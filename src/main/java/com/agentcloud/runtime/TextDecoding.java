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
            return utf8;
        }

        for (Charset candidate : fallbackCharsets(bytes)) {
            if (candidate == null) {
                continue;
            }
            try {
                return new String(bytes, candidate);
            } catch (Exception ignored) {
                // 继续尝试下一个候选。
            }
        }

        return new String(bytes, Charset.defaultCharset());
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
