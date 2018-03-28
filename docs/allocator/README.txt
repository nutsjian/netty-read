关于 Unpooled
========================================================================================================================
Unpooled 非池化内存分配
    1. 非池化 堆内内存，实际上是一个 字节数组 byte[]
    2. 非池化 堆外内存，实际上是一个 java.nio.ByteBuffer，内部最终是委托给 java.nio.ByteBuffer 分配的

我们无法直接用 UnpooledByteBufAllocator 来创建 堆内内存 和 堆外内存。我们应该使用 Unpooled 的静态方法来创建，但是我们要
直到，Unpooled 分配内存，实际上最终还是委托给 UnpooledByteBufAllocator 来分配的。

Unpooled 是一个 final 类，里面有很多 final static 方法

public static ByteBuf buffer();
public static ByteBuf directBuffer();
public static ByteBuf buffer(int initialCapacity);
public static ByteBuf directBuffer(int initialCapacity);
public static ByteBuf buffer(int initialCapacity, int maxCapacity);
public static ByteBuf directBuffer(int initialCapacity, int maxCapacity);
上面这几个方法创建的字节 buf，实际是委托给内部的字节分配器 UnpooledByteBufAllocator 实现的

public static ByteBuf wrappedBuffer(byte[] array);
public static ByteBuf wrappedBuffer(byte[] array, int offset, int length);
public static ByteBuf wrappedBuffer(byte[]... arrays);
public static ByteBuf wrappedBuffer(ByteBuf buffer);
public static ByteBuf wrappedBuffer(ByteBuf... buffers);
public static ByteBuf wrappedBuffer(ByteBuffer buffer);
public static ByteBuf wrappedBuffer(ByteBuffer... buffers);
public static ByteBuf wrappedBuffer(long memoryAddress, int size, boolean doFree);
public static ByteBuf wrappedBuffer(int maxNumComponents, byte[]... arrays);
public static ByteBuf wrappedBuffer(int maxNumComponents, ByteBuf... buffers);
public static ByteBuf wrappedBuffer(int maxNumComponents, ByteBuffer... buffers);
上面这些包装方法，都是零拷贝操作，对源 byte[]、ByteBuf、ByteBuffer 的操作，会直接影响到包装后的 ByteBuf 对象，
上面有些方法，当对多个 byte[]、ByteBuf、ByteBuffer 进行包装的时候，底层实际上是创建的 CompositeByteBuf 对象，
所以 CompositeByteBuf 对象就是用来做 零拷贝的，它内部维护了各个 buf 的引用，而不是拷贝。

public static ByteBuf copiedBuffer(byte[] array);
public static ByteBuf copiedBuffer(byte[] array, int offset, int length);
public static ByteBuf copiedBuffer(ByteBuffer buffer);
public static ByteBuf copiedBuffer(ByteBuf buffer);
public static ByteBuf copiedBuffer(byte[]... arrays);
public static ByteBuf copiedBuffer(ByteBuf... buffers);
public static ByteBuf copiedBuffer(ByteBuffer... buffers);
public static ByteBuf copiedBuffer(CharSequence string, Charset charset);
public static ByteBuf copiedBuffer(CharSequence string, int offset, int length, Charset charset);
public static ByteBuf copiedBuffer(char[] array, Charset charset);
public static ByteBuf copiedBuffer(char[] array, int offset, int length, Charset charset);
private static ByteBuf copiedBuffer(CharBuffer buffer, Charset charset);
上面这些方法，

public static ByteBuf unmodifiableBuffer(ByteBuf buffer);

public static ByteBuf unreleasableBuffer(ByteBuf buf);

public static ByteBuf unmodifiableBuffer(ByteBuf... buffers);

