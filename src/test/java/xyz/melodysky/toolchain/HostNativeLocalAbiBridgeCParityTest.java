package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import xyz.melodysky.toolchain.HostNativeLocalAbiBridgeSource.Parameter;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class HostNativeLocalAbiBridgeCParityTest {
    private static final String METHOD =
            "pkg/Owner#method!(JJJJ)J";
    private static final List<Parameter> SENTINEL_PARAMETERS = List.of(
            new Parameter("uint64_t", "first", "first"),
            new Parameter("uint64_t", "second", "second"),
            new Parameter("uint64_t", "third", "third"),
            new Parameter("uint64_t", "fourth", "fourth"));

    @Test
    void bothBranchedRoutesPreserveCanonicalSentinels(
            @TempDir Path temp) throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(clang != null, "clang is required for local-ABI C parity");
        HostNativeLocalAbiBridgeSource.Emission emission = emissionFor(
                NativeLocalAbiPlan.Shape.BRANCHED_PERMUTING_BRIDGE,
                METHOD,
                "uint64_t",
                "j2ll_target",
                SENTINEL_PARAMETERS);
        String firstRoute = routeCall(emission, 0);
        String secondRoute = routeCall(emission, 1);
        assertTrue(emission.wrapperInvocation().contains(firstRoute));
        assertTrue(emission.wrapperInvocation().contains(secondRoute));

        String source = """
                #include <stdint.h>

                static uint64_t j2ll_target(
                        uint64_t first,
                        uint64_t second,
                        uint64_t third,
                        uint64_t fourth) {
                    if (first != UINT64_C(0x1111111111111111)
                            || second != UINT64_C(0x2222222222222222)
                            || third != UINT64_C(0x3333333333333333)
                            || fourth != UINT64_C(0x4444444444444444)) {
                        return UINT64_C(0);
                    }
                    return UINT64_C(0x5a5a5a5a5a5a5a5a);
                }

                """
                + emission.source()
                + """
                static int run_routes(void) {
                    uint64_t first = UINT64_C(0x1111111111111111);
                    uint64_t second = UINT64_C(0x2222222222222222);
                    uint64_t third = UINT64_C(0x3333333333333333);
                    uint64_t fourth = UINT64_C(0x4444444444444444);
                    uint64_t first_result = %s;
                    uint64_t second_result = %s;
                    return first_result == UINT64_C(0x5a5a5a5a5a5a5a5a)
                            && second_result == UINT64_C(0x5a5a5a5a5a5a5a5a);
                }

                int main(void) {
                    return run_routes() ? 0 : 1;
                }
                """.formatted(firstRoute, secondRoute);

        compileAndRun(clang, temp, "branched-route-parity", source);
    }

    @Test
    void zeroParameterVoidBranchedTopologyCompilesAndRuns(
            @TempDir Path temp) throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(clang != null, "clang is required for local-ABI C parity");
        HostNativeLocalAbiBridgeSource.Emission emission = emissionFor(
                NativeLocalAbiPlan.Shape.BRANCHED_PERMUTING_BRIDGE,
                "pkg/Owner#tick!()V",
                "void",
                "j2ll_target",
                List.of());
        String source = """
                #include <stdint.h>

                static volatile unsigned int j2ll_counter = 0;

                static void j2ll_target(void) {
                    j2ll_counter++;
                }

                """
                + emission.source()
                + """
                static void run_wrapper(void) {
                """
                + emission.wrapperPrelude()
                + "    "
                + emission.wrapperInvocation()
                + ";\n"
                + """
                }

                int main(void) {
                    run_wrapper();
                    return j2ll_counter == 1u ? 0 : 1;
                }
                """;

        compileAndRun(clang, temp, "zero-parameter-void", source);
    }

    @Test
    void branchedOptimizedObjectGrowthIsBounded(
            @TempDir Path temp) throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(clang != null, "clang is required for local-ABI size evidence");
        int sites = 96;
        String direct = batchSource(
                NativeLocalAbiPlan.Shape.DIRECT_CANONICAL,
                sites);
        String branched = batchSource(
                NativeLocalAbiPlan.Shape.BRANCHED_PERMUTING_BRIDGE,
                sites);

        long directBytes = Files.size(
                compileObject(clang, temp, "direct", direct));
        long branchedBytes = Files.size(
                compileObject(clang, temp, "branched", branched));
        long growthBytes = branchedBytes - directBytes;
        long fixedAllowance = 16L * 1024L;
        long perSiteAllowance = 3072L;

        assertTrue(
                growthBytes > 0,
                "branched object should retain bounded extra topology");
        assertTrue(
                growthBytes <= fixedAllowance + (sites * perSiteAllowance),
                "direct="
                        + directBytes
                        + ", branched="
                        + branchedBytes
                        + ", growth="
                        + growthBytes);
    }

    private String batchSource(
            NativeLocalAbiPlan.Shape shape,
            int sites) {
        StringBuilder source = new StringBuilder(
                "#include <stdint.h>\n\n");
        for (int index = 0; index < sites; index++) {
            String target = "j2ll_target_" + index;
            String wrapper = "j2ll_wrapper_" + index;
            String method = "pkg/Owner#method" + index + "!(JJJJ)J";
            HostNativeLocalAbiBridgeSource.Emission emission = emissionFor(
                    shape,
                    method,
                    "uint64_t",
                    target,
                    SENTINEL_PARAMETERS);
            source.append("extern uint64_t ")
                    .append(target)
                    .append(
                            "(uint64_t, uint64_t, uint64_t, uint64_t);\n")
                    .append(emission.source())
                    .append("uint64_t ")
                    .append(wrapper)
                    .append("""
                            (
                                    uint64_t first,
                                    uint64_t second,
                                    uint64_t third,
                                    uint64_t fourth) {
                            """)
                    .append(emission.wrapperPrelude())
                    .append("    return ")
                    .append(emission.wrapperInvocation())
                    .append(";\n}\n\n");
        }
        return source.toString();
    }

    private HostNativeLocalAbiBridgeSource.Emission emissionFor(
            NativeLocalAbiPlan.Shape shape,
            String method,
            String returnType,
            String target,
            List<Parameter> parameters) {
        HostNativeLocalAbiBridgeSource generator =
                new HostNativeLocalAbiBridgeSource();
        for (int index = 0; index < 16_384; index++) {
            HostNativeLocalAbiBridgeSource.Emission emission =
                    generator.emit(
                            NativeTextBuildKey.fromUtf8(
                                    "local-abi-c-parity:"
                                            + shape
                                            + ":"
                                            + method
                                            + ":"
                                            + index),
                            method,
                            returnType,
                            target,
                            parameters);
            if (emission.plan().shape() == shape) {
                return emission;
            }
        }
        throw new AssertionError(
                "could not derive local-ABI shape " + shape);
    }

    private String routeCall(
            HostNativeLocalAbiBridgeSource.Emission emission,
            int route) {
        List<Integer> order =
                emission.plan().parameterOrders().get(route);
        String arguments = String.join(
                ", ",
                order.stream()
                        .map(SENTINEL_PARAMETERS::get)
                        .map(Parameter::wrapperExpression)
                        .toList());
        return emission.plan().bridgeSymbols().get(route)
                + "("
                + arguments
                + ")";
    }

    private void compileAndRun(
            Path clang,
            Path directory,
            String name,
            String source) throws Exception {
        Path cFile = directory.resolve(name + ".c");
        Path executable = directory.resolve(
                isWindows() ? name + ".exe" : name);
        Files.writeString(cFile, source, StandardCharsets.UTF_8);
        Process compile = new ProcessBuilder(
                        clang.toString(),
                        "-std=gnu11",
                        "-O2",
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
                "local-ABI C compile timed out");
        String compileOutput = new String(
                compile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, compile.exitValue(), compileOutput);

        Process run = new ProcessBuilder(executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(
                run.waitFor(30, TimeUnit.SECONDS),
                "local-ABI C parity executable timed out");
        String runOutput = new String(
                run.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, run.exitValue(), runOutput);
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
                        "-ffunction-sections",
                        "-fdata-sections",
                        "-c",
                        cFile.toString(),
                        "-o",
                        objectFile.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(
                compile.waitFor(45, TimeUnit.SECONDS),
                "local-ABI object compile timed out");
        String compileOutput = new String(
                compile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, compile.exitValue(), compileOutput);
        return objectFile;
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
        for (String directory :
                path.split(Pattern.quote(File.pathSeparator))) {
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
