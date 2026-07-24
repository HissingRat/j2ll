package xyz.melodysky.toolchain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces generated-C string literals with writable encoded byte arrays.
 *
 * <p>The strings are decoded once before native registration. This is an
 * obfuscation boundary, not cryptographic secret storage: its purpose is to
 * keep JVM owner/member metadata out of static binary string views while JNI
 * still receives the original modified-UTF-8-compatible bytes at runtime.
 */
public final class CMetadataStringObfuscator {
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final String REGISTER_HEADER = "JNIEXPORT jint JNICALL j2ll_register(JavaVM* vm) {\n";
    private static final String HEX_DIGITS = "0123456789abcdef";

    public String obfuscate(String source) {
        Matcher matcher = STRING_LITERAL.matcher(source);
        StringBuffer transformed = new StringBuffer(source.length());
        Map<String, EncodedString> strings = new LinkedHashMap<>();
        while (matcher.find()) {
            String token = matcher.group();
            String value = decodeLiteral(token.substring(1, token.length() - 1));
            if (value.isEmpty() || HEX_DIGITS.equals(value) || isInsideComment(source, matcher.start())) {
                matcher.appendReplacement(transformed, Matcher.quoteReplacement(token));
                continue;
            }
            EncodedString encoded = strings.computeIfAbsent(value, this::encode);
            matcher.appendReplacement(
                    transformed,
                    Matcher.quoteReplacement("(char*)" + encoded.symbol()));
        }
        matcher.appendTail(transformed);
        if (strings.isEmpty()) {
            return source;
        }

        String withDecoder = insertDecoder(transformed.toString(), List.copyOf(strings.values()));
        if (!withDecoder.contains(REGISTER_HEADER)) {
            throw new IllegalArgumentException("generated JNI C has no j2ll_register entry point");
        }
        return withDecoder.replace(
                REGISTER_HEADER,
                REGISTER_HEADER + "    j2ll_decode_metadata_strings();\n");
    }

    private String insertDecoder(String source, List<EncodedString> strings) {
        int insertion = source.indexOf("\n\n");
        if (insertion < 0) {
            throw new IllegalArgumentException("generated JNI C has no include preamble boundary");
        }
        insertion += 2;
        StringBuilder decoder = new StringBuilder();
        for (EncodedString string : strings) {
            decoder.append("static unsigned char ")
                    .append(string.symbol())
                    .append("[] = {");
            appendBytes(decoder, string.bytes());
            decoder.append("\n};\n");
        }
        decoder.append("""
                typedef struct {
                    unsigned char* data;
                    size_t length;
                    uint64_t key;
                } j2ll_encoded_metadata_string;

                static j2ll_encoded_metadata_string j2ll_encoded_metadata_strings[] = {
                """);
        for (EncodedString string : strings) {
            decoder.append("    { ")
                    .append(string.symbol())
                    .append(", sizeof(")
                    .append(string.symbol())
                    .append("), 0x")
                    .append(String.format(java.util.Locale.ROOT, "%016x", string.key()))
                    .append("ULL },\n");
        }
        decoder.append("""
                };
                static int j2ll_metadata_strings_decoded = 0;

                static unsigned char j2ll_metadata_string_stream(uint64_t key, size_t index) {
                    uint64_t value = key + 0x9e3779b97f4a7c15ULL * (uint64_t)(index + 1u);
                    value = (value ^ (value >> 30u)) * 0xbf58476d1ce4e5b9ULL;
                    value = (value ^ (value >> 27u)) * 0x94d049bb133111ebULL;
                    value ^= value >> 31u;
                    return (unsigned char)(value >> 56u);
                }

                static void j2ll_decode_metadata_strings(void) {
                    if (j2ll_metadata_strings_decoded) {
                        return;
                    }
                    for (size_t string_index = 0;
                            string_index < sizeof(j2ll_encoded_metadata_strings) / sizeof(j2ll_encoded_metadata_strings[0]);
                            string_index++) {
                        j2ll_encoded_metadata_string* string = &j2ll_encoded_metadata_strings[string_index];
                        for (size_t byte_index = 0; byte_index < string->length; byte_index++) {
                            string->data[byte_index] ^= j2ll_metadata_string_stream(string->key, byte_index);
                        }
                    }
                    j2ll_metadata_strings_decoded = 1;
                }

                """);
        return source.substring(0, insertion) + decoder + source.substring(insertion);
    }

    private EncodedString encode(String value) {
        byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
        byte[] terminated = java.util.Arrays.copyOf(plaintext, plaintext.length + 1);
        byte[] digest = sha256(("j2ll-native-metadata-v1\u0000" + value).getBytes(StandardCharsets.UTF_8));
        long key = ByteBuffer.wrap(digest).getLong();
        byte[] encoded = new byte[terminated.length];
        for (int index = 0; index < terminated.length; index++) {
            encoded[index] = (byte) (terminated[index] ^ streamByte(key, index));
        }
        String symbol = "j2ll_ms_" + HexFormat.of().formatHex(digest, 8, 20);
        return new EncodedString(symbol, key, encoded);
    }

    private int streamByte(long key, int index) {
        long value = key + 0x9e3779b97f4a7c15L * (index + 1L);
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (int) ((value >>> 56) & 0xffL);
    }

    private void appendBytes(StringBuilder builder, byte[] bytes) {
        for (int index = 0; index < bytes.length; index++) {
            if (index % 12 == 0) {
                builder.append("\n    ");
            }
            builder.append(String.format(
                    java.util.Locale.ROOT,
                    "0x%02x, ",
                    bytes[index] & 0xff));
        }
    }

    private String decodeLiteral(String contents) {
        StringBuilder decoded = new StringBuilder(contents.length());
        for (int index = 0; index < contents.length(); index++) {
            char ch = contents.charAt(index);
            if (ch != '\\') {
                decoded.append(ch);
                continue;
            }
            if (++index >= contents.length()) {
                throw new IllegalArgumentException("unterminated C string escape");
            }
            char escaped = contents.charAt(index);
            switch (escaped) {
                case '\\', '"', '\'', '?' -> decoded.append(escaped);
                case 'a' -> decoded.append('\u0007');
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'v' -> decoded.append('\u000b');
                default -> {
                    if (escaped < '0' || escaped > '7') {
                        throw new IllegalArgumentException("unsupported generated C string escape: \\" + escaped);
                    }
                    int value = escaped - '0';
                    int digits = 1;
                    while (digits < 3
                            && index + 1 < contents.length()
                            && contents.charAt(index + 1) >= '0'
                            && contents.charAt(index + 1) <= '7') {
                        value = (value << 3) + (contents.charAt(++index) - '0');
                        digits++;
                    }
                    decoded.append((char) value);
                }
            }
        }
        return decoded.toString();
    }

    private boolean isInsideComment(String source, int position) {
        int lineStart = source.lastIndexOf('\n', position - 1) + 1;
        int lineComment = source.indexOf("//", lineStart);
        if (lineComment >= 0 && lineComment < position) {
            return true;
        }
        int blockStart = source.lastIndexOf("/*", position);
        int blockEnd = source.lastIndexOf("*/", position);
        return blockStart > blockEnd;
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record EncodedString(String symbol, long key, byte[] bytes) {
        private EncodedString {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
