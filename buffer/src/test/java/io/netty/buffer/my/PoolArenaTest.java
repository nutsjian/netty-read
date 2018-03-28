package io.netty.buffer.my;

import org.junit.Test;

public class PoolArenaTest {

    /**
     * 规范化内存容量测试
     * > 13
     * 0000 0000 0000 0000 0000 0000 0000 1101
     * 0000 0000 0000 0000 0000 0000 0000 1100      normalizedCapacity --
     * 0000 0000 0000 0000 0000 0000 0000 0110      normalizedCapacity >>> 1
     * 0000 0000 0000 0000 0000 0000 0000 1110      |=
     * 0000 0000 0000 0000 0000 0000 0000 0011      normalizedCapacity >>> 2
     *
     *
     */
    @Test
    public void testNormalizeCapacity() {
        int normalizedCapacity = 512;
        normalizedCapacity --;
        normalizedCapacity |= normalizedCapacity >>>  1;
        normalizedCapacity |= normalizedCapacity >>>  2;
        normalizedCapacity |= normalizedCapacity >>>  4;
        normalizedCapacity |= normalizedCapacity >>>  8;
        normalizedCapacity |= normalizedCapacity >>> 16;
        normalizedCapacity ++;

        if (normalizedCapacity < 0) {
            normalizedCapacity >>>= 1;
        }

        System.out.println(normalizedCapacity);
    }

}
