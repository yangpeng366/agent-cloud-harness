package com.agentcloud.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextDecodingTest {

    @Test
    void prefersUtf8WhenBytesAreValidUtf8() {
        String original = "错误: 没有找到线程 \"15252\"";
        byte[] bytes = original.getBytes(StandardCharsets.UTF_8);

        assertEquals(original, TextDecoding.decodeExternalProcessOutput(bytes));
    }

    @Test
    void fallsBackToWindowsCompatibleCharsetForGbkBytes() throws Exception {
        String original = "错误: 没有找到线程 \"15252\"";
        byte[] bytes = original.getBytes("GBK");

        assertEquals(original, TextDecoding.decodeExternalProcessOutput(bytes));
    }

    @Test
    void stripsUtf8BomWhenPresent() {
        String original = "中文输出";
        byte[] utf8 = original.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[utf8.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, withBom, 3, utf8.length);

        assertEquals(original, TextDecoding.decodeExternalProcessOutput(withBom));
    }
}
