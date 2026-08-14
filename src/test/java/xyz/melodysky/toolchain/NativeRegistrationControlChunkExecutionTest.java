package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeRegistrationControlChunkExecutionTest {
    @TempDir
    Path temp;

    @Test
    void successAndBoundaryFailuresPreserveAtomicRollbackAndThrowableIdentity()
            throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(clang != null, "clang is required for the chunk-chain fake-JNI test");
        assumeTrue(
                Files.isRegularFile(
                        Path.of(System.getProperty("java.home"))
                                .resolve("include/jni.h")),
                "JDK JNI headers are required for the chunk-chain fake-JNI test");

        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        NativeRegistrationControlChunkExecutionFixture.OWNER_COUNT,
                        "fake-jni-registration-chunk-chain");
        NativeRegistrationControlTopologyPlan plan = emission.topologyPlan();
        assertEquals(3, plan.chunks().size());
        int lastInFirstChunk = plan.chunks().get(0).endExclusive();
        int firstInSecondChunk = plan.chunks().get(1).startInclusive() + 1;
        int firstInThirdChunk = plan.chunks().get(2).startInclusive() + 1;

        String harness = new NativeRegistrationControlChunkExecutionFixture()
                .harness(
                        emission.source(),
                        lastInFirstChunk,
                        firstInSecondChunk,
                        firstInThirdChunk);
        Path include = new ZigJniHeaderSet()
                .prepare(ZigBuildWorkspace.under(temp))
                .get(0);
        Path source = temp.resolve("registration_chunk_chain.c");
        Path executable = temp.resolve(isWindows()
                ? "registration_chunk_chain.exe"
                : "registration_chunk_chain");
        Files.writeString(source, harness, StandardCharsets.UTF_8);

        Process compile = new ProcessBuilder(
                        clang.toString(),
                        "-std=c11",
                        "-I",
                        include.toString(),
                        source.toString(),
                        "-o",
                        executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(
                compile.waitFor(45, TimeUnit.SECONDS),
                "clang compile timed out");
        String compileOutput = new String(
                compile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, compile.exitValue(), compileOutput);

        Process run = new ProcessBuilder(executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(
                run.waitFor(15, TimeUnit.SECONDS),
                "chunk-chain fake-JNI harness timed out");
        String runOutput = new String(
                run.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, run.exitValue(), runOutput);
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
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
