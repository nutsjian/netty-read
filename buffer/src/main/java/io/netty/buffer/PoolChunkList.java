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

import io.netty.util.internal.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static java.lang.Math.*;

/**
 * 为什么要通过一个PoolChunkList来管理PoolChunk，并且PoolChunkList还会自身PoolChunk的使用比例来调整PoolChunkList位置
 *
 * qInit -> q000 <---> q025 <---> q050 <---> q075 <---> q100
 *
 * 当 q000 这个PoolChunkList的内存使用比例增大后，会往后移动，相反当 q050 这个PoolChunkList的内存使用比例减少后，会往前移动，其它以此类推。
 *
 * 那为什么要这样设计呢？有什么好处？
 * ---------------------------------------------------------------
 * 1）首先 PoolChunk 本身是从连续内存中分配一小段连续的内存，这样实际使用的内存读写很方便，但是随着内存的不断分配和回收，PoolChunk 中可能存在很多碎片。
 * 碎片越来越多后，我们想分配一段连续的内存的失败几率就会提高。
 *
 * 2）针对这种情况，我们可以把使用比例较大的PoolChunk 放到后面，而先使用比例更小的 PoolChunk，这样分配成功的几率就提高了。
 *
 * 3）当后面内存使用比例较大的 q075 q100等，随着内存的释放，PoolChunk中会存在越来越多的大块连续内存，所以当内存使用比例慢慢减少后，需要往前移动。当然这个往前
 * 调整的时机是在释放内存的时候（PoolChunkList.free()）
 *
 * minUsage、maxUsage 的含义和用途
 * ---------------------------------------------------------------
 * 1）
 *
 *
 * PoolChunk 的生命周期？
 * ---------------------------------------------------------------
 * 1）一个 PoolChunk 的生命周期并不是在一个固定的 PoolChunkList 中的，随着内存的分配和释放，它会进入到不同的 PoolChunkList 中
 *   怎么理解？
 *
 * 2）我们注意到，两个相邻的的 PoolChunkList ，前一个 PoolChunkList 的 maxUsage 和后一个 PoolChunkList 的 minUsage 的值必须有一段交叉的值来缓冲，否则会
 * 出现某个 usage 在临界值的 PoolChunk 不停的在两个 PoolChunkList 之间来回移动。
 *  qInit       minUsage MIN            maxUsage 25
 *  q000        minUsage 1              maxUsage 50
 *  q025        minUsage 25             maxUsage 75
 *  q050        minUsage 50             maxUsage 100
 *  q075        minUsage 75             maxUsage 100
 *  q100        minUsage 100            maxUsage MAX
 *
 * 补充：PoolChunk 在一个 PoolChunkList 中，当 PoolChunk 分配内存(allocate)、释放内存(free)后，会判断该 PoolChunk 的 usage()，看是否需要移动该 PoolChunk。
 *  分配：调用 PoolChunk.allocate()
 *  释放：调用 PoolChunk.free()
 *
 * 移动判断依据：
 *  分配后：判断当前 PoolChunk 的 usage > maxUsage ? 若大于，则往后移动 PoolChunkList，表示该 PoolChunk 的内存使用比例较大，连续内存空间很少，分配失败的几率很大
 *  释放后：判断当前 PoolChunk 的 usage < minUsage ? 若小于，则向前移动 PoolChunkList，表示该 PoolChunk 的内存使用比例较小，可能存在较多的连续内存空间，分配成功几率很大
 *
 *
 * 3）我们注意到，尾结点PoolChunkList上的 maxUsage 一定要大于 100，这里取值Integer.MAX_VALUE，这样 PoolChunk 占满后才不会被继续往后挪（后面也没有可用的 PoolChunkList 了）
 *
 *
 * 增加结点、删除结点方法解析：
 *      add0(PoolChunk<T> chunk)
 *      remove(PoolChunk<T> cur)
 * ---------------------------------------------------------------
 *
 *
 *
 */
final class PoolChunkList<T> implements PoolChunkListMetric {
    private static final Iterator<PoolChunkMetric> EMPTY_METRICS = Collections.<PoolChunkMetric>emptyList().iterator();
    private final PoolArena<T> arena;
    private final PoolChunkList<T> nextList;
    private final int minUsage;     // 当前PoolChunkList中的 PoolChunk 的最小使用比例
    private final int maxUsage;     // 当前PoolChunkList中的 PoolChunk 的最大使用比例
    private final int maxCapacity;

    // PoolChunk 有 prev、next 指针，所以这里只用一个结点 head，就可以维护一整个 PoolChunk 链
    private PoolChunk<T> head;

    // This is only update once when create the linked like list of PoolChunkList in PoolArena constructor.
    private PoolChunkList<T> prevList;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunkList(PoolArena<T> arena, PoolChunkList<T> nextList, int minUsage, int maxUsage, int chunkSize) {
        assert minUsage <= maxUsage;
        this.arena = arena;
        this.nextList = nextList;
        this.minUsage = minUsage;
        this.maxUsage = maxUsage;

        // 计算最大允许容量
        maxCapacity = calculateMaxCapacity(minUsage, chunkSize);
    }

    /**
     * Calculates the maximum capacity of a buffer that will ever be possible to allocate out of the {@link PoolChunk}s
     * that belong to the {@link PoolChunkList} with the given {@code minUsage} and {@code maxUsage} settings.
     */
    private static int calculateMaxCapacity(int minUsage, int chunkSize) {
        minUsage = minUsage0(minUsage);

        if (minUsage == 100) {
            // If the minUsage is 100 we can not allocate anything out of this list.
            return 0;
        }

        // Calculate the maximum amount of bytes that can be allocated from a PoolChunk in this PoolChunkList.
        //
        // As an example:
        // - If a PoolChunkList has minUsage == 25 we are allowed to allocate at most 75% of the chunkSize because
        //   this is the maximum amount available in any PoolChunk in this PoolChunkList.
        return  (int) (chunkSize * (100L - minUsage) / 100L);
    }

    void prevList(PoolChunkList<T> prevList) {
        assert this.prevList == null;
        this.prevList = prevList;
    }

    /**
     * 为 buf 分配指定大小的内存
     *
     *
     *
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        // 如果 PoolChunkList 中没有 PoolChunk 或者 请求分配的内存大小 > 该 PoolChunkList 的最大内存大小
        // 则返回 false
        if (head == null || normCapacity > maxCapacity) {
            // Either this PoolChunkList is empty or the requested capacity is larger then the capacity which can
            // be handled by the PoolChunks that are contained in this PoolChunkList.
            return false;
        }

        // 遍历 PoolChunkList 中的 PoolChunk
        // 可以通过 head 指针遍历，因为 PoolChunk 中本身有 prev、next 指针
        for (PoolChunk<T> cur = head;;) {
            long handle = cur.allocate(normCapacity);
            // handle < 0 表示分配失败，找到下一个 PoolChunk 尝试分配
            if (handle < 0) {
                cur = cur.next;
                if (cur == null) {
                    return false;
                }
            } else {
                // 分配成功，则将分配到的资源赋值给 ByteBuf
                cur.initBuf(buf, handle, reqCapacity);
                if (cur.usage() >= maxUsage) {
                    remove(cur);
                    nextList.add(cur);
                }
                return true;
            }
        }
    }

    /**
     *  释放指定 PoolChunk 内的指定 Page 或 Page 内的 Subpage
     *
     *  handle：PoolChunk 中的某个 Page
     *
     *  释放 PoolChunk 内存后，还需要计算现在的 PoolChunk 的内存使用比例，并决定它是放在哪个 PoolChunkList 中 （q000、q025、q050、q075、q100）
     */
    boolean free(PoolChunk<T> chunk, long handle) {
        chunk.free(handle);
        // 如果当前 PoolChunk 的内存使用比例 < 当前 PoolChunkList 的规定的 PoolChunk 最小使用比例
        // 则在本 PoolChunkList 中移除该 PoolChunk，remove(chunk)
        // 并移动到前一个 PoolChunkList 中，move0(chunk)
        if (chunk.usage() < minUsage) {
            remove(chunk);
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }
        return true;
    }

    /**
     * assert <表达式>，如果表达式 = true，则程序继续执行
     * 把参数 PoolChunk 尝试移动到当前 PoolChunkList 中，如果 PoolChunk 的内存使用比例 < 当前 PoolChunkList 的内存使用比率，则继续往前查看 PoolChunkList，
     * 继续尝试把 PoolChunk 放在前一个 PoolChunkList 中。
     */
    private boolean move(PoolChunk<T> chunk) {
        assert chunk.usage() < maxUsage;

        if (chunk.usage() < minUsage) {
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }

        // PoolChunk fits into this PoolChunkList, adding it here.
        add0(chunk);
        return true;
    }

    /**
     * Moves the {@link PoolChunk} down the {@link PoolChunkList} linked-list so it will end up in the right
     * {@link PoolChunkList} that has the correct minUsage / maxUsage in respect to {@link PoolChunk#usage()}.
     */
    private boolean move0(PoolChunk<T> chunk) {
        if (prevList == null) {
            // 如果没有前一个 PoolChunkList，则直接返回false
            // There is no previous PoolChunkList so return false which result in having the PoolChunk destroyed and
            // all memory associated with the PoolChunk will be released.
            assert chunk.usage() == 0;
            return false;
        }
        return prevList.move(chunk);
    }

    /**
     * 将一个 PoolChunk 加入到 PoolChunkList 中
     */
    void add(PoolChunk<T> chunk) {
        // 如果要加入的PoolChunk的使用率 >= 当前PoolChunkList的最大内存使用率
        // 则把该 PoolChunk 加入到下一个 PoolChunkList 中
        if (chunk.usage() >= maxUsage) {
            nextList.add(chunk);
            return;
        }
        add0(chunk);
    }

    /**
     * Adds the {@link PoolChunk} to this {@link PoolChunkList}.
     */
    void add0(PoolChunk<T> chunk) {
        // 把当前 PoolChunkList 设置给 PoolChunk
        chunk.parent = this;

        // 如果当前 PoolChunkList 没有 head 指针，则把该 PoolChunk 设置为 head 指针
        if (head == null) {
            head = chunk;
            chunk.prev = null;
            chunk.next = null;
        } else { // 如果当前 PoolChunkList 存在 head 指针，则把该 Chunk 加入到该 PoolChunkList 中
            /*
             * 假设当前PoolChunkList的PoolChunk如下：
             * chunk0 <---> chunk1 <---> chunk2
             *   |
             *  head
             *
             * 新增一个PoolChunk（chunk-x）
             *
             * chunk-x ---> chunk0      (chunk.next = head)
             * chunk-x <--- chunk0      (head.prev = chunk)
             * head = chunk             (把head指针指向新增的chunk)
             *
             * 最后得到的结果如下：
             * chunk-x <---> chunk0 <---> chunk1 <---> chunk2
             *   |
             *  head
             */
            chunk.prev = null;
            chunk.next = head;
            head.prev = chunk;
            head = chunk;
        }
    }

    /**
     * 在 PoolChunkList 中移除指定 PoolChunk
     */
    private void remove(PoolChunk<T> cur) {
        if (cur == head) {  // 如果移除的是head结点
            head = cur.next;            // 把head结点的下一个 PoolChunk 设置为 head 结点
            if (head != null) {         // 如果存在
                head.prev = null;       // 则把 "新的head结点" 的 prev 引用置空，这样原先的 head 结点就没有引用了，等待 GC
            }
        } else {    // 如果移除的不是head结点
            PoolChunk<T> next = cur.next;       // 把要移除的下一个结点存为临时变量 next
            cur.prev.next = next;               // 把要移除结点的前一个结点的 next 指针，指向临时变量 next
            if (next != null) {                 // 如果临时变量 next 存在
                next.prev = cur.prev;           // 则把临时变量 next 的 prev 指针，指向要移除结点的 prev 结点
            }                                   // 综上：实际操作就是打断要移除结点的 prev、next 连线，使其不被引用，后再等待GC
        }
    }

    @Override
    public int minUsage() {
        return minUsage0(minUsage);
    }

    @Override
    public int maxUsage() {
        return min(maxUsage, 100);
    }

    private static int minUsage0(int value) {
        return max(1, value);
    }

    @Override
    public Iterator<PoolChunkMetric> iterator() {
        synchronized (arena) {
            if (head == null) {
                return EMPTY_METRICS;
            }
            List<PoolChunkMetric> metrics = new ArrayList<PoolChunkMetric>();
            for (PoolChunk<T> cur = head;;) {
                metrics.add(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
            }
            return metrics.iterator();
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        synchronized (arena) {
            if (head == null) {
                return "none";
            }

            for (PoolChunk<T> cur = head;;) {
                buf.append(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
                buf.append(StringUtil.NEWLINE);
            }
        }
        return buf.toString();
    }

    void destroy(PoolArena<T> arena) {
        PoolChunk<T> chunk = head;
        while (chunk != null) {
            arena.destroyChunk(chunk);
            chunk = chunk.next;
        }
        head = null;
    }
}
