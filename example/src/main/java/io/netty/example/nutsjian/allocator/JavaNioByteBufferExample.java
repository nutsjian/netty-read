package io.netty.example.nutsjian.allocator;

import java.nio.ByteBuffer;

public class JavaNioByteBufferExample {

    public static void main(String[] args) {
        ByteBuffer nioByteBuf = java.nio.ByteBuffer.allocateDirect(12);
        nioByteBuf.putInt(2);

    }

}
