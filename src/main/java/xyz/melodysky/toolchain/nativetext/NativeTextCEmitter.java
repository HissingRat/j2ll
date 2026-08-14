package xyz.melodysky.toolchain.nativetext;

import java.util.Locale;
import java.util.Objects;

/**
 * Emits independent ciphertext and site-bound decode/cleanup code.
 *
 * <p>There is no generic decoder entry point. Each encoding carries a
 * build-scoped codec family and operation schedule that is emitted directly
 * into the activation that owns the plaintext scratch.</p>
 */
public final class NativeTextCEmitter {
    private final NativeTextCodecCEmitter codecEmitter =
            new NativeTextCodecCEmitter();

    public String runtimeSource() {
        return """
                #ifndef J2LL_NATIVE_TEXT_RUNTIME_DEFINED
                #define J2LL_NATIVE_TEXT_RUNTIME_DEFINED 1
                #define J2LL_NATIVE_TEXT_AFFINE_STORAGE 1

                """
                + new NativeScratchZeroizerSource().emit()
                + """
                #endif

                """;
    }

    public String ciphertextDeclaration(NativeTextEncoding encoding) {
        Objects.requireNonNull(encoding, "encoding");
        StringBuilder source = new StringBuilder("static const unsigned char ");
        source.append(cipherSymbol(encoding))
                .append("[] = {");
        byte[] ciphertext = encoding.ciphertext();
        for (int index = 0; index < ciphertext.length; index++) {
            if (index % 12 == 0) {
                source.append("\n    ");
            }
            source.append(String.format(
                    Locale.ROOT,
                    "0x%02x, ",
                    ciphertext[index] & 0xff));
        }
        return source.append("\n};\n").toString();
    }

    public String scratchDeclarationAndDecode(
            NativeTextEncoding encoding,
            String scratchVariable) {
        requireCValue(scratchVariable);
        return new StringBuilder()
                .append("char ")
                .append(scratchVariable)
                .append("[sizeof(")
                .append(cipherSymbol(encoding))
                .append(")];\n")
                .append(decodeInto(
                        encoding,
                        scratchVariable,
                        ""))
                .toString();
    }

    public String decodeInto(
            NativeTextEncoding encoding,
            String destination,
            String indent) {
        Objects.requireNonNull(encoding, "encoding");
        requireCValue(destination);
        requireNonAliasingDestination(encoding, destination);
        requireIndent(indent);
        return codecEmitter.decodeInto(
                encoding,
                cipherSymbol(encoding),
                "sizeof(" + cipherSymbol(encoding) + ")",
                destination,
                indent);
    }

    String decodeTupleInto(
            NativeTextTupleEncoding tuple,
            String destination,
            String indent) {
        Objects.requireNonNull(tuple, "tuple");
        requireCValue(destination);
        requireNonAliasingDestination(tuple.record(), destination);
        requireIndent(indent);
        return codecEmitter.decodeTupleInto(
                tuple,
                cipherSymbol(tuple.record()),
                "sizeof(" + cipherSymbol(tuple.record()) + ")",
                destination,
                indent);
    }

    public String decodeIntoOffset(
            NativeTextEncoding encoding,
            String byteBuffer,
            int offset,
            String indent) {
        Objects.requireNonNull(encoding, "encoding");
        requireCIdentifier(byteBuffer);
        requireNonAliasingDestination(encoding, byteBuffer);
        if (offset < 0) {
            throw new IllegalArgumentException(
                    "native-text destination offset must not be negative");
        }
        requireIndent(indent);
        return codecEmitter.decodeInto(
                encoding,
                cipherSymbol(encoding),
                "sizeof(" + cipherSymbol(encoding) + ")",
                "(char*)(" + byteBuffer + " + " + offset + "u)",
                indent);
    }

    public String scratchCleanup(
            NativeTextEncoding encoding,
            String scratchVariable) {
        Objects.requireNonNull(encoding, "encoding");
        requireCValue(scratchVariable);
        return NativeScratchZeroizerSource.FUNCTION_NAME
                + "("
                + scratchVariable
                + ", sizeof("
                + scratchVariable
                + "));\n";
    }

    private String cipherSymbol(NativeTextEncoding encoding) {
        return encoding.symbol() + "_cipher";
    }

    private void requireNonAliasingDestination(
            NativeTextEncoding encoding,
            String destination) {
        if (cipherSymbol(encoding).equals(destination)) {
            throw new IllegalArgumentException(
                    "affine native-text decode destination must not alias ciphertext");
        }
    }

    private void requireCValue(String value) {
        Objects.requireNonNull(value, "value");
        if (!value.matches(
                "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
            throw new IllegalArgumentException(
                    "invalid native-text C value: " + value);
        }
    }

    private void requireCIdentifier(String value) {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "invalid native-text C identifier: " + value);
        }
    }

    private void requireIndent(String indent) {
        Objects.requireNonNull(indent, "indent");
        if (!indent.matches("[ ]*")) {
            throw new IllegalArgumentException(
                    "native-text C indentation must contain spaces only");
        }
    }
}
