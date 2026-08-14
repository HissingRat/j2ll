package xyz.melodysky.toolchain.nativetext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedCFragmentTextObfuscatorTest {
    private final GeneratedCFragmentTextObfuscator obfuscator =
            new GeneratedCFragmentTextObfuscator();

    @Test
    void sensitiveTextUsesActivationLocalScratchAndExitCleanup() {
        String fragment = """
                static int consume(const char* first, const char* second);

                static int evaluate(int early) {
                    const char* owner = "owner/Secret";
                    if (early) {
                        return owner[0];
                    }
                    return consume(owner, "(Ljava/lang/String;)V");
                }
                """;

        String output = obfuscator.obfuscate(
                NativeTextBuildKey.fromUtf8("fixed-build"),
                "runtime:metadata",
                fragment,
                GeneratedCTextPolicy.sensitive(
                        NativeTextPurpose.RUNTIME_DESCRIPTOR));

        assertFalse(output.contains("\"owner/Secret\""));
        assertFalse(output.contains("\"(Ljava/lang/String;)V\""));
        assertTrue(output.contains("static const unsigned char j2ll_nt_"));
        assertTrue(output.contains("size_t length;"));
        assertTrue(output.contains(
                "__attribute__((cleanup("
                        + NativeScratchZeroizerSource
                                .CLEANUP_FUNCTION_NAME
                        + ")))"));
        assertFalse(output.contains(
                "__attribute__((cleanup("
                        + NativeScratchZeroizerSource
                                .CLEANUP_FUNCTION_NAME
                        + "))) = {"));
        assertFalse(output.contains("j2ll_native_text_decode("));
        assertTrue(output.contains("j2ll_nt_word_"));
        assertFalse(output.contains("static void j2ll_nt_cleanup_"));
        assertFalse(output.contains(
                "static __attribute__((noinline, unused)) void "
                        + NativeScratchZeroizerSource
                                .CLEANUP_FUNCTION_NAME));
        assertEquals(2, occurrences(output, "_cipher[] = {"));
        assertEquals(0, occurrences(output, ".ready == 0u"));
        assertEquals(0, occurrences(output, ".ready = 0u;"));
        assertEquals(2, useInvocationCount(output));
        assertEquals(
                2,
                new NativeTextSourceScanner().scan(output)
                        .siteBoundCodecCount());
        assertFalse(output.contains("_Atomic int"));
        assertFalse(output.contains("j2ll_gcf_low_once_"));
    }

    @Test
    void sensitiveTextReusesEqualValuesOnlyInsideOneFunction() {
        String fragment = """
                static int consume(const char*);
                static int first(void) {
                    return consume("same-value") + consume("same-value");
                }
                static int second(void) {
                    return consume("same-value");
                }
                """;

        String output = obfuscator.obfuscate(
                NativeTextBuildKey.fromUtf8("fixed-build"),
                "runtime:metadata",
                fragment,
                GeneratedCTextPolicy.sensitive(
                        NativeTextPurpose.RUNTIME_DESCRIPTOR));

        assertEquals(2, occurrences(output, "_cipher[] = {"));
        assertEquals(0, occurrences(output, "static void j2ll_nt_cleanup_"));
        assertEquals(
                2,
                occurrences(
                        output,
                        "__attribute__((cleanup("
                                + NativeScratchZeroizerSource
                                        .CLEANUP_FUNCTION_NAME
                                + ")))"));
        assertEquals(3, occurrences(output, "(const char*)(j2ll_nt_use_"));
        assertEquals(2, occurrences(output, "#define j2ll_nt_use_"));
        assertEquals(1, occurrences(output, ".ready == 0u"));
        assertEquals(1, occurrences(output, ".ready = 0u;"));
        assertFalse(output.substring(output.indexOf("static int first"))
                .contains("j2ll_nt_word_"));
    }

    @Test
    void onlyDirectArgumentsFromOneCallShareARecord() {
        String fragment = """
                static int pair(const char*, const char*);
                static int evaluate(int branch) {
                    const char* assigned = "assignment-only";
                    if (branch) {
                        return pair("first-owner", "first-name");
                    }
                    return pair("second-owner", "second-name")
                            + assigned[0];
                }
                """;

        String output = obfuscator.obfuscate(
                NativeTextBuildKey.fromUtf8("use-coherent-build"),
                "use-coherent-scope",
                fragment);

        assertEquals(3, occurrences(output, "_cipher[] = {"));
        assertEquals(0, occurrences(output, ".ready == 0u"));
        assertEquals(3, useInvocationCount(output));
        assertEquals(
                2,
                occurrences(output, ".value + "),
                "each two-component call must decode once and use one raw tuple offset");
        assertEquals(
                3,
                new NativeTextSourceScanner().scan(output)
                        .siteBoundCodecCount());
        assertTrue(new GeneratedNativeHardeningAudit().audit(output).passed());
    }

    @Test
    void runtimeErrorPolicyUsesActivationLocalScratchAndDomainSeparation() {
        String fragment = """
                static int consume(const char* value);
                static int runtime_message(void) {
                    return consume("ordinary runtime warning");
                }
                """;

        String output = obfuscator.obfuscate(
                NativeTextBuildKey.fromUtf8("fixed-build"),
                "runtime:error",
                fragment,
                GeneratedCTextPolicy.sensitive(
                        NativeTextPurpose.RUNTIME_ERROR));
        String generatedFragmentDomain = obfuscator.obfuscate(
                NativeTextBuildKey.fromUtf8("fixed-build"),
                "runtime:error",
                fragment);

        assertFalse(output.contains("ordinary runtime warning"));
        assertTrue(output.contains("static const unsigned char j2ll_nt_"));
        assertTrue(output.contains(
                "__attribute__((cleanup("
                        + NativeScratchZeroizerSource.CLEANUP_FUNCTION_NAME
                        + ")))"));
        assertNotEquals(
                firstTextSymbol(output),
                firstTextSymbol(generatedFragmentDomain));
        assertFalse(output.contains("#include <stdatomic.h>"));
        assertFalse(output.contains("_Atomic"));
        assertFalse(output.contains("atomic_"));
        assertFalse(output.contains("j2ll_gcf_low_"));
        assertFalse(output.matches(
                "(?s).*static\\s+unsigned\\s+char\\s+j2ll_nt_[0-9a-f]{24}_cipher.*"));
        assertFalse(output.contains("j2ll_encoded_metadata_strings"));
    }

    @Test
    void runtimeErrorTextDoesNotShareScratchOrEncodingAcrossFunctions() {
        String fragment = """
                static int consume(const char*);
                static int first(void) {
                    return consume("shared") + consume("first-only");
                }
                static int second(void) {
                    return consume("shared") + consume("second-only");
                }
                static int untouched(void) {
                    return 7;
                }
                """;

        String output = obfuscator.obfuscate(
                NativeTextBuildKey.fromUtf8("fixed-build"),
                "runtime:error",
                fragment,
                GeneratedCTextPolicy.sensitive(
                        NativeTextPurpose.RUNTIME_ERROR));

        assertEquals(4, occurrences(output, "_cipher[] = {"));
        assertEquals(2, occurrences(
                output,
                "__attribute__((cleanup("
                        + NativeScratchZeroizerSource.CLEANUP_FUNCTION_NAME
                        + ")))"));
        assertEquals(0, occurrences(output, ".ready == 0u"));
        assertEquals(0, occurrences(output, ".ready = 0u;"));
        assertFalse(output.contains("j2ll_gcf_low_"));
        assertFalse(output.contains("_Atomic"));
        assertTrue(output.contains("static int untouched(void) {\n"
                + "    return 7;\n"
                + "}"));
        var audit = new GeneratedNativeHardeningAudit().audit(output);
        assertTrue(audit.passed(), audit.findings().toString());
        assertTrue(audit.evidence().contains(
                GeneratedNativeHardeningAudit
                        .EVIDENCE_CALL_LOCAL_TEXT_CLEANUP));
    }

    @Test
    void lexerPreservesCommentsPreprocessorAndCharacterLiterals() {
        String fragment = """
                #include "generated-header.h"
                // "comment-only"
                /* block "comment-only-too" */
                static const char quote = '"';

                int read_message(void) {
                    const char* message = "line\\nquote=\\\" slash=\\\\ octal=\\101";
                    return message[0] + quote;
                }
                """;

        String output = obfuscator.obfuscate(
                NativeTextBuildKey.fromUtf8("fixed-build"),
                "runtime:error",
                fragment);

        assertTrue(output.contains("#include \"generated-header.h\""));
        assertTrue(output.contains("// \"comment-only\""));
        assertTrue(output.contains("/* block \"comment-only-too\" */"));
        assertTrue(output.contains("static const char quote = '\"';"));
        assertFalse(output.contains("\"line\\nquote="));
        assertTrue(output.contains("(const char*)(j2ll_nt_use_"));
    }

    @Test
    void buildKeyAndScopeDiversifyCiphertextSymbolsAndSource() {
        String fragment =
                "int consume(const char*); int value(void) { return consume(\"same-value\"); }\n";
        String baseline = obfuscator.obfuscate(
                NativeTextBuildKey.fromUtf8("build-a"),
                "scope-a",
                fragment);
        String repeated = obfuscator.obfuscate(
                NativeTextBuildKey.fromUtf8("build-a"),
                "scope-a",
                fragment);
        String otherBuild = obfuscator.obfuscate(
                NativeTextBuildKey.fromUtf8("build-b"),
                "scope-a",
                fragment);
        String otherScope = obfuscator.obfuscate(
                NativeTextBuildKey.fromUtf8("build-a"),
                "scope-b",
                fragment);

        assertEquals(baseline, repeated);
        assertNotEquals(baseline, otherBuild);
        assertNotEquals(baseline, otherScope);
        assertNotEquals(firstTextSymbol(baseline), firstTextSymbol(otherBuild));
        assertNotEquals(firstTextSymbol(baseline), firstTextSymbol(otherScope));
    }

    @Test
    void acceptsAutomaticPointerButRejectsEscapingStorage() {
        String automatic = """
                int consume(const char*);
                int value(void) {
                    const char* local = "value";
                    return consume(local);
                }
                """;
        assertTrue(obfuscator
                .obfuscate(
                        NativeTextBuildKey.fromUtf8("fixed-build"),
                        "automatic",
                        automatic)
                .contains("j2ll_nt_local_"));

        for (String storage : List.of(
                "static",
                "extern",
                "register",
                "_Thread_local")) {
            String fragment = """
                    int value(void) {
                        %s const char* local = "value";
                        return local[0];
                    }
                    """.formatted(storage);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> obfuscator.obfuscate(
                            NativeTextBuildKey.fromUtf8("fixed-build"),
                            "escaping:" + storage,
                            fragment),
                    storage);
        }
    }

    @Test
    void rejectsUnsupportedLiteralContextsFailClosed() {
        List<String> unsupported = List.of(
                "int value(void) { return sizeof(\"value\"); }\n",
                "int value(void) { char value[] = \"value\"; return value[0]; }\n",
                "const wchar_t* value(void) { return L\"value\"; }\n",
                "const char* value(void) { return u8\"value\"; }\n",
                "int consume(const char*); int value(void) { return consume(\"\\x41\"); }\n",
                "int consume(const char*); int value(void) { return consume(\"\\u0041\"); }\n",
                "int consume(const char*); int value(void) { return consume(\"a\" \"b\"); }\n",
                """
                static const char* values[] = { "first", "second" };
                int value(void) { return values[0][0]; }
                """);

        for (int index = 0; index < unsupported.size(); index++) {
            int caseIndex = index;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> obfuscator.obfuscate(
                            NativeTextBuildKey.fromUtf8("fixed-build"),
                            "unsupported:" + caseIndex,
                            unsupported.get(caseIndex)),
                    "case " + caseIndex);
        }
    }

    @Test
    void generatedSensitiveSourceCompilesAndPreservesParity(
            @TempDir Path temp) throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(clang != null, "clang is required for generated C parity");

        String fragment = """
                #include <string.h>
                static int evaluate(int early) {
                    const char* owner = "owner/Secret";
                    if (early) {
                        return strcmp(owner, "owner/Secret");
                    }
                    return strcmp(owner, "different") == 0 ? 2 : 0;
                }
                static int verify_bytes(void) {
                    const char* unicode = "秘密";
                    const char* embedded = "left\\0ignored";
                    const char* empty = "";
                    return (unsigned char)unicode[0] != 0xe7u
                            || (unsigned char)unicode[1] != 0xa7u
                            || (unsigned char)unicode[2] != 0x98u
                            || embedded[4] != 0
                            || embedded[5] != 'i'
                            || empty[0] != 0;
                }
                int main(void) {
                    return evaluate(0) != 0
                            || evaluate(1) != 0
                            || verify_bytes() != 0;
                }
                """;
        String generated = """
                #include <stddef.h>
                #include <stdint.h>
                """
                + new NativeTextCEmitter().runtimeSource()
                + obfuscator.obfuscate(
                        NativeTextBuildKey.fromUtf8("compile-parity"),
                        "compile:parity",
                        fragment);
        Path source = temp.resolve("native-text.c");
        Path executable = temp.resolve(
                System.getProperty("os.name", "")
                                .toLowerCase(Locale.ROOT)
                                .contains("win")
                        ? "native-text.exe"
                        : "native-text");
        Files.writeString(source, generated, StandardCharsets.UTF_8);

        Process compile = new ProcessBuilder(
                        clang.toString(),
                        "-std=gnu11",
                        source.toString(),
                        "-o",
                        executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(compile.waitFor(45, TimeUnit.SECONDS), "clang compile timed out");
        String compileOutput =
                new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, compile.exitValue(), compileOutput);

        Process run = new ProcessBuilder(executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(run.waitFor(15, TimeUnit.SECONDS), "generated C run timed out");
        String runOutput =
                new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, run.exitValue(), runOutput);
    }

    @Test
    void singleUseTupleHasSourceAndObjectSizeEvidence(
            @TempDir Path temp) throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(clang != null, "clang is required for native-text size evidence");
        NativeTextBuildKey key = NativeTextBuildKey.fromUtf8(
                "single-use-object-size");
        String fragment = """
                static __attribute__((noinline)) int consume(
                        const char* first,
                        const char* second) {
                    return (unsigned char)first[0]
                            + (unsigned char)second[0];
                }
                static __attribute__((used, noinline)) int binding(void) {
                    return consume("owner/Secret", "(Ljava/lang/String;)V");
                }
                """;
        String prefix = """
                #include <stddef.h>
                #include <stdint.h>
                """ + new NativeTextCEmitter().runtimeSource();
        String fast = prefix + obfuscator.obfuscate(
                key,
                "single-use:fast",
                fragment);
        String guarded = prefix + guardedTupleSourceProbe(key);
        String independent = prefix + independentSiteDecoderProbe(key);

        assertTrue(
                fast.length() < guarded.length(),
                "single-use source budget: fast="
                        + fast.length()
                        + ", guarded="
                        + guarded.length());

        long fastBytes = compileObject(
                clang,
                temp,
                "single-use-fast",
                fast);
        long independentBytes = compileObject(
                clang,
                temp,
                "independent-sites",
                independent);

        assertTrue(
                fastBytes < independentBytes,
                "single-use tuple object budget: fast="
                        + fastBytes
                        + ", independent="
                        + independentBytes);
    }

    private String firstTextSymbol(String source) {
        java.util.regex.Matcher matcher = Pattern
                .compile("static const unsigned char (j2ll_nt_[0-9a-f]{24})_cipher\\[\\]")
                .matcher(source);
        assertTrue(matcher.find(), source);
        return matcher.group(1);
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

    private int useInvocationCount(String source) {
        java.util.regex.Matcher matcher = Pattern
                .compile("(?m)^(?!#define j2ll_nt_use_).*\\bj2ll_nt_use_")
                .matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private String independentSiteDecoderProbe(NativeTextBuildKey key) {
        NativeTextEncoder encoder = new NativeTextEncoder();
        NativeTextCEmitter emitter = new NativeTextCEmitter();
        NativeTextEncoding owner = encoder.encode(
                key,
                NativeTextPurpose.GENERATED_C_FRAGMENT,
                "single-use:independent:owner",
                "owner/Secret");
        NativeTextEncoding descriptor = encoder.encode(
                key,
                NativeTextPurpose.GENERATED_C_FRAGMENT,
                "single-use:independent:descriptor",
                "(Ljava/lang/String;)V");
        return emitter.ciphertextDeclaration(owner)
                + emitter.ciphertextDeclaration(descriptor)
                + """
                static __attribute__((noinline)) int consume(
                        const char* first,
                        const char* second) {
                    return (unsigned char)first[0]
                            + (unsigned char)second[0];
                }
                static __attribute__((used, noinline)) int binding(void) {
                """
                + emitter.scratchDeclarationAndDecode(owner, "owner_value")
                + emitter.scratchDeclarationAndDecode(
                        descriptor,
                        "descriptor_value")
                + "    int result = consume(owner_value, descriptor_value);\n"
                + "    "
                + emitter.scratchCleanup(owner, "owner_value")
                + "    "
                + emitter.scratchCleanup(descriptor, "descriptor_value")
                + "    return result;\n}\n";
    }

    private String guardedTupleSourceProbe(NativeTextBuildKey key) {
        NativeTextTupleEncoding tuple = new NativeTextTupleEncoder().encode(
                key,
                NativeTextPurpose.GENERATED_C_FRAGMENT,
                "single-use:guarded",
                List.of("owner/Secret", "(Ljava/lang/String;)V"));
        NativeTextEncoding record = tuple.record();
        NativeTextCEmitter emitter = new NativeTextCEmitter();
        String token = record.symbol().substring("j2ll_nt_".length());
        String scratch = "j2ll_nt_local_" + token;
        String slot = scratch + ".slot_" + token;
        String decode = emitter.decodeTupleInto(
                tuple,
                slot + ".value",
                "            ");
        String macro = "#define j2ll_nt_use_" + token + "() \\\n"
                + "    __extension__ ({ \\\n"
                + "        if (" + slot + ".ready == 0u) { \\\n"
                + continuationLines(decode)
                + "            " + slot + ".ready = 1u; \\\n"
                + "        } \\\n"
                + "        " + slot + ".value; \\\n"
                + "    })\n";
        return emitter.ciphertextDeclaration(record)
                + macro
                + """
                static __attribute__((noinline)) int consume(
                        const char* first,
                        const char* second) {
                    return (unsigned char)first[0]
                            + (unsigned char)second[0];
                }
                static __attribute__((used, noinline)) int binding(void) {
                    struct {
                        size_t length;
                        struct {
                            unsigned char ready;
                            char value[sizeof(%s_cipher)];
                        } slot_%s;
                    } %s __attribute__((cleanup(%s))) = {
                        .length = sizeof(%s) - sizeof(size_t)
                    };
                    return consume(
                            (const char*)(j2ll_nt_use_%s() + %du),
                            (const char*)(j2ll_nt_use_%s() + %du));
                }
                """.formatted(
                        record.symbol(),
                        token,
                        scratch,
                        NativeScratchZeroizerSource.CLEANUP_FUNCTION_NAME,
                        scratch,
                        token,
                        tuple.slice(0).offset(),
                        token,
                        tuple.slice(1).offset());
    }

    private String continuationLines(String source) {
        StringBuilder result = new StringBuilder();
        for (String line : source.split("\\n")) {
            result.append(line).append(" \\\n");
        }
        return result.toString();
    }

    private long compileObject(
            Path clang,
            Path temp,
            String name,
            String sourceText) throws Exception {
        Path source = temp.resolve(name + ".c");
        Path object = temp.resolve(name + ".o");
        Files.writeString(source, sourceText, StandardCharsets.UTF_8);
        Process compile = new ProcessBuilder(
                        clang.toString(),
                        "-std=gnu11",
                        "-Oz",
                        "-c",
                        source.toString(),
                        "-o",
                        object.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(
                compile.waitFor(45, TimeUnit.SECONDS),
                name + " object compile timed out");
        String output = new String(
                compile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, compile.exitValue(), output);
        return Files.size(object);
    }

    private Optional<Path> findClang() {
        String configured = System.getProperty("j2ll.test.clang");
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        List<String> names = System.getProperty("os.name", "")
                        .toLowerCase(Locale.ROOT)
                        .contains("win")
                ? List.of("clang.exe", "clang")
                : List.of("clang");
        for (String directory : path.split(Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) {
                continue;
            }
            for (String name : names) {
                Path candidate = Path.of(directory).resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }
}
