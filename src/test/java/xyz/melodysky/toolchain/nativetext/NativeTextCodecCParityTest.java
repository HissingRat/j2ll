package xyz.melodysky.toolchain.nativetext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeTextCodecCParityTest {
    @Test
    void everyCodecFamilyCompilesAndDecodesWithCParity(
            @TempDir Path temp) throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(clang != null, "clang is required for native-text codec parity");

        NativeTextBuildKey buildKey =
                NativeTextBuildKey.fromUtf8("all-codec-c-parity");
        NativeTextEncoder encoder = new NativeTextEncoder();
        List<NativeTextEncoding> encodings =
                encodingsByCodecCase(encoder, buildKey);
        assertTrue(encodings.stream()
                .noneMatch(encoding ->
                        encoding.storagePermutation().isIdentity()));
        NativeTextEncoding singleton = encoder.encode(
                buildKey,
                NativeTextPurpose.RUNTIME_ERROR,
                "c-parity-singleton",
                "");
        assertEquals(1, singleton.ciphertext().length);
        assertTrue(singleton.storagePermutation().isIdentity());
        List<String> boundaryPlaintexts = List.of(
                "a",
                "p".repeat(30),
                "w".repeat(31),
                "c".repeat(59));
        List<NativeTextEncoding> boundaryEncodings =
                java.util.stream.IntStream.range(
                                0,
                                boundaryPlaintexts.size())
                        .mapToObj(index -> encoder.encode(
                                buildKey,
                                NativeTextPurpose.RUNTIME_ERROR,
                                "c-parity-affine-length:" + index,
                                boundaryPlaintexts.get(index)))
                        .toList();
        assertTrue(boundaryEncodings.stream()
                .noneMatch(encoding ->
                        encoding.storagePermutation().isIdentity()));
        NativeTextCEmitter emitter = new NativeTextCEmitter();
        StringBuilder source = new StringBuilder("""
                #include <stddef.h>
                #include <stdint.h>
                #include <string.h>

                """).append(emitter.runtimeSource());
        int functionIndex = 0;
        for (NativeTextEncoding encoding : encodings) {
            // The first case intentionally collides with the codec's compact
            // loop-local position name. The emitter must capture the caller's
            // destination before declaring any compact locals.
            String scratch = functionIndex == 0
                    ? "p"
                    : "decoded_" + functionIndex;
            source.append(emitter.ciphertextDeclaration(encoding))
                    .append("static int check_")
                    .append(functionIndex)
                    .append("(void) {\n    ")
                    .append(emitter.scratchDeclarationAndDecode(
                            encoding,
                            scratch).replace("\n", "\n    ").stripTrailing())
                    .append("\n    int result = strcmp(")
                    .append(scratch)
                    .append(", \"codec-family-placeholder\");\n    ")
                    .append(emitter.scratchCleanup(encoding, scratch))
                    .append("    return result;\n}\n\n");
            functionIndex++;
        }
        String singletonScratch = "decoded_singleton";
        source.append(emitter.ciphertextDeclaration(singleton))
                .append("static int check_singleton(void) {\n    ")
                .append(emitter.scratchDeclarationAndDecode(
                        singleton,
                        singletonScratch)
                        .replace("\n", "\n    ")
                        .stripTrailing())
                .append("\n    int result = ")
                .append(singletonScratch)
                .append("[0] != '\\0';\n    ")
                .append(emitter.scratchCleanup(
                        singleton,
                        singletonScratch))
                .append("    return result;\n}\n\n");
        for (int index = 0;
                index < boundaryEncodings.size();
                index++) {
            NativeTextEncoding encoding =
                    boundaryEncodings.get(index);
            String scratch = "decoded_boundary_" + index;
            source.append(emitter.ciphertextDeclaration(encoding))
                    .append("static int check_boundary_")
                    .append(index)
                    .append("(void) {\n    ")
                    .append(emitter.scratchDeclarationAndDecode(
                            encoding,
                            scratch)
                            .replace("\n", "\n    ")
                            .stripTrailing())
                    .append("\n    int result = strcmp(")
                    .append(scratch)
                    .append(", \"")
                    .append(boundaryPlaintexts.get(index))
                    .append("\");\n    ")
                    .append(emitter.scratchCleanup(
                            encoding,
                            scratch))
                    .append("    return result;\n}\n\n");
        }
        source.append("int main(void) {\n    return ");
        for (int index = 0; index < functionIndex; index++) {
            if (index > 0) {
                source.append(" || ");
            }
            source.append("check_").append(index).append("()");
        }
        source.append(" || check_singleton()");
        for (int index = 0;
                index < boundaryEncodings.size();
                index++) {
            source.append(" || check_boundary_")
                    .append(index)
                    .append("()");
        }
        source.append(";\n}\n");

        NativeTextSourceMetrics metrics =
                new NativeTextSourceScanner().scan(source.toString());
        assertEquals(
                NativeTextCodecFamily.values().length,
                metrics.codecFamilyCount());
        int expectedSites =
                encodings.size() + 1 + boundaryEncodings.size();
        assertEquals(expectedSites, metrics.cipherArrayCount());
        assertEquals(
                expectedSites,
                metrics.runtimeBoundCipherReadCount());
        assertEquals(expectedSites, metrics.siteBoundCodecCount());
        assertEquals(0, metrics.fixedDecoderShapeOccurrences());
        assertEquals(0, metrics.adjacentSeedCipherOccurrences());
        assertTrue(metrics.largestDecoderCipherFanout() <= 1);

        Path cFile = temp.resolve("native-text-codecs.c");
        Path executable = temp.resolve(isWindows()
                ? "native-text-codecs.exe"
                : "native-text-codecs");
        Files.writeString(cFile, source, StandardCharsets.UTF_8);
        Process compile = new ProcessBuilder(
                        clang.toString(),
                        "-std=gnu11",
                        "-Wall",
                        "-Wextra",
                        "-Werror",
                        cFile.toString(),
                        "-o",
                        executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(
                compile.waitFor(45, TimeUnit.SECONDS),
                "native-text codec C compile timed out");
        String compileOutput = new String(
                compile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, compile.exitValue(), compileOutput);

        Process run = new ProcessBuilder(executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(
                run.waitFor(15, TimeUnit.SECONDS),
                "native-text codec C parity run timed out");
        String runOutput = new String(
                run.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, run.exitValue(), runOutput);
    }

    @Test
    void optimizedObjectDoesNotReconstitutePlaintext(
            @TempDir Path temp) throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(clang != null, "clang is required for optimized native-text audit");

        String plaintext =
                "j2ll-optimizer-folding-regression-secret-owner-and-descriptor";
        NativeTextEncoding encoding = new NativeTextEncoder().encode(
                NativeTextBuildKey.fromUtf8("optimized-object-runtime-boundary"),
                NativeTextPurpose.REGISTRATION_OWNER,
                "optimized-object:owner",
                plaintext);
        assertFalse(encoding.storagePermutation().isIdentity());
        NativeTextCEmitter emitter = new NativeTextCEmitter();
        String source = """
                #include <stddef.h>
                #include <stdint.h>

                """
                + emitter.ciphertextDeclaration(encoding)
                + "void j2ll_decode_probe(unsigned char* output) {\n"
                + emitter.decodeInto(encoding, "output", "    ")
                + "}\n";
        assertFalse(source.contains(plaintext));

        Path cFile = temp.resolve("native-text-optimized.c");
        Path objectFile = temp.resolve("native-text-optimized.o");
        Files.writeString(cFile, source, StandardCharsets.UTF_8);
        Process compile = new ProcessBuilder(
                        clang.toString(),
                        "-std=gnu11",
                        "-O2",
                        "-c",
                        cFile.toString(),
                        "-o",
                        objectFile.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(
                compile.waitFor(45, TimeUnit.SECONDS),
                "optimized native-text C compile timed out");
        String compileOutput = new String(
                compile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, compile.exitValue(), compileOutput);
        byte[] objectBytes = Files.readAllBytes(objectFile);
        assertNoPlaintextWindows(
                objectBytes,
                plaintext.getBytes(StandardCharsets.UTF_8),
                12);
        assertNoPlaintextWindows(
                objectBytes,
                plaintext.getBytes(StandardCharsets.UTF_16LE),
                12);
    }

    @Test
    void affineMutableCipherUsesScratchForSafeInPlaceDecode(
            @TempDir Path temp) throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(
                clang != null,
                "clang is required for affine in-place parity");

        String plaintext =
                "affine-in-place-cycle-overwrite-regression";
        NativeTextEncoding encoding = new NativeTextEncoder().encode(
                NativeTextBuildKey.fromUtf8("affine-in-place-build"),
                NativeTextPurpose.RUNTIME_ERROR,
                "affine-in-place-use",
                plaintext);
        assertFalse(encoding.storagePermutation().isIdentity());
        NativeTextCEmitter emitter = new NativeTextCEmitter();
        String source = """
                #include <stddef.h>
                #include <stdint.h>
                #include <string.h>

                """
                + emitter.runtimeSource()
                + emitter.mutableCiphertextDeclaration(encoding)
                + "static void decode_once(void) {\n"
                + emitter.decodeInPlace(
                        encoding,
                        encoding.symbol() + "_cipher",
                        "    ")
                + "}\n"
                + "int main(void) {\n"
                + "    decode_once();\n"
                + "    return strcmp((char*)"
                + encoding.symbol()
                + "_cipher, \""
                + plaintext
                + "\");\n"
                + "}\n";
        assertTrue(source.contains(
                "unsigned char j2ll_nt_in_place_"));
        assertTrue(source.contains(
                "j2ll_native_text_zero(j2ll_nt_in_place_"));
        GeneratedNativeHardeningAuditResult audit =
                new GeneratedNativeHardeningAudit()
                        .audit(source);
        assertTrue(audit.passed(), audit.findings().toString());
        assertTrue(audit.evidence().contains(
                GeneratedNativeHardeningAudit
                        .EVIDENCE_AFFINE_CIPHERTEXT_STORAGE));

        Path cFile = temp.resolve("native-text-in-place.c");
        Path executable = temp.resolve(isWindows()
                ? "native-text-in-place.exe"
                : "native-text-in-place");
        Files.writeString(cFile, source, StandardCharsets.UTF_8);
        Process compile = new ProcessBuilder(
                        clang.toString(),
                        "-std=gnu11",
                        "-Wall",
                        "-Wextra",
                        "-Werror",
                        cFile.toString(),
                        "-o",
                        executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(
                compile.waitFor(45, TimeUnit.SECONDS),
                "affine in-place C compile timed out");
        String compileOutput = new String(
                compile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, compile.exitValue(), compileOutput);

        Process run = new ProcessBuilder(executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(
                run.waitFor(15, TimeUnit.SECONDS),
                "affine in-place C parity run timed out");
        String runOutput = new String(
                run.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, run.exitValue(), runOutput);
    }

    @Test
    void affineStorageHasBoundedOptimizedObjectAndSourceOverhead(
            @TempDir Path temp) throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(
                clang != null,
                "clang is required for affine storage size audit");

        int siteCount = 96;
        NativeTextBuildKey buildKey =
                NativeTextBuildKey.fromUtf8("affine-storage-size-budget");
        NativeTextEncoder encoder = new NativeTextEncoder();
        NativeTextCEmitter emitter = new NativeTextCEmitter();
        String header = """
                #include <stddef.h>
                #include <stdint.h>

                """;
        StringBuilder affine = new StringBuilder(header);
        StringBuilder direct = new StringBuilder(header);
        // The direct-index variant is a size-only baseline. It deliberately
        // keeps the exact same ciphertext declarations, so the object delta
        // measures code rather than data duplication.
        for (int index = 0; index < siteCount; index++) {
            NativeTextEncoding encoding = encoder.encode(
                    buildKey,
                    NativeTextPurpose.RUNTIME_DESCRIPTOR,
                    "affine-size-site:" + index,
                    "(Ljava/lang/String;I)Ljava/lang/Object;");
            String declaration =
                    emitter.ciphertextDeclaration(encoding);
            String decode = emitter.decodeInto(
                    encoding,
                    "output",
                    "    ");
            affine.append(declaration)
                    .append("void affine_probe_")
                    .append(index)
                    .append("(unsigned char* output) {\n")
                    .append(decode)
                    .append("}\n\n");
            direct.append(declaration)
                    .append("void affine_probe_")
                    .append(index)
                    .append("(unsigned char* output) {\n")
                    .append(directStorageEquivalent(
                            encoding,
                            decode))
                    .append("}\n\n");
        }

        long generatedSourceOverhead =
                affine.length() - direct.length();
        assertTrue(generatedSourceOverhead > 0);
        assertTrue(
                generatedSourceOverhead <= siteCount * 440L,
                "affine generated-C overhead exceeded its per-site constant bound: "
                        + generatedSourceOverhead);

        Path affineObject = compileObject(
                clang,
                temp,
                "affine-storage",
                affine.toString());
        Path directObject = compileObject(
                clang,
                temp,
                "direct-storage",
                direct.toString());
        long optimizedObjectOverhead =
                Files.size(affineObject) - Files.size(directObject);
        assertTrue(
                optimizedObjectOverhead
                        <= siteCount * 160L + 4096L,
                "affine optimized-object overhead exceeded its per-site budget: "
                        + optimizedObjectOverhead);
    }

    private void assertNoPlaintextWindows(
            byte[] artifact,
            byte[] plaintext,
            int windowLength) {
        for (int offset = 0;
                offset + windowLength <= plaintext.length;
                offset++) {
            byte[] window = java.util.Arrays.copyOfRange(
                    plaintext,
                    offset,
                    offset + windowLength);
            assertFalse(
                    contains(artifact, window),
                    "optimizing compiler reconstructed a plaintext window at byte "
                            + offset);
        }
    }

    private boolean contains(byte[] haystack, byte[] needle) {
        for (int offset = 0;
                offset + needle.length <= haystack.length;
                offset++) {
            boolean equal = true;
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                return true;
            }
        }
        return false;
    }

    private String directStorageEquivalent(
            NativeTextEncoding encoding,
            String decodeSource) {
        String token =
                encoding.symbol().substring("j2ll_nt_".length());
        String storageIndex = "j2ll_nt_s_" + token;
        StringBuilder direct = new StringBuilder();
        boolean declarationFound = false;
        boolean firstLine = true;
        for (String line : decodeSource.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith(
                    "size_t " + storageIndex + " =")) {
                declarationFound = true;
                continue;
            }
            if (trimmed.startsWith(storageIndex + " +=")
                    || trimmed.startsWith(storageIndex + " -=")) {
                continue;
            }
            if (!firstLine) {
                direct.append('\n');
            }
            direct.append(line.replace(
                    storageIndex,
                    "p"));
            firstLine = false;
        }
        if (!declarationFound) {
            throw new AssertionError(
                    "affine storage index declaration is missing");
        }
        return direct.toString();
    }

    private Path compileObject(
            Path clang,
            Path directory,
            String name,
            String source) throws Exception {
        Path cFile = directory.resolve(name + ".c");
        Path objectFile = directory.resolve(name + ".o");
        Files.writeString(cFile, source, StandardCharsets.UTF_8);
        Process compile = new ProcessBuilder(
                        clang.toString(),
                        "-std=gnu11",
                        "-O2",
                        "-c",
                        cFile.toString(),
                        "-o",
                        objectFile.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(
                compile.waitFor(45, TimeUnit.SECONDS),
                "affine storage C compile timed out");
        String compileOutput = new String(
                compile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, compile.exitValue(), compileOutput);
        return objectFile;
    }

    private List<NativeTextEncoding> encodingsByCodecCase(
            NativeTextEncoder encoder,
            NativeTextBuildKey buildKey) {
        Map<String, NativeTextEncoding> result = new TreeMap<>();
        int expected = NativeTextCodecFamily.values().length * 3 * 2;
        for (int index = 0;
                index < 100_000 && result.size() < expected;
                index++) {
            NativeTextEncoding encoding = encoder.encode(
                    buildKey,
                    NativeTextPurpose.BUSINESS_STRING,
                    "c-parity-site:" + index,
                    "codec-family-placeholder");
            NativeTextCodecPlan plan = encoding.codecPlan();
            String key = plan.family().name()
                    + ':'
                    + plan.schedule()
                    + ':'
                    + plan.reverseTraversal();
            result.putIfAbsent(key, encoding);
        }
        if (result.size() != expected) {
            throw new AssertionError(
                    "could not select one deterministic site for every codec case");
        }
        return List.copyOf(result.values());
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
        List<String> names = isWindows()
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

    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }
}
