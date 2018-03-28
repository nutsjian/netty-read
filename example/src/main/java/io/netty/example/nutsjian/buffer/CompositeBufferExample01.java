package io.netty.example.nutsjian.buffer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;

public class CompositeBufferExample01 {

    public static void main(String[] args) {
        byte[] bytes = new byte[]{1,2,3,4};
//        CompositeByteBuf buf = Unpooled.compositeBuffer();
//        buf.addComponent()
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        Unpooled.wrappedBuffer(bytes, bytes);
    }

}
