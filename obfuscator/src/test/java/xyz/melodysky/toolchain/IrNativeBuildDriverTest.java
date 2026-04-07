package xyz.melodysky.toolchain;

import org.junit.jupiter.api.Test;
import xyz.melodysky.config.BuildTarget;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    public void testRunStopsPromptlyWhenWorkerThreadIsInterrupted() throws Exception {
        Path workspace = Files.createTempDirectory("ir-native-build-driver-test-");
        IrNativeBuildDriver driver = new IrNativeBuildDriver(workspace);
        Path pidFile = workspace.resolve("interruptible-compile.pid");
        List<String> command = createSleepingJavaCommand(workspace, "InterruptibleCompile", pidFile);
        Method runMethod = IrNativeBuildDriver.class.getDeclaredMethod("run", List.class);
        runMethod.setAccessible(true);

        AtomicReference<Throwable> failureRef = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                runMethod.invoke(driver, command);
            } catch (InvocationTargetException exception) {
                failureRef.set(exception.getCause());
            } catch (Throwable throwable) {
                failureRef.set(throwable);
            }
        }, "ir-native-build-driver-interrupt-test");

        long startNanos = System.nanoTime();
        worker.start();
        long childPid = waitForPid(pidFile);
        worker.interrupt();
        worker.join(5000L);

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        assertFalse(worker.isAlive(), "expected compile worker to stop promptly after interruption");
        assertTrue(elapsedMillis < 5000L, "expected interrupted compile to stop within timeout");

        Throwable failure = failureRef.get();
        assertNotNull(failure, "expected interrupted compile to report cancellation");
        assertTrue(failure instanceof InterruptedException,
                "expected InterruptedException but was: " + failure);
        assertFalse(isProcessAlive(childPid), "expected child compiler process to be terminated");
    }

    private List<String> createSleepingJavaCommand(Path workspace, String className, Path pidFile) throws Exception {
        Path sourceFile = workspace.resolve(className + ".java");
        Files.writeString(sourceFile, """
                public class %s {
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Files.writeString(
                                java.nio.file.Path.of(args[0]),
                                Long.toString(ProcessHandle.current().pid()),
                                java.nio.charset.StandardCharsets.UTF_8
                        );
                        System.out.println("started");
                        Thread.sleep(30000L);
                    }
                }
                """.formatted(className), StandardCharsets.UTF_8);

        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path javac = Path.of(javaHome, "bin", windows ? "javac.exe" : "javac");
        Path java = Path.of(javaHome, "bin", windows ? "java.exe" : "java");

        Process compile = new ProcessBuilder(javac.toString(), sourceFile.getFileName().toString())
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start();
        String compilerOutput = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (compile.waitFor() != 0) {
            throw new IllegalStateException("Failed to compile sleeping helper: " + compilerOutput);
        }

        return List.of(java.toString(), "-cp", workspace.toString(), className, pidFile.toString());
    }

    private long waitForPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                String text = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    return Long.parseLong(text);
                }
            }
            Thread.sleep(50L);
        }
        throw new IllegalStateException("Timed out waiting for child pid file: " + pidFile);
    }

    private boolean isProcessAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
