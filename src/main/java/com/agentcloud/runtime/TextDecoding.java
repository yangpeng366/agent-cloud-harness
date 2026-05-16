package com.agentcloud.runtime;

import org.mozilla.universalchardet.UniversalDetector;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 外部进程输出的文本解码辅助。
 * 仓库内部文件/HTTP 继续严格使用 UTF-8；只有宿主机外部进程输出走自适应兼容。
 */
public final class TextDecoding {
    private static final Charset GB18030 = charsetOrNull("GB18030");
    private static final Charset GBK = charsetOrNull("GBK");

    private TextDecoding() {
    }

    public static String decodeExternalProcessOutput(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        Charset bomCharset = bomCharset(bytes);
        if (bomCharset != null) {
            return stripBom(new String(bytes, bomCharset));
        }

        String utf8 = decodeUtf8Strict(bytes);
        if (utf8 != null) {
            // 如果 UTF-8 解码成功但结果看起来像乱码（包含大量替换字符），
            // 继续尝试 fallback 字符集，因为可能是 GBK 等编码被错误解码
            if (!looksLikeGarbage(utf8)) {
                return utf8;
            }
        }

        for (Charset candidate : fallbackCharsets(bytes)) {
            if (candidate == null) {
                continue;
            }
            try {
                String result = new String(bytes, candidate);
                // 如果解码结果不像乱码，使用它
                if (!looksLikeGarbage(result)) {
                    return result;
                }
            } catch (Exception ignored) {
                // 继续尝试下一个候选。
            }
        }

        // 如果所有 fallback 都失败或结果都是乱码，返回原始 UTF-8 结果（即使可能是乱码）
        return utf8 != null ? utf8 : new String(bytes, Charset.defaultCharset());
    }

    /**
     * 检测字符串是否看起来像乱码。
     * 如果字符串包含大量的 Unicode 替换字符 U+FFFD 或其他异常字符模式，
     * 则认为是乱码。
     */
    private static boolean looksLikeGarbage(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }

        int replacementCharCount = 0;
        int asciiPrintableCount = 0;

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '\uFFFD') {
                replacementCharCount++;
            } else if (c >= 0x20 && c <= 0x7E) {
                asciiPrintableCount++;
            }
        }

        int length = str.length();
        
        // 如果替换字符占比超过 10%，认为是乱码
        if (length > 0 && replacementCharCount > length * 0.1) {
            return true;
        }

        // 如果大部分字符都不是可打印 ASCII（且字符串较长），可能是乱码
        // 对于中文字符串，可打印 ASCII 比例通常不会特别低，但如果是乱码，
        // 可能包含大量非 ASCII 控制字符或奇怪的 Unicode 字符
        if (length > 10 && asciiPrintableCount < length * 0.2) {
            // 检查是否包含典型的 GBK 乱码模式
            if (containsGbkGarbagePattern(str)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查字符串是否包含典型的 GBK 乱码模式。
     * GBK 双字节字符被当作 UTF-8 解码时会产生特定的乱码模式。
     */
    private static boolean containsGbkGarbagePattern(String str) {
        // 典型的 GBK 乱码模式：包含多个连续的 Latin-1 补充字符或其他异常字符
        // 例如："û" (U+00FB), "�" (U+FFFD), 以及各种组合字符

        int weirdCharCount = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            // 检查是否在 Latin-1 补充区域（通常是 GBK 乱码的特征）
            if ((c >= 0x80 && c <= 0xFF) || 
                (c >= 0x100 && c <= 0x17F) ||  // Latin Extended-A
                (c >= 0x180 && c <= 0x24F)) {   // Latin Extended-B
                weirdCharCount++;
            }
        }

        return weirdCharCount > str.length() * 0.3;
    }

    private static String decodeUtf8Strict(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static List<Charset> fallbackCharsets(byte[] bytes) {
        LinkedHashSet<Charset> ordered = new LinkedHashSet<>();

        Charset detected = detectedCharset(bytes);
        if (detected != null && !StandardCharsets.UTF_8.equals(detected)) {
            ordered.add(detected);
        }

        Charset defaultCharset = Charset.defaultCharset();
        if (!StandardCharsets.UTF_8.equals(defaultCharset)) {
            ordered.add(defaultCharset);
        }

        if (isWindows()) {
            if (GB18030 != null) {
                ordered.add(GB18030);
            }
            if (GBK != null) {
                ordered.add(GBK);
            }
        }

        ArrayList<Charset> result = new ArrayList<>(ordered);
        if (result.isEmpty()) {
            result.add(Charset.defaultCharset());
        }
        return result;
    }

    private static Charset detectedCharset(byte[] bytes) {
        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(bytes, 0, bytes.length);
        detector.dataEnd();
        String detected = detector.getDetectedCharset();
        detector.reset();
        if (detected == null || detected.isBlank()) {
            return null;
        }
        return charsetOrNull(detected);
    }

    private static Charset bomCharset(byte[] bytes) {
        if (bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (bytes.length >= 2
            && (bytes[0] & 0xFF) == 0xFF
            && (bytes[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (bytes.length >= 2
            && (bytes[0] & 0xFF) == 0xFE
            && (bytes[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    private static String stripBom(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }

    private static Charset charsetOrNull(String name) {
        try {
            return name == null || name.isBlank() ? null : Charset.forName(name);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");
    }
}
