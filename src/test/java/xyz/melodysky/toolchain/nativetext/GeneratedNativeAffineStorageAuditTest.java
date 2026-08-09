package xyz.melodysky.toolchain.nativetext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GeneratedNativeAffineStorageAuditTest {
    private final NativeTextCEmitter emitter = new NativeTextCEmitter();
    private final GeneratedNativeAffineStorageAudit audit =
            new GeneratedNativeAffineStorageAudit();

    @Test
    void indexesMultipleCiphersAndReportsRealCompletedCount() {
        NativeTextEncoding first = encoding(
                "multi-cipher-first",
                "first native text");
        NativeTextEncoding second = encoding(
                "multi-cipher-second",
                "second native text");
        String firstCipher = first.symbol() + "_cipher";
        String secondCipher = second.symbol() + "_cipher";
        String source = validSource(first, second)
                + "// ignored alias = " + firstCipher + ";\n"
                + "static const char* ignored_text = \""
                + secondCipher
                + "\";\n";
        ArrayList<Integer> completed = new ArrayList<>();

        assertEquals(2, audit.cipherCount(source));
        GeneratedNativeAffineStorageAudit.Inspection inspection =
                audit.inspect(source, completed::add);

        assertNull(inspection.finding());
        assertTrue(inspection.evidence());
        assertEquals(List.of(1, 2), completed);
    }

    @Test
    void rejectsAliasForOneCipherWithoutMisclassifyingTheOthers() {
        NativeTextEncoding first = encoding(
                "multi-alias-first",
                "first protected value");
        NativeTextEncoding second = encoding(
                "multi-alias-second",
                "second protected value");
        String source = validSource(first, second)
                + "static const unsigned char* escaped = "
                + second.symbol()
                + "_cipher;\n";
        ArrayList<Integer> completed = new ArrayList<>();

        GeneratedNativeAffineStorageAudit.Inspection inspection =
                audit.inspect(source, completed::add);

        assertFalse(inspection.evidence());
        assertEquals(
                GeneratedNativeAffineStorageAudit
                        .INVALID_AFFINE_CIPHERTEXT_STORAGE,
                inspection.finding().code());
        assertEquals(
                "native-text ciphertext has an unclassified direct or aliased reference",
                inspection.finding().detail());
        assertEquals(List.of(1, 2), completed);
    }

    private String validSource(NativeTextEncoding... encodings) {
        StringBuilder source = new StringBuilder(emitter.runtimeSource());
        for (NativeTextEncoding encoding : encodings) {
            source.append(emitter.ciphertextDeclaration(encoding));
        }
        for (int index = 0; index < encodings.length; index++) {
            NativeTextEncoding encoding = encodings[index];
            source.append("static void decode_")
                    .append(index)
                    .append("(unsigned char* output) {\n")
                    .append(emitter.decodeInto(encoding, "output", "    "))
                    .append("}\n");
        }
        return source.toString();
    }

    private NativeTextEncoding encoding(String use, String plaintext) {
        return new NativeTextEncoder().encode(
                NativeTextBuildKey.fromUtf8("affine-index-focused-build"),
                NativeTextPurpose.RUNTIME_ERROR,
                use,
                plaintext);
    }
}
