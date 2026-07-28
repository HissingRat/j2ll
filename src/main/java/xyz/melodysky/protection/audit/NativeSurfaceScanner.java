package xyz.melodysky.protection.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import xyz.melodysky.toolchain.nativetext.NativeTextSourceMetrics;
import xyz.melodysky.toolchain.nativetext.NativeTextSourceScanner;

final class NativeSurfaceScanner {
    private static final byte[] CLASS_MAGIC =
            {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe};
    private static final List<String> FALLBACK_MARKERS = List.of(
            "nativeEmbeddedClassBlob",
            "defineHiddenFallback",
            "j2ll_fallback_blob",
            "j2ll_fallback_class",
            "fallback_blob");
    private static final String LEGACY_METADATA_DIRECTORY =
            "j2ll_encoded_metadata_strings";
    private static final String LEGACY_DECODE_ALL =
            "j2ll_decode_metadata_strings";
    private static final int MIN_PRINTABLE_RUN = 4;

    NativeSurfaceMetrics scan(
            byte[] nativeBytes,
            String generatedC,
            List<String> sensitivePlaintexts) {
        TreeSet<String> sensitive = new TreeSet<>();
        sensitivePlaintexts.stream()
                .filter(value -> value != null && !value.isEmpty())
                .forEach(sensitive::add);
        ArrayList<SensitivePlaintextMetric> sensitiveMetrics =
                new ArrayList<>();
        for (String plaintext : sensitive) {
            sensitiveMetrics.add(new SensitivePlaintextMetric(
                    sha256(plaintext),
                    occurrences(
                            nativeBytes,
                            plaintext.getBytes(StandardCharsets.UTF_8)),
                    occurrences(
                            nativeBytes,
                            plaintext.getBytes(StandardCharsets.UTF_16LE)),
                    occurrences(generatedC, plaintext)));
        }

        int fallbackOccurrences = 0;
        for (String marker : FALLBACK_MARKERS) {
            fallbackOccurrences += occurrences(
                    nativeBytes,
                    marker.getBytes(StandardCharsets.US_ASCII));
        }
        NativeTextSourceMetrics nativeText =
                new NativeTextSourceScanner().scan(generatedC);
        return new NativeSurfaceMetrics(
                fallbackOccurrences,
                plausibleClassfileHeaders(nativeBytes),
                occurrences(
                        nativeBytes,
                        LEGACY_METADATA_DIRECTORY.getBytes(StandardCharsets.US_ASCII)),
                occurrences(
                        nativeBytes,
                        LEGACY_DECODE_ALL.getBytes(StandardCharsets.US_ASCII)),
                printableRuns(nativeBytes),
                cStringLiterals(generatedC),
                nativeText.cipherArrayCount(),
                nativeText.siteBoundCodecCount(),
                nativeText.codecFamilyCount(),
                nativeText.decoderFunctionCount(),
                nativeText.largestDecoderCipherFanout(),
                nativeText.fixedDecoderShapeOccurrences(),
                nativeText.adjacentSeedCipherOccurrences(),
                sensitiveMetrics);
    }

    private int printableRuns(byte[] bytes) {
        int count = 0;
        int run = 0;
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            if (unsigned >= 0x20 && unsigned <= 0x7e) {
                run++;
            } else {
                if (run >= MIN_PRINTABLE_RUN) {
                    count++;
                }
                run = 0;
            }
        }
        return run >= MIN_PRINTABLE_RUN ? count + 1 : count;
    }

    private int cStringLiterals(String source) {
        int count = 0;
        Mode mode = Mode.CODE;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length()
                    ? source.charAt(index + 1)
                    : '\0';
            switch (mode) {
                case CODE -> {
                    if (current == '/' && next == '/') {
                        mode = Mode.LINE_COMMENT;
                        index++;
                    } else if (current == '/' && next == '*') {
                        mode = Mode.BLOCK_COMMENT;
                        index++;
                    } else if (current == '"') {
                        count++;
                        mode = Mode.STRING;
                    } else if (current == '\'') {
                        mode = Mode.CHARACTER;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n') {
                        mode = Mode.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        mode = Mode.CODE;
                        index++;
                    }
                }
                case STRING -> {
                    if (current == '\\') {
                        index++;
                    } else if (current == '"') {
                        mode = Mode.CODE;
                    }
                }
                case CHARACTER -> {
                    if (current == '\\') {
                        index++;
                    } else if (current == '\'') {
                        mode = Mode.CODE;
                    }
                }
            }
        }
        return count;
    }

    private int plausibleClassfileHeaders(byte[] bytes) {
        int count = 0;
        for (int start = 0; start <= bytes.length - 10; start++) {
            if (!matches(bytes, start, CLASS_MAGIC)) {
                continue;
            }
            int minor = unsigned16BigEndian(bytes, start + 4);
            int major = unsigned16BigEndian(bytes, start + 6);
            int constantPoolCount = unsigned16BigEndian(bytes, start + 8);
            if ((minor <= 3 || minor == 0xffff)
                    && major >= 45
                    && major <= 100
                    && constantPoolCount > 0) {
                count++;
            }
        }
        return count;
    }

    private boolean matches(byte[] bytes, int start, byte[] needle) {
        for (int index = 0; index < needle.length; index++) {
            if (bytes[start + index] != needle[index]) {
                return false;
            }
        }
        return true;
    }

    private int unsigned16BigEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 8
                | (bytes[offset + 1] & 0xff);
    }

    private int occurrences(String text, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private int occurrences(byte[] bytes, byte[] needle) {
        if (needle.length == 0) {
            return 0;
        }
        int count = 0;
        for (int start = 0; start <= bytes.length - needle.length;) {
            int index = 0;
            while (index < needle.length
                    && bytes[start + index] == needle[index]) {
                index++;
            }
            if (index == needle.length) {
                count++;
                start += needle.length;
            } else {
                start++;
            }
        }
        return count;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private enum Mode {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHARACTER
    }
}
