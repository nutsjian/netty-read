/*
 * Copyright 2013 The Netty Project
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

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 *
 * Recycler的三个核心方法
 *      get()                   获取一个对象
 *      recycle(T, Handle)      回收一个对象，T 为对象泛型
 *           DefaultHandle.recycle();
 *      newObject(Handle)       当没有可用对象时创建对象的实现方法
 *
 *           http://blog.csdn.net/levena/article/details/78144924
 *
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    // 表示一个不需要回收的包装对象
    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {    // 用于maxCapacityPerThread == 0时，关闭对象回收功能
            // NOOP
        }
    };

    // 线程安全的自增计数器，用来做唯一标记的
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);

    // static 变量，生成并获取一个唯一ID，标记当前的线程
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();

    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 32768; // Use 32k instances as default.

    // 每个线程的Stack最多缓存多少个对象
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;

    // 初始化容量
    private static final int INITIAL_CAPACITY;

    // 最大可共享的容量
    private static final int MAX_SHARED_CAPACITY_FACTOR;

    // WeakOrderQueue最大数量
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;

    // WeakOrderQueue中的数组DefaultHandle<?>[] elements容量
    private static final int LINK_CAPACITY;

    // 掩码
    private static final int RATIO;

    static {

        // 对各个参数初始化，做到了可配置，可以根据实际情况，调节对象池的参数
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    private final int ratioMask;
    private final int maxDelayedQueuesPerThread;

    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    ratioMask, maxDelayedQueuesPerThread);
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        // 根据ratio获取一个掩码，默认为8
        // 那么ratioMask的二进制就是"111"
        ratioMask = safeFindNextPositivePowerOfTwo(ratio) - 1;
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    @SuppressWarnings("unchecked")
    public final T get() {
        // 这里通过修改maxCapacityPerThread = 0，可以关闭回收功能，默认值是32768 = 32K
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }

        // 获取当前线程的Stack
        Stack<T> stack = threadLocal.get();

        // 从对象池中获取对象
        DefaultHandle<T> handle = stack.pop();
        if (handle == null) {
            handle = stack.newHandle();

            // 没有对象，则调用子类的newObject方法创建新的对象
            // newObject 需要子类去实现
            // 这里的value是一个泛型，可以是byte[]，或者java.nio.ByteBuffer（分别是堆内存、直接内存）
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     * 已经废弃
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    // 返回当前线程的对象池的对象数
    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    // 返回当前Stack的数量（也就是线程的数量）
    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    // Handle 主要提供一个 recycle 接口，用于提供对象回收的具体实现
    // 每个 Handle 关联一个 value 字段，用于存放具体的池化对象
    // 【记住】在对象池中，所有的池化对象都被这个Handle包装，Handle是对象池管理的基本单位
    public interface Handle<T> {
        void recycle(T object);
    }

    // 默认的Handle - DefaultHandle
    // 是Stack的包装对象，持有Stack的引用，可以回收自己到Stack中
    static final class DefaultHandle<T> implements Handle<T> {
        // 最新一次回收线程的 id
        private int lastRecycledId;
        // 也是一个标记，是用来做回收前的校验的
        private int recycleId;

        // 标记是否已经被回收
        boolean hasBeenRecycled;

        // stack 是具体维护对象池数据的
        // DefaultHandle 持有的 Stack 引用
        private Stack<?> stack;
        private Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }
            // 可以回收自己到 Stack 中
            stack.push(this);
        }
    }

    // 这是一个线程本地变量，每个线程都有自己的Map<Stack<?>, WeakOrderQueue>>
    // 根据 Stack 可以获取到对应的 WeakOrderQueue
    // 需要注意的是，这边的两个对象都是 弱引用，WeakReference
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
                @Override
                protected Map<Stack<?>, WeakOrderQueue> initialValue() {
                    // 使用WeakHashMap，保证对 key 也就是 Stack 是弱引用，一旦 Stack 没有强引用了，会被回收，WeakHashMap 不会无限占用内存
                    return new WeakHashMap<Stack<?>, WeakOrderQueue>();
                }
            };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    // 可以把WeakOrderQueue看做一个对象仓库
    // Stack内只维护一个Handle数组，用于直接向Recycler提供服务，当从这个数组中拿不到对象的时候则会寻找对应
    // WeakOrderQueue并调用其transfer方法向Stack供给对象
    private static final class WeakOrderQueue {

        // 用来标记空的WeakOrderQueue，在达到 WeakOrderQueue 数量上限时放入这个，表示结束了
        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        // Link 对象本身会作为读索引
        @SuppressWarnings("serial")
        // 为什么要继承AtomicInteger？保证Link是一个线程安全的容器，保证了多线程安全和可见性，因为每个Stack绑定的线程都有WeakOrderQueue的引用，会有多线程操作的情况
        private static final class Link extends AtomicInteger {
            // 维护一个数组，容量默认为 16
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            // 读索引
            private int readIndex;

            // 下一个索引，WeakOrderQueue 有多个时，之间遍历靠 next 指向下一个 WeakOrderQueue
            private Link next;
        }

        // chain of data items
        // 头指针和尾指针
        private Link head, tail;
        // pointer to another queue of delayed items for the same stack
        // 指向下一个WeakOrderQueue
        private WeakOrderQueue next;
        // WeakReference，当GC运行时，就会被回收，获取这个对象，则通过 get() 方法获取，如果为空，说明被 GC 回收了
        // 拥有者,干嘛的?? 要注意到这是一个弱引用,就是不会影响Thread对象的GC的,如果thread为空,owner.get()会返回null
        private final WeakReference<Thread> owner;

        // WeakOrderQueue的唯一标识
        private final int id = ID_GENERATOR.getAndIncrement();

        // 允许的最大共享容量
        private final AtomicInteger availableSharedCapacity;

        // 用于初始化 DUMMY，遇到DUMMY就知道要抛弃了
        private WeakOrderQueue() {
            owner = null;
            availableSharedCapacity = null;
        }

        // 进入这个方法的前提是当前线程与Stack绑定线程不一致
        // 在 Stack 的io.netty.util.Recycler.Stack.pushLater()中如果没有WeakOrderQueue,会调用这里new一个
        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            // 初始化头尾指针，指向这个新创建的Link
            head = tail = new Link();
            // 表示当前的WeakOrderQueue是被哪个线程拥有的. 因为只有不同线程去回收对象才会进到这个方法,所以thread不是这stack对应的线程
            // 这里的WeakReference,对Thread是一个弱引用,所以Thread在没有强引用时就会被回收(线程也是可以回收的对象)
            owner = new WeakReference<Thread>(thread);
            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            // 这里很重要,我们没有把Stack保存到WeakOrderQueue中
            // 因为Stack是WeakHashMap的key
            // 我们只是持有head 和 tail的引用,就可以遍历WeakOrderQueue


            availableSharedCapacity = stack.availableSharedCapacity;
        }


        // stack.setHead(queue)必须在构造器外进行,防止对象溢出.(我查看作者的该动记录是为了修改以前的不安全发布的构造方法)
        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            stack.setHead(queue);   //这个stack,头指针指向 这个新创建的WeakOrderQueue
            return queue;
        }

        private void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            return reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)       // 先预约space容量
                    ? WeakOrderQueue.newQueue(stack, thread) : null;                // 预约成功, 对当前stack创建一个新的WeakOrderQueue
        }

        // 容量不够就返回false; 够的话就减去space大小.
        // 通过CAS + Atomic 来保证线程安全
        private static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
            assert space >= 0;
            for (;;) {
                int available = availableSharedCapacity.get();
                if (available < space) {     // 如果剩余可用容量小于 LINK_CAPACITY,返回false
                    return false;
                }
                if (availableSharedCapacity.compareAndSet(available, available - space)) { // 调用availableSharedCapacity线程安全的CAS方法
                    return true;
                }
            }
        }

        // availableSharedCapacity加上space,就是恢复前面减去的space大小
        private void reclaimSpace(int space) {
            assert space >= 0;
            // availableSharedCapacity和上面的方法会存在并发,所以采用原子类型.
            availableSharedCapacity.addAndGet(space);
        }


        void add(DefaultHandle<?> handle) {
            // 更新最近一次回收的id, 注意这里只更新了lastRecycledId, recycleId没有更新, 等到真正回收的时候,会改成一致的.
            handle.lastRecycledId = id;

            Link tail = this.tail;
            int writeIndex;
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                if (!reserveSpace(availableSharedCapacity, LINK_CAPACITY)) {    //判断剩余空间是否足够
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = new Link();

                writeIndex = tail.get();        // tail这是一个自增的变量,每次tail.get()就表示放到末尾了
            }
            tail.elements[writeIndex] = handle;  // 把对应的handle引用放到末尾的数组里
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            tail.lazySet(writeIndex + 1);   // todo 这个方法JDK注释比较少,还没看懂.后面可以写个demo测试下.
        }

        // readIndex指向当前读取的, tail.get()表示最大的值, 不相等代表还有待读取的数据.
        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        // 把WeakOrderQueue里面暂存的对象,传输到对应的stack,主动去回收对象.
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            Link head = this.head;
            if (head == null) {
                return false;
            }

            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                this.head = head = head.next;
            }

            // 读取头指针的读索引
            final int srcStart = head.readIndex;
            // 读取Link的最后索引
            int srcEnd = head.get();
            // 计算出来该Link数据的大小
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            // 获取Stack目前的容量
            final int dstSize = dst.size;
            // 计算Stack预期需要的容量
            final int expectedCapacity = dstSize + srcSize;

            // 比较预期的容量是否超出 Stack 的总容量
            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle element = srcElems[i];
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null;

                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;
                    dstElems[newDstSize ++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    reclaimSpace(LINK_CAPACITY);

                    this.head = head.next;
                }

                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                super.finalize();
            } finally {
                // We need to reclaim all space that was reserved by this WeakOrderQueue so we not run out of space in
                // the stack. This is needed as we not have a good life-time control over the queue as it is used in a
                // WeakHashMap which will drop it at any time.
                Link link = head;
                while (link != null) {
                    reclaimSpace(LINK_CAPACITY);
                    link = link.next;
                }
            }
        }
    }

    // Stack具体维护这对象的数据
    // 向 Recycler 提供 push 和 pop 两个主要的访问接口
    // pop 用于从内部弹出一个可被重复使用的对象
    // push 用于回收以后可以重复使用的对象
    static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent;

        // 持有线程的强引用, 如果Stack没有被回收,那么Thread也不能被回收了,但是Stack没有强引用,在map中是弱引用,前面提到的.关于引用的知识回头再细化下.
        final Thread thread;

        //容量,用一个AtomicInteger表示,是为了可以并发CAS修改;
        final AtomicInteger availableSharedCapacity;
        final int maxDelayedQueues;

        private final int maxCapacity;
        private final int ratioMask;
        // DefaultHandle数组，pop()返回值、push()参数
        private DefaultHandle<?>[] elements;
        private int size;
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.

        // 指向当前的WeakOrderQueue 和 前一个
        private WeakOrderQueue cursor, prev;

        // Stack 维护这一个 WeakOrderQueue 的头指针，还是 volatile 修饰
        private volatile WeakOrderQueue head;

        // 构造函数
        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int ratioMask, int maxDelayedQueues) {
            this.parent = parent;
            this.thread = thread;
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            // 初始化elements，INITIAL_CAPACITY 是每个线程的初始容量，maxCapacity是设置的最大容量
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.ratioMask = ratioMask;
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        // 标记为同步,保证两个操作顺序执行
        // 重要的一点就是这个方法是 synchronized 的, 这个类里面唯一的synchronized方法
        // synchronized避免并发修改queue.setNext的情况.
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        // 根据期望的容量expectedCapacity，查看Stack目前的容量，然后计算并扩容
        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            if (size == 0) {
                // pop方法首先从该Stack中查看是否有可用对象 size > 0
                // 如果 size == 0，代表没有可用对象，则需要从WeakOrderQueue仓库中取
                // scavenge()方法就是从仓库中取
                if (!scavenge()) {
                    return null;
                }
                size = this.size;
            }
            size --;
            DefaultHandle ret = elements[size];
            elements[size] = null;
            if (ret.lastRecycledId != ret.recycleId) {  // 这两个应该相等
                throw new IllegalStateException("recycled multiple times");
            }
            ret.recycleId = 0;          // 获取出的对象,置为0表示没有被回收
            ret.lastRecycledId = 0;     // 获取出的对象,置为0表示没有被回收
            this.size = size;
            return ret;
        }

        boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                prev = null;
                cursor = head;
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                WeakOrderQueue next = cursor.next;
                if (cursor.owner.get() == null) {       // 线程被回收了
                    // 这里检查WeakOrderQueue的owner(thread)是否为空
                    // cursor.owner.get() == null表示,WeakOrderQueue的归属线程被回收了.
                    // 这里读取的是线程安全的变量,确认没有数据可回收了
                    // 第一个queue永远不回收,因为更新head指针会存在并发.我们不想去在head指针上做并发处理
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    if (cursor.hasFinalData()) {
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;   // cursor.transfer(this)返回false,代表没有读取的数据了
                            }
                        }
                    }

                    if (prev != null) {
                        prev.setNext(next); // 这是一个单向链表,只要改变prev的引用,老的节点会被回收的.
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            if (thread == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                // 当前线程与Stack线程一致
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack, we need to signal that the push
                // happens later.
                // 当前线程与Stack线程不一致
                pushLater(item, currentThread);
            }
        }

        private void pushNow(DefaultHandle<?> item) {
            // 如果没回收,recycleId和lastRecycledId应该都是0; 正常应该不会进来, 感觉应该是作者为了在开发中排除这种情况.
            // item.recycleid | item.lastRecycleid == 0 代表两个都是0，两个都是0，代表没有回收
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }

            // 都更新为OWN_THREAD_ID,表示被回收过了
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            // size 是当前elements的大小
            // 如果 size >= maxCapacity（设置的最大容量），则尝试调用dropHandle(item)
            // dropHandle()返回true,就直接return掉,本次不做回收
            int size = this.size;
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }

            // 如果当前elements容器已满，则扩容
            // 扩容后的大小是取（当前容量 * 2 ，设置的最大容量）之间的最小值
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            // 如果当前elements容器没满，则添加一个元素
            // 并把size + 1
            elements[size] = item;
            this.size = size + 1;
        }

        // 想想为什么需要pushLater?
        // 为了在回收的过程中没有并发,如果回收的不是当前线程的Stack的对象,
        // 就放入到它的WeakOrderQueue,等它自己拿的时候回收,这样recycle方法就没有并发了;这种思想在Doug lea的AQS里也有.
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            // 获取当前线程对应的Map<Stack<?>, WeakOrderQueue>
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            // 根据this Stack获取 WeakOrderQueue
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {
                // 如果queue == null，就需要创建一个
                // 大于上限,就放入一个DUMMY,表示满了
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                // WeakOrderQueue.allocate方法,针对需要回收的这个Stack,创建一个新的WeakOrderQueue
                // 如果WeakOrderQueue.allocate返回null，代表WeakOrderQueue共享的Link大小满了，返回null
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
                    // drop object
                    return;
                }
                // 否则在本线程中放入刚创建的WeakOrderQueue
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            // 把回收对象放入上面刚创建的WeakOrderQueue
            queue.add(item);
        }

        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {      // 判断是否已经回收
                // handleRecycleCount初始为-1, ++handleRecycleCount = 0, 所以第一次肯定会进去.位运算的性能很好.
                if ((++handleRecycleCount & ratioMask) != 0) {
                    // Drop the object.
                    return true;
                }
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
