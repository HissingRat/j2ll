package xyz.melodysky.ir.model;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Canonical view of one business-string constant carried by IR.
 *
 * <p>Equal Java values deliberately share one small native helper group.
 * Helper names are hash-only and never contain plaintext or stable Java
 * metadata.</p>
 */
public record BusinessStringConstantRef(
        String value,
        long token) {
    private static final String PLAIN_HELPER_PREFIX =
            "j2ll_rt_string_constant|string:";
    private static final String ENCRYPTED_HELPER_PREFIX_V1 =
            "j2ll_rt_string_constant|enc:v1:";
    private static final String ENCRYPTED_HELPER_PREFIX_V2 =
            "j2ll_rt_string_constant|enc:v2:";

    public static Optional<BusinessStringConstantRef> fromInstruction(
            IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.CONST_STRING
                && instruction.symbol().isPresent()) {
            String symbol = instruction.symbol().orElseThrow();
            String value = symbol.startsWith("string:")
                    ? symbol.substring("string:".length())
                    : symbol;
            return Optional.of(of(value));
        }
        if (instruction.opcode() != IrOpcode.CALL_RUNTIME_HELPER
                || instruction.symbol().isEmpty()) {
            return Optional.empty();
        }
        String symbol = instruction.symbol().orElseThrow();
        if (symbol.startsWith(PLAIN_HELPER_PREFIX)) {
            return Optional.of(of(symbol.substring(PLAIN_HELPER_PREFIX.length())));
        }
        if (!symbol.startsWith(ENCRYPTED_HELPER_PREFIX_V1)
                && !symbol.startsWith(ENCRYPTED_HELPER_PREFIX_V2)) {
            return Optional.empty();
        }
        EncryptedCarrier carrier = parseEncryptedCarrier(symbol);
        byte[] key = carrier.key();
        byte[] ciphertext = carrier.ciphertext();
        byte[] plaintext = new byte[ciphertext.length];
        for (int index = 0; index < plaintext.length; index++) {
            plaintext[index] = (byte) (
                    ciphertext[index] ^ key[index % key.length]);
        }
        String value = strictUtf8(plaintext);
        BusinessStringConstantRef result = of(value);
        long expectedToken = carrier.keyBoundToken()
                ? encryptedCarrierToken(value, key)
                : result.token();
        if (carrier.token() != expectedToken) {
            throw new IllegalArgumentException(
                    "encrypted business string carrier token does not match its plaintext");
        }
        return Optional.of(result);
    }

    public static BusinessStringConstantRef of(String value) {
        return new BusinessStringConstantRef(value, integrityToken(value));
    }

    /**
     * Carrier corruption check only. Final runtime helper identity is always
     * derived by the invocation-scoped {@link BusinessStringSymbolMapper}.
     */
    public static long integrityToken(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(
                    "j2ll-business-string-carrier-integrity-v2"
                            .getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            long token = ByteBuffer.wrap(digest.digest(), 0, Long.BYTES)
                    .getLong();
            return token == Long.MIN_VALUE ? Long.MAX_VALUE : token;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Build-scoped carrier integrity token bound to the encrypted payload key. */
    public static long encryptedCarrierToken(String value, byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException(
                    "encrypted business string carrier key must not be empty");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(
                    "j2ll-business-string-carrier-key-bound-v2"
                            .getBytes(StandardCharsets.US_ASCII));
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(key.length)
                    .array());
            digest.update(key);
            byte[] encodedValue = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(encodedValue.length)
                    .array());
            digest.update(encodedValue);
            long token = ByteBuffer.wrap(digest.digest(), 0, Long.BYTES)
                    .getLong();
            return token == Long.MIN_VALUE ? Long.MAX_VALUE : token;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String helperSymbol(BusinessStringSymbolMapper mapper) {
        return mapper.symbolFor(this);
    }

    /** Fixed-key compatibility mapping for focused fixtures. */
    public String helperSymbol() {
        return helperSymbol(BusinessStringSymbolMapper.compatibility());
    }

    /**
     * JNI {@code NewStringUTF} consumes modified UTF-8, not standard UTF-8.
     */
    public byte[] modifiedUtf8Bytes() {
        ByteArrayOutputStream output = new ByteArrayOutputStream(value.length() * 3);
        for (int index = 0; index < value.length(); index++) {
            int character = value.charAt(index);
            if (character >= 0x0001 && character <= 0x007f) {
                output.write(character);
            } else if (character <= 0x07ff) {
                output.write(0xc0 | ((character >> 6) & 0x1f));
                output.write(0x80 | (character & 0x3f));
            } else {
                output.write(0xe0 | ((character >> 12) & 0x0f));
                output.write(0x80 | ((character >> 6) & 0x3f));
                output.write(0x80 | (character & 0x3f));
            }
        }
        return output.toByteArray();
    }

    private static EncryptedCarrier parseEncryptedCarrier(String symbol) {
        boolean keyBoundToken = symbol.startsWith(
                ENCRYPTED_HELPER_PREFIX_V2);
        String[] parts = symbol.split(":", 5);
        if (parts.length != 5) {
            throw new IllegalArgumentException(
                    "invalid encrypted business string carrier");
        }
        try {
            long token = Long.parseLong(parts[2]);
            byte[] key = HexFormat.of().parseHex(parts[3]);
            byte[] ciphertext = HexFormat.of().parseHex(parts[4]);
            if (key.length == 0) {
                throw new IllegalArgumentException(
                        "encrypted business string carrier has an empty key");
            }
            return new EncryptedCarrier(
                    token,
                    key,
                    ciphertext,
                    keyBoundToken);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "invalid encrypted business string carrier",
                    exception);
        }
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                    "encrypted business string carrier is not valid UTF-8",
                    exception);
        }
    }

    private record EncryptedCarrier(
            long token,
            byte[] key,
            byte[] ciphertext,
            boolean keyBoundToken) {
        private EncryptedCarrier {
            key = key.clone();
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] key() {
            return key.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }
}
