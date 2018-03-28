/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    // Page 所在的 memoryMap 索引
    // 也就是说该Subpage分配是在 memoryMap[memoryMapIdx] 下的 Page 中切分的
    // 而且，memoryMapIdx 只可能是叶子结点的索引，因为分配的内存只有小于一个Page的Size，才会切分Page成 N 个 Subpage
    private final int memoryMapIdx;     // 当前Page在Chunk中的ID
    private final int runOffset;        // 当前Page在Chunk中的偏移量
    private final int pageSize;

    // 通过对每个二进制位的标记来修改一段内存的占用状态
    private final long[] bitmap;

    PoolSubpage<T> prev;                // 前一个结点，配合PoolArena看
    PoolSubpage<T> next;

    boolean doNotDestroy;               // 表示该Page在使用中，不能被清除
    int elemSize;                       // 该Page切分后每一段的大小
    // 一个 Page 切分成 Subpage 的块的数目
    private int maxNumElems;
    // 一个bitmap 64 位，通过 bitmap 来记录切分成 Subpage 的块的可用性
    private int bitmapLength;           // bitmap 需要用到的长度
    // 是针对该 Page 切分 Subpage N 等分后，针对 N 的偏移量
    // nextAvail 就是为了确定分配的内存，分配在哪个位置（可用的位置）
    private int nextAvail;              // 可用的段数量

    // 所有可供分配的 索引总数
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    // 该构造函数用于给 PoolArena 构造 tinySubpagePools、smallSubpagePools 链表时使用
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }


    // 初始化PoolSubpage
    // 这里bitmap = 8，因为最小分配内存是 16B，而一个 long 有 64位，每一位可以表示占用/没占用，所以 8192 / 16 / 64 = 8，即使用 8 个bitmap 就可以表示所有块Elem的占用情况
    // 而init方法，会根据 elemSize（具体块的大小）来计算需要多少个 bitmap，记录为 bitmapLength
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(head, elemSize);
    }


    // 初始化
    // 设置每个块的大小（Subpage Size） elemSize
    // 设置总共有多少块、多少可用量，maxNumElems、numAvail
    // 初始化可用偏移量 nextAvail = 0
    // 计算可用偏移量需要占用的bitmap的长度 bitmapLength
    // 如果块数不是 64 的倍数，需要对齐，把 bitmapLength 自增 1
    // 初始化bitmap，都设置为0，表示未使用
    // 把头结点放入 Pool
    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            maxNumElems = numAvail = pageSize / elemSize;
            nextAvail = 0;
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     *
     * 返回bitmap的索引
     *
     */
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        // 判断此 Page 是否还有 块 可用，以及是否被销毁了
        // 如果没有可用空间或者是被销毁了则返回 -1
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // 获取下一个可用的块的索引
        // 假设我们第一次分配的内存大小是 32 ，则Page总共会被切分为 256 块，可以通过 4 个bitmap来记录块的状态，如下
        // 0000 0000 0000 .... 0000 0000 0000   64位     bitmap[0]
        // 0000 0000 0000 .... 0000 0000 0000   64位     bitmap[1]
        // 0000 0000 0000 .... 0000 0000 0000   64位     bitmap[2]
        // 0000 0000 0000 .... 0000 0000 0000   64位     bitmap[3]
        // 当我们分配了129块的时候，bitmap结构如下
        // 1111 1111 1111 .... 1111 1111 1111   64位     bitmap[0]
        // 1111 1111 1111 .... 1111 1111 1111   64位     bitmap[1]
        // 0000 0000 0000 .... 0000 0000 0001   64位     bitmap[2]
        // 0000 0000 0000 .... 0000 0000 0000   64位     bitmap[3]
        //
        // 此时，我们再通过getNextAvail()来获取下一个可用的bitmapIdx，得到的结果就是 130（具体的计算可以跟踪一下getNextAvail()方法）
        //

        final int bitmapIdx = getNextAvail();

        // >>> 无符号右移
        // q 是该可用块在bitmap[]中的索引
        // r 是该可用快在一个bitmap中的位置
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        // 假设 r = 3
        // bitmap[q] = (... 0000 0000 0111)
        // r = (... 0000 0000 0111) & (.... 1111 1111 1111) = 0111
        // 1L << r => (.... 0000 0000 0001) << 3 => .... 0000 0000 1111
        // bitmap[q] |= 1L << r
        // 等效于如下
        // (.... 0000 0000 0111)
        // (.... 0000 0000 1111)
        //
        // 所以bitmap[q] = (.... 0000 0000 1111)
        //
        // 处理后的bitmap[q] 实际上就是（从右往左）把空闲的那一位置为 1
        //
        bitmap[q] |= 1L << r;

        if (-- numAvail == 0) {
            removeFromPool();
        }

        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    /**
     *
     * +--------------+     +--------------+
     * |              |     |              |
     * |     prev     | --> |     head     |
     * |              |     |              |
     * +--------------+     +--------------+
     *
     *
     */
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }


    /**
     * 获取下一个可用的索引，索引是针对bitmap的索引
     *
     *
     */
    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        // 每次分配完成，nextAvail 被置为 -1，下次调用getNextAvail只能重新计算 findNextAvail()
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    /**
     *
     * 获取下一个可用的区域，通过bitmap来找空闲区域
     * 举例：bitmap = long[8]，则
     *      bitmapLength = 8
     *
     *                 0    1    2    3    4    5    6    7    8    9    10   11   12   13   14  15
     *      若：bits = 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111
     *        ~bits = 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 = 0
     *
     *     若：bits = 1111 1111 1111 1111 1011 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111
     *       ~bits = 0000 0000 0000 0000 0100 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 != 0
     *
     *     ~bits != 0，说明有空闲区域，空闲区域用 0 表示
     *
     *     如果存在，则通过findNextAvail0 查找
     *
     */
    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            // 非运算，不等于0，代表这个long类型的数字存在 "1"
            if (~bits != 0) {
                // 深入查找具体在哪个位置
                // i 是bitmap[]数组的索引
                // bits 是遍历到的存在可用内存块的 bitmap
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    /**
     * @param i 空闲区域所在的long数组的索引，即bitmap的索引
     * @param bits 具体哪个 bitmap
     * @return bitmap的位置
     *
     * maxNumElems = 该 Page 被分割成 Subpage 的总数
     * baseVal = i << 6，对索引 i 左移6位 ==>  i * 64 （有点像计算偏移量的感觉），就理解成索引 i 的基准偏移量吧
     *
     *
     * 举例解释 bits & 1 ，bits >>>= 1
     *  假设：i = 3, bits = 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0011 1111 1111
     *  第一次循环：bits & 1 = (... 0011 1111 1111) & 1 = 1 然后 bits >>>= 1，右移 1 位，bits = ... 0001 1111 1111
     *  第二次循环：bits & 1 = (... 0001 1111 1111) & 1 = 1 然后 bits >>>= 1，右移 1 位，bits = ... 0000 1111 1111
     *  第三次循环：bits & 1 = (... 0000 1111 1111) & 1 = 1 然后 bits >>>= 1，右移 1 位，bits = ... 0000 0111 1111
     *  ......
     *  第N次循环：bits & 1 = (.... 0000 0000 0000) & 1 = 0，然后设置 int val = baseVal | j
     *
     *  经过 N 次循环后，得到 bits & 1 == 0 的时候，j = (右移的次数 - 1) = bits 从右往左数有多少个"1"
     *
     *  所以 baseVal | j 的值 => baseVal + j ？为什么 baseVal 或运算 j 等效于 baseVal + j
     *  答案：因为baseVal必定是64的倍数，而 j 的取值是 【0， 63】,所以必然 baseVal 的低 6 位为 0，而或运算正好就等于 j 的值
     *
     *
     *
     */
    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        // 计算基址 << 6，相当于 *64
        final int baseVal = i << 6;

        // 计算val，val = baseVal + j
        // baseVal 是bitmap 索引 i 的基准偏移量
        // j 是一个 bitmap 中从右往左的空闲的 偏移量
        // val就是总的偏移量，该偏移量不能大于Subpage的总数
        for (int j = 0; j < 64; j ++) {
            // 每次都把bits的最低位 与 0 做与运算，检查这个最低位是否为 "0"，如果是 "0" 代表可用
            // 否则 bits >>>= 1，右移一位，检查下一位
            if ((bits & 1) == 0) {
                // 基址和遍历j 做或运算
                int val = baseVal | j;
                // 得到的地址如果 < maxNumElems，则返回，说明没有越界（也就是说val这个地址是存在 elem 块的）
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }

            // 继续寻找
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
