package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class NativeRegistrationControlMachineShapeFixture {
    private final Path temp;

    NativeRegistrationControlMachineShapeFixture(Path temp) {
        this.temp = temp;
    }

    Path realManagedZig() throws Exception {
        Path zig = locateRealZig();
        assumeTrue(
                zig != null && Files.isRegularFile(zig),
                "set -Dj2ll.realZig=<zig 0.15.2 executable> to run the registration machine-shape test");
        ZigCommandResult version = ZigCommandRunner.process().run(
                List.of(zig.toString(), "version"),
                zig.getParent(),
                Map.of());
        assumeTrue(
                version.exitCode() == 0
                        && version.stdout().trim().equals("0.15.2"),
                "the registration machine-shape test requires Zig 0.15.2");
        return zig;
    }

    Path prepareJniHeaders() throws Exception {
        return new ZigJniHeaderSet()
                .prepare(ZigBuildWorkspace.under(temp.resolve("jni")))
                .get(0);
    }

    Path compileAssembly(
            Path zig,
            Path include,
            Path source,
            TargetTriple target,
            String scenario) throws Exception {
        Path assembly = temp.resolve(
                "registration-"
                        + scenario
                        + "-"
                        + target.directoryName()
                        + ".s");
        ArrayList<String> command = new ArrayList<>(List.of(
                zig.toString(),
                "cc",
                "-target",
                target.zigTarget(),
                "-std=gnu11",
                "-Oz",
                "-S",
                "-g0",
                "-fPIC",
                "-fvisibility=hidden",
                "-ffunction-sections",
                "-fdata-sections",
                "-ffile-compilation-dir=.",
                "-fdebug-compilation-dir=.",
                "-Werror=implicit-function-declaration"));
        command.addAll(
                NativeMachineOutlinerPolicy.forSource(
                                target,
                                ZigCInputMachinePolicyPlan.Mode
                                        .REGISTRATION_CONTROL_OUTLINER_FORBIDDEN)
                        .cFlags());
        command.add("-I");
        command.add(include.toString());
        command.add(source.toString());
        command.add("-o");
        command.add(assembly.toString());
        ZigCommandResult compile = ZigCommandRunner.process().run(
                command,
                temp,
                Map.of());
        assertEquals(
                0,
                compile.exitCode(),
                scenario
                        + "/"
                        + target.directoryName()
                        + ": "
                        + compile.stderr());
        return assembly;
    }

    private Path locateRealZig() {
        String configured = System.getProperty("j2ll.realZig");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("J2LL_REAL_ZIG");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String executable = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win")
                ? "zig.exe"
                : "zig";
        return Arrays.stream(path.split(Pattern.quote(File.pathSeparator)))
                .filter(directory -> !directory.isBlank())
                .map(Path::of)
                .map(directory -> directory.resolve(executable))
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
    }
}
