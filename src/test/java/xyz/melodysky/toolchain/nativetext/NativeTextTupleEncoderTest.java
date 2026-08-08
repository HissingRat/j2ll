package xyz.melodysky.toolchain.nativetext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeTextTupleEncoderTest {
    @Test
    void componentsHaveExactSlicesAndIndependentNulTerminators() {
        List<String> components = List.of(
                "owner/秘密",
                "",
                "left\0ignored",
                "(Ljava/lang/String;)V");
        NativeTextTupleEncoder encoder = new NativeTextTupleEncoder();
        NativeTextTupleEncoding tuple = encoder.encode(
                NativeTextBuildKey.fromUtf8("tuple-build"),
                NativeTextPurpose.GENERATED_C_FRAGMENT,
                "binding:tuple",
                components);
        byte[] aggregateOnly = new NativeTextEncoder()
                .decodeBytes(tuple.record());
        byte[] decoded = encoder.decodeBytes(tuple);

        assertFalse(Arrays.equals(aggregateOnly, decoded));

        assertEquals(components.size(), tuple.componentCount());
        for (int index = 0; index < components.size(); index++) {
            NativeTextTupleEncoding.Slice slice = tuple.slice(index);
            byte[] expected = components.get(index)
                    .getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(
                    expected,
                    Arrays.copyOfRange(
                            decoded,
                            slice.offset(),
                            slice.offset() + slice.length()));
            int terminator = slice.offset() + slice.length();
            if (terminator < decoded.length) {
                assertEquals(0, decoded[terminator]);
            } else {
                // NativeTextEncoder owns the final record terminator, which
                // is intentionally omitted by decodeBytes().
                assertEquals(decoded.length, terminator);
            }
        }
    }

    @Test
    void componentBuildPurposeAndUseMaterialDiversifyRecord() {
        NativeTextTupleEncoder encoder = new NativeTextTupleEncoder();
        NativeTextTupleEncoding baseline = encoder.encode(
                NativeTextBuildKey.fromUtf8("build-a"),
                NativeTextPurpose.RUNTIME_DESCRIPTOR,
                "binding-a",
                List.of("Owner", "name", "()V"));
        NativeTextTupleEncoding repeated = encoder.encode(
                NativeTextBuildKey.fromUtf8("build-a"),
                NativeTextPurpose.RUNTIME_DESCRIPTOR,
                "binding-a",
                List.of("Owner", "name", "()V"));
        NativeTextTupleEncoding changedComponent = encoder.encode(
                NativeTextBuildKey.fromUtf8("build-a"),
                NativeTextPurpose.RUNTIME_DESCRIPTOR,
                "binding-a",
                List.of("Owner", "other", "()V"));
        NativeTextTupleEncoding changedPurpose = encoder.encode(
                NativeTextBuildKey.fromUtf8("build-a"),
                NativeTextPurpose.RUNTIME_METHOD_NAME,
                "binding-a",
                List.of("Owner", "name", "()V"));
        NativeTextTupleEncoding changedUse = encoder.encode(
                NativeTextBuildKey.fromUtf8("build-a"),
                NativeTextPurpose.RUNTIME_DESCRIPTOR,
                "binding-b",
                List.of("Owner", "name", "()V"));

        assertEquals(baseline.record(), repeated.record());
        assertNotEquals(baseline.record(), changedComponent.record());
        assertNotEquals(baseline.record(), changedPurpose.record());
        assertNotEquals(baseline.record(), changedUse.record());
    }

    @Test
    void generatedTuplesStayFunctionLocalAndPassHardeningAudit() {
        String fragment = """
                static int consume(const char*, const char*, const char*);
                static int first(void) {
                    return consume("owner/A", "first", "()V");
                }
                static int second(void) {
                    return consume("owner/B", "second", "(I)I");
                }
                """;
        String output = new GeneratedCFragmentTextObfuscator().obfuscate(
                NativeTextBuildKey.fromUtf8("tuple-audit-build"),
                "binding-tuples",
                fragment,
                GeneratedCTextPolicy.sensitive(
                        NativeTextPurpose.GENERATED_C_FRAGMENT));

        NativeTextSourceMetrics metrics =
                new NativeTextSourceScanner().scan(output);
        assertEquals(2, metrics.cipherArrayCount());
        assertEquals(2, metrics.siteBoundCodecCount());
        assertTrue(metrics.largestDecoderCipherFanout() <= 1);
        assertFalse(output.contains("_table[]"));
        assertFalse(output.contains("j2ll_decode_metadata_strings"));
        GeneratedNativeHardeningAuditResult audit =
                new GeneratedNativeHardeningAudit().audit(output);
        assertTrue(audit.passed(), audit.findings().toString());
    }

    @Test
    void oneFunctionUsesOneBoundedCodecRegardlessOfTupleWidth() {
        List<String> values = List.of(
                "owner/A",
                "name",
                "(Ljava/lang/String;)V",
                "java/lang/String",
                "valueOf",
                "(I)Ljava/lang/String;");
        String fragment = """
                static int consume(
                        const char*, const char*, const char*,
                        const char*, const char*, const char*);
                static int binding(void) {
                    return consume(
                            "owner/A",
                            "name",
                            "(Ljava/lang/String;)V",
                            "java/lang/String",
                            "valueOf",
                            "(I)Ljava/lang/String;");
                }
                """;
        String output = new GeneratedCFragmentTextObfuscator().obfuscate(
                NativeTextBuildKey.fromUtf8("tuple-size-build"),
                "binding-size",
                fragment);

        assertEquals(1, occurrences(output, "_cipher[] = {"));
        assertEquals(
                1,
                new NativeTextSourceScanner().scan(output)
                        .siteBoundCodecCount());
        assertEquals(0, occurrences(output, ".ready == 0u"));
        assertEquals(1, occurrences(
                output.substring(output.indexOf("static int consume")),
                "j2ll_nt_use_"));
        assertEquals(5, occurrences(output, ".value + "));
        String independent = independentDecodeBaseline(values);
        assertTrue(
                output.length() * 4 < independent.length() * 3,
                "tuple source budget: tuple="
                        + output.length()
                        + ", independent="
                        + independent.length());
    }

    @Test
    void useCoherentGroupsRespectComponentAndDecodedByteBounds() {
        StringBuilder arguments = new StringBuilder();
        for (int index = 0; index < 17; index++) {
            if (index > 0) {
                arguments.append(", ");
            }
            arguments.append('"').append("v").append(index).append('"');
        }
        String fragment = "static int consume();\n"
                + "static int bounded(void) { return consume("
                + arguments
                + "); }\n";
        String componentBounded =
                new GeneratedCFragmentTextObfuscator().obfuscate(
                        NativeTextBuildKey.fromUtf8("component-bound"),
                        "component-bound",
                        fragment);
        assertEquals(3, occurrences(componentBounded, "_cipher[] = {"));

        String longValue = "x".repeat(520);
        String bytesFragment = "static int pair(const char*, const char*);\n"
                + "static int bytes(void) { return pair(\""
                + "a".repeat(500)
                + "\", \""
                + longValue
                + "\"); }\n";
        String byteBounded =
                new GeneratedCFragmentTextObfuscator().obfuscate(
                        NativeTextBuildKey.fromUtf8("byte-bound"),
                        "byte-bound",
                        bytesFragment);
        assertEquals(2, occurrences(byteBounded, "_cipher[] = {"));
    }

    @Test
    void encoderRejectsUnboundedAggregateButAllowsOneOversizedComponent() {
        NativeTextTupleEncoder encoder = new NativeTextTupleEncoder();
        NativeTextBuildKey key = NativeTextBuildKey.fromUtf8("bound-build");
        assertThrows(
                IllegalArgumentException.class,
                () -> encoder.encode(
                        key,
                        NativeTextPurpose.GENERATED_C_FRAGMENT,
                        "too-many",
                        java.util.stream.IntStream.range(0, 9)
                                .mapToObj(index -> "v" + index)
                                .toList()));
        assertThrows(
                IllegalArgumentException.class,
                () -> encoder.encode(
                        key,
                        NativeTextPurpose.GENERATED_C_FRAGMENT,
                        "too-wide",
                        List.of("a".repeat(300), "b".repeat(300))));
        NativeTextTupleEncoding oversized = encoder.encode(
                key,
                NativeTextPurpose.GENERATED_C_FRAGMENT,
                "oversized-singleton",
                List.of("x".repeat(700)));
        assertEquals(1, oversized.componentCount());
    }

    private String independentDecodeBaseline(List<String> values) {
        NativeTextBuildKey buildKey =
                NativeTextBuildKey.fromUtf8("tuple-size-build");
        NativeTextEncoder encoder = new NativeTextEncoder();
        NativeTextCEmitter emitter = new NativeTextCEmitter();
        StringBuilder source = new StringBuilder("static int binding(void) {\n");
        for (int index = 0; index < values.size(); index++) {
            NativeTextEncoding encoding = encoder.encode(
                    buildKey,
                    NativeTextPurpose.GENERATED_C_FRAGMENT,
                    "binding-size:independent:" + index,
                    values.get(index));
            source.insert(0, emitter.ciphertextDeclaration(encoding));
            source.append(emitter.scratchDeclarationAndDecode(
                            encoding,
                            "independent_" + index))
                    .append(emitter.scratchCleanup(
                            encoding,
                            "independent_" + index));
        }
        return source.append("return 0;\n}\n").toString();
    }

    private int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
