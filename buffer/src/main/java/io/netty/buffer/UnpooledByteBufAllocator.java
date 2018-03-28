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

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;

/**
 * Simplistic {@link ByteBufAllocator} implementation that does not pool anything.
 *
 * 这是一个“非池化”字节 buf 分配器
 */
public final class UnpooledByteBufAllocator extends AbstractByteBufAllocator implements ByteBufAllocatorMetricProvider {

    // 字节 buf 分配器 Metric
    private final UnpooledByteBufAllocatorMetric metric = new UnpooledByteBufAllocatorMetric();
    // 是否关闭资源泄漏探测
    private final boolean disableLeakDetector;

    /**
     * Default instance which uses leak-detection for direct buffers.
     * 默认非池类字节buf分配器
     */
    public static final UnpooledByteBufAllocator DEFAULT =
            new UnpooledByteBufAllocator(PlatformDependent.directBufferPreferred());

    /**
     * Create a new instance which uses leak-detection for direct buffers.
     *
     * @param preferDirect {@code true} if {@link #buffer(int)} should try to allocate a direct buffer rather than
     *                     a heap buffer
     */
    public UnpooledByteBufAllocator(boolean preferDirect) {
        this(preferDirect, false);
    }

    /**
     * Create a new instance
     *
     * @param preferDirect {@code true} if {@link #buffer(int)} should try to allocate a direct buffer rather than
     *                     a heap buffer
     * @param disableLeakDetector {@code true} if the leak-detection should be disabled completely for this
     *                            allocator. This can be useful if the user just want to depend on the GC to handle
     *                            direct buffers when not explicit released.
     */
    public UnpooledByteBufAllocator(boolean preferDirect, boolean disableLeakDetector) {
        super(preferDirect);
        this.disableLeakDetector = disableLeakDetector;
    }

    /**
     * 创建一个堆内内存 Buf ，注意该方法是 protected 的
     * @param initialCapacity 初始容量
     * @param maxCapacity   最大容量
     */
    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        /*
         * 堆内内存有两种 InstrumentedUnpooledUnsafeHeapByteBuf 和 InstrumentedUnpooledHeapByteBuf
         *
         * 这两者都是 静态内部类
         *
         */
        return PlatformDependent.hasUnsafe() ?
                new InstrumentedUnpooledUnsafeHeapByteBuf(this, initialCapacity, maxCapacity) :
                new InstrumentedUnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
    }

    /**
     * 创建一个堆外内存 buf，注意该方法是 protected 的
     * @param initialCapacity 初始容量
     * @param maxCapacity 最大容量
     * @return 返回堆外内存 buf
     */
    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        final ByteBuf buf;
        /*
         * 从下面可以看出 Direct 内存分为三种实现：
         * 1. InstrumentedUnpooledUnsafeNoCleanerDirectByteBuf
         * 2. InstrumentedUnpooledUnsafeDirectByteBuf
         * 3. InstrumentedUnpooledDirectByteBuf
         *
         * 这三个也是 静态内部类
         */
        if (PlatformDependent.hasUnsafe()) {
            buf = PlatformDependent.useDirectBufferNoCleaner() ?
                    new InstrumentedUnpooledUnsafeNoCleanerDirectByteBuf(this, initialCapacity, maxCapacity) :
                    new InstrumentedUnpooledUnsafeDirectByteBuf(this, initialCapacity, maxCapacity);
        } else {
            buf = new InstrumentedUnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
        }
        return disableLeakDetector ? buf : toLeakAwareBuffer(buf);
    }

    @Override
    public CompositeByteBuf compositeHeapBuffer(int maxNumComponents) {
        CompositeByteBuf buf = new CompositeByteBuf(this, false, maxNumComponents);
        return disableLeakDetector ? buf : toLeakAwareBuffer(buf);
    }

    @Override
    public CompositeByteBuf compositeDirectBuffer(int maxNumComponents) {
        CompositeByteBuf buf = new CompositeByteBuf(this, true, maxNumComponents);
        return disableLeakDetector ? buf : toLeakAwareBuffer(buf);
    }

    @Override
    public boolean isDirectBufferPooled() {
        return false;
    }

    @Override
    public ByteBufAllocatorMetric metric() {
        return metric;
    }

    void incrementDirect(int amount) {
        metric.directCounter.add(amount);
    }

    void decrementDirect(int amount) {
        metric.directCounter.add(-amount);
    }

    // 这里会调用度量器中计数器的 add() 方法，来更新内存使用量
    void incrementHeap(int amount) {
        metric.heapCounter.add(amount);
    }

    void decrementHeap(int amount) {
        metric.heapCounter.add(-amount);
    }

    /**
     * 静态内部类，继承 UnpooledUnsafeHeapByteBuf
     */
    private static final class InstrumentedUnpooledUnsafeHeapByteBuf extends UnpooledUnsafeHeapByteBuf {
        InstrumentedUnpooledUnsafeHeapByteBuf(UnpooledByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
            super(alloc, initialCapacity, maxCapacity);
        }

        // 上面的构造方法，会调用 UnpooledUnsafeHeapByteBuf，进而继续调用 allocateArray 来分配内存
        // 这里重写了 UnpooledUnsafeHeapByteBuf 的 allocateArray() 方法，所以具体的 对内存分配是在这个
        // 方法中
        @Override
        byte[] allocateArray(int initialCapacity) {
            // 调用父类的方法，在堆内内存中开辟一块内存，并分配
            byte[] bytes = super.allocateArray(initialCapacity);
            // 更新 buf 分配器中 Heap 的内存使用量（度量器中的计数器）
            ((UnpooledByteBufAllocator) alloc()).incrementHeap(bytes.length);
            return bytes;
        }

        @Override
        void freeArray(byte[] array) {
            int length = array.length;
            // 调用父类的 freeArray 来释放堆内内存
            super.freeArray(array);
            // 更新 buf 分配器你中 heap 内存的使用量（度量器中的计数器）
            ((UnpooledByteBufAllocator) alloc()).decrementHeap(length);
        }
    }

    private static final class InstrumentedUnpooledHeapByteBuf extends UnpooledHeapByteBuf {
        InstrumentedUnpooledHeapByteBuf(UnpooledByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
            super(alloc, initialCapacity, maxCapacity);
        }

        @Override
        byte[] allocateArray(int initialCapacity) {
            byte[] bytes = super.allocateArray(initialCapacity);
            ((UnpooledByteBufAllocator) alloc()).incrementHeap(bytes.length);
            return bytes;
        }

        @Override
        void freeArray(byte[] array) {
            int length = array.length;
            super.freeArray(array);
            ((UnpooledByteBufAllocator) alloc()).decrementHeap(length);
        }
    }

    private static final class InstrumentedUnpooledUnsafeNoCleanerDirectByteBuf
            extends UnpooledUnsafeNoCleanerDirectByteBuf {
        InstrumentedUnpooledUnsafeNoCleanerDirectByteBuf(
                UnpooledByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
            super(alloc, initialCapacity, maxCapacity);
        }

        // 上面的构造方法会调用父类 UnpooledUnsafeNoCleanerDirectByteBuf 的构造方法 super
        // 进而会调用父类的 allocateDirect方法来分配堆外内存
        // 这个实现类，重写了父类的 allocateDirect() 方法，目的是在分配后能够在 度量器中的计数器来记录 堆外内存的分配数量
        @Override
        protected ByteBuffer allocateDirect(int initialCapacity) {
            // 调用 super.allocateDirect()，那实际上真正的开辟和分配堆外内存，还是在父类中 UnpooledUnsafeNoCleanerDirectByteBuf
            ByteBuffer buffer = super.allocateDirect(initialCapacity);
            // 通过度量器计数器来记录堆外内存的分配数量
            ((UnpooledByteBufAllocator) alloc()).incrementDirect(buffer.capacity());
            return buffer;
        }

        @Override
        ByteBuffer reallocateDirect(ByteBuffer oldBuffer, int initialCapacity) {
            int capacity = oldBuffer.capacity();
            ByteBuffer buffer = super.reallocateDirect(oldBuffer, initialCapacity);
            ((UnpooledByteBufAllocator) alloc()).incrementDirect(buffer.capacity() - capacity);
            return buffer;
        }

        @Override
        protected void freeDirect(ByteBuffer buffer) {
            int capacity = buffer.capacity();
            // 释放堆外内存
            super.freeDirect(buffer);
            // 更新度量器计数器
            ((UnpooledByteBufAllocator) alloc()).decrementDirect(capacity);
        }
    }

    private static final class InstrumentedUnpooledUnsafeDirectByteBuf extends UnpooledUnsafeDirectByteBuf {
        InstrumentedUnpooledUnsafeDirectByteBuf(
                UnpooledByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
            super(alloc, initialCapacity, maxCapacity);
        }

        @Override
        protected ByteBuffer allocateDirect(int initialCapacity) {
            ByteBuffer buffer = super.allocateDirect(initialCapacity);
            ((UnpooledByteBufAllocator) alloc()).incrementDirect(buffer.capacity());
            return buffer;
        }

        @Override
        protected void freeDirect(ByteBuffer buffer) {
            int capacity = buffer.capacity();
            super.freeDirect(buffer);
            ((UnpooledByteBufAllocator) alloc()).decrementDirect(capacity);
        }
    }

    private static final class InstrumentedUnpooledDirectByteBuf extends UnpooledDirectByteBuf {
        InstrumentedUnpooledDirectByteBuf(
                UnpooledByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
            super(alloc, initialCapacity, maxCapacity);
        }

        @Override
        protected ByteBuffer allocateDirect(int initialCapacity) {
            ByteBuffer buffer = super.allocateDirect(initialCapacity);
            ((UnpooledByteBufAllocator) alloc()).incrementDirect(buffer.capacity());
            return buffer;
        }

        @Override
        protected void freeDirect(ByteBuffer buffer) {
            int capacity = buffer.capacity();
            super.freeDirect(buffer);
            ((UnpooledByteBufAllocator) alloc()).decrementDirect(capacity);
        }
    }

    // 度量器
    // 因为内存分配分为：堆内内存 + 堆外内存，所以有两个成员变量来记录
    private static final class UnpooledByteBufAllocatorMetric implements ByteBufAllocatorMetric {
        // direct 内存使用计数器
        final LongCounter directCounter = PlatformDependent.newLongCounter();
        // heap 内存使用计数器
        final LongCounter heapCounter = PlatformDependent.newLongCounter();

        @Override
        public long usedHeapMemory() {
            return heapCounter.value();
        }

        @Override
        public long usedDirectMemory() {
            return directCounter.value();
        }

        @Override
        public String toString() {
            return StringUtil.simpleClassName(this) +
                    "(usedHeapMemory: " + usedHeapMemory() + "; usedDirectMemory: " + usedDirectMemory() + ')';
        }
    }
}
