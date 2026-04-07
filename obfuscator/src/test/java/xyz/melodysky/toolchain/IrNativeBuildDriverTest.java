package xyz.melodysky.toolchain;

import org.junit.jupiter.api.Test;
import xyz.melodysky.config.BuildTarget;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IrNativeBuildDriverTest {

    @Test
    public void testCreatesExpectedSingleStepCompileCommand() {
        IrNativeBuildDriver driver = new IrNativeBuildDriver(Path.of("build", "ir"));

        List<String> command = driver.createCompileCommand(
                "zig",
                Path.of("build", "ir", "program.ll"),
                Path.of("build", "ir", "runtime", "ir_runtime_stubs.c"),
                Path.of("build", "ir", "native", "x64-windows.dll"),
                BuildTarget.WINDOWS_X64
        );

        assertEquals("zig", command.get(0));
        assertEquals("cc", command.get(1));
        assertEquals("-target", command.get(2));
        assertEquals("x86_64-windows", command.get(3));
        assertEquals("-g0", command.get(4));
        assertEquals("-shared", command.get(5));
        assertTrue(command.contains(Path.of("build", "ir", "program.ll").toAbsolutePath().toString()));
        assertTrue(command.contains(Path.of("build", "ir", "runtime", "ir_runtime_stubs.c").toAbsolutePath().toString()));
    }

    @Test
    public void testCreatesExpectedLlvmObjectCompileCommand() {
        IrNativeBuildDriver driver = new IrNativeBuildDriver(Path.of("build", "ir"));

        List<String> command = driver.createLlvmObjectCompileCommand(
                "zig",
                Path.of("build", "ir", "llvm-modules", "common.ll"),
                Path.of("build", "ir", "native-obj", "windowsX64", "00-common.obj"),
                BuildTarget.WINDOWS_X64
        );

        assertEquals(List.of("zig", "cc", "-target", "x86_64-windows", "-g0", "-c"), command.subList(0, 6));
        assertTrue(command.contains(Path.of("build", "ir", "llvm-modules", "common.ll").toAbsolutePath().toString()));
    }

    @Test
    public void testCreatesExpectedLinkCommand() {
        IrNativeBuildDriver driver = new IrNativeBuildDriver(Path.of("build", "ir"));

        List<String> command = driver.createLinkCommand(
                "zig",
                List.of(
                        Path.of("build", "ir", "native-obj", "windowsX64", "00-common.obj"),
                        Path.of("build", "ir", "native-obj", "windowsX64", "runtime.obj")
                ),
                Path.of("build", "ir", "native", "x64-windows.dll"),
                BuildTarget.WINDOWS_X64
        );

        assertEquals(List.of("zig", "cc", "-target", "x86_64-windows", "-g0", "-shared", "-s"), command.subList(0, 7));
        assertTrue(command.contains(Path.of("build", "ir", "native-obj", "windowsX64", "00-common.obj").toAbsolutePath().toString()));
        assertTrue(command.contains(Path.of("build", "ir", "native-obj", "windowsX64", "runtime.obj").toAbsolutePath().toString()));
    }

    @Test
    public void testMapsTargetToExpectedLibraryName() {
        IrNativeBuildDriver driver = new IrNativeBuildDriver(Path.of("build", "ir"));

        assertEquals("x64-windows.dll", driver.outputFileName(BuildTarget.WINDOWS_X64));
        assertEquals("arm64-linux.so", driver.outputFileName(BuildTarget.LINUX_ARM64));
        assertEquals("arm64-macos.dylib", driver.outputFileName(BuildTarget.MACOS_ARM64));
    }
}
