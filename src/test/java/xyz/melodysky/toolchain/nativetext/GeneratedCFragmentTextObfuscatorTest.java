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
        assertTrue(output.contains("__attribute__((cleanup(j2ll_nt_cleanup_"));
        assertFalse(output.contains("j2ll_native_text_decode("));
        assertTrue(output.contains("j2ll_nt_word_"));
        assertTrue(output.contains(
                "j2ll_native_text_zero((unsigned char*)memory + sizeof(size_t), length)"));
        assertEquals(1, occurrences(output, "static void j2ll_nt_cleanup_"));
        assertEquals(2, occurrences(output, "_cipher[] = {"));
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
        assertEquals(1, occurrences(output, "static void j2ll_nt_cleanup_"));
        assertEquals(2, occurrences(output, "__attribute__((cleanup(j2ll_nt_cleanup_"));
        assertEquals(3, occurrences(output, "(const char*)j2ll_nt_use_"));
        assertEquals(2, occurrences(output, "#define j2ll_nt_use_"));
        assertTrue(output.contains(".ready == 0u"));
        assertFalse(output.substring(output.indexOf("static int first"))
                .contains("j2ll_nt_word_"));
    }

    @Test
    void lazyOnceRequiresExplicitLowSensitivityRuntimeErrorPolicy() {
        String fragment =
                "const char* runtime_message(void) { return \"ordinary runtime warning\"; }\n";

        assertThrows(
                IllegalArgumentException.class,
                () -> obfuscator.obfuscate(
                        NativeTextBuildKey.fromUtf8("fixed-build"),
                        "runtime:error",
                        fragment));

        String output = obfuscator.obfuscate(
                NativeTextBuildKey.fromUtf8("fixed-build"),
                "runtime:error",
                fragment,
                GeneratedCTextPolicy.lowSensitivityRuntimeError());

        assertFalse(output.contains("ordinary runtime warning"));
        assertTrue(output.contains("static _Atomic int j2ll_gcf_low_once_"));
        assertTrue(output.contains("j2ll_gcf_low_decode_"));
        assertTrue(output.contains("atomic_compare_exchange_strong_explicit("));
        assertFalse(output.contains("j2ll_encoded_metadata_strings"));
    }

    @Test
    void lazyOnceDeduplicatesEqualValuesAndDecodesOnlyFunctionUses() {
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
                GeneratedCTextPolicy.lowSensitivityRuntimeError());

        assertEquals(3, occurrences(output, "_cipher[] = {"));
        assertEquals(
                3,
                occurrences(
                        output,
                        "static void j2ll_gcf_low_decode_"));
        String untouched = output.substring(
                output.indexOf("static int untouched"));
        assertFalse(untouched.contains("j2ll_gcf_low_decode_"));
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
        assertTrue(output.contains("(const char*)j2ll_nt_use_"));
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
                int main(void) {
                    return evaluate(0) != 0 || evaluate(1) != 0;
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
