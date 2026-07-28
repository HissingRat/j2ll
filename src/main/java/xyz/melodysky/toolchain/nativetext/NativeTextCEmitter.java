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
        return ciphertextDeclaration(encoding, true);
    }

    public String mutableCiphertextDeclaration(NativeTextEncoding encoding) {
        return ciphertextDeclaration(encoding, false);
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

    public String decodeInPlace(
            NativeTextEncoding encoding,
            String mutableCipher,
            String indent) {
        Objects.requireNonNull(encoding, "encoding");
        requireCIdentifier(mutableCipher);
        requireIndent(indent);
        String token =
                encoding.symbol().substring("j2ll_nt_".length());
        String scratch = "j2ll_nt_in_place_" + token;
        String copyIndex = "j2ll_nt_copy_" + token;
        String inner = indent + "    ";
        String loop = inner + "    ";
        return new StringBuilder(indent)
                .append("{\n")
                .append(inner)
                .append("unsigned char ")
                .append(scratch)
                .append("[sizeof(")
                .append(mutableCipher)
                .append(")];\n")
                .append(codecEmitter.decodeInto(
                        encoding,
                        mutableCipher,
                        "sizeof(" + mutableCipher + ")",
                        scratch,
                        inner))
                .append(inner)
                .append("for (size_t ")
                .append(copyIndex)
                .append(" = 0u; ")
                .append(copyIndex)
                .append(" < sizeof(")
                .append(mutableCipher)
                .append("); ")
                .append(copyIndex)
                .append("++) {\n")
                .append(loop)
                .append("((unsigned char*)(")
                .append(mutableCipher)
                .append("))[")
                .append(copyIndex)
                .append("] = ")
                .append(scratch)
                .append("[")
                .append(copyIndex)
                .append("];\n")
                .append(inner)
                .append("}\n")
                .append(inner)
                .append(NativeScratchZeroizerSource.FUNCTION_NAME)
                .append("(")
                .append(scratch)
                .append(", sizeof(")
                .append(scratch)
                .append("));\n")
                .append(indent)
                .append("}\n")
                .toString();
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

    private String ciphertextDeclaration(
            NativeTextEncoding encoding,
            boolean constant) {
        Objects.requireNonNull(encoding, "encoding");
        StringBuilder source = new StringBuilder("static ");
        if (constant) {
            source.append("const ");
        }
        source.append("unsigned char ")
                .append(cipherSymbol(encoding))
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

    private String cipherSymbol(NativeTextEncoding encoding) {
        return encoding.symbol() + "_cipher";
    }

    private void requireNonAliasingDestination(
            NativeTextEncoding encoding,
            String destination) {
        if (cipherSymbol(encoding).equals(destination)) {
            throw new IllegalArgumentException(
                    "affine native-text decode destination aliases ciphertext; use decodeInPlace");
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
