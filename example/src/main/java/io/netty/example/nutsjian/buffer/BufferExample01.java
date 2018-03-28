package io.netty.example.nutsjian.buffer;

import io.netty.buffer.Unpooled;

public class BufferExample01 {

    public static void main(String[] args) {
        // UnpooledByteBufAllocator.heapBuffer();
        // 返回的结果类型是：UnpooledHeapByteBuf extends AbstractReferenceCountedByteBuf
        Unpooled.buffer();
    }

}
