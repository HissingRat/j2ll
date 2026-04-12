package xyz.melodysky.toolchain;

import org.junit.jupiter.api.Test;
import xyz.melodysky.config.BuildTarget;
import xyz.melodysky.zig.ZigWorkspaceEnvironment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

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
        assertTrue(command.contains("-ffile-prefix-map=" + Path.of("build", "ir").toAbsolutePath().normalize().toString().replace('\\', '/') + "=."));
        assertEquals("-shared", command.get(8));
        assertTrue(command.contains("program.ll"));
        assertTrue(command.contains("runtime/ir_runtime_stubs.c"));
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

        assertEquals(List.of("zig", "cc", "-target", "x86_64-windows", "-g0"), command.subList(0, 5));
        assertTrue(command.contains("-ffile-prefix-map=" + Path.of("build", "ir").toAbsolutePath().normalize().toString().replace('\\', '/') + "=."));
        assertTrue(command.contains("llvm-modules/common.ll"));
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

        assertEquals(List.of("zig", "cc", "-target", "x86_64-windows", "-g0"), command.subList(0, 5));
        assertTrue(command.contains("-ffile-prefix-map=" + Path.of("build", "ir").toAbsolutePath().normalize().toString().replace('\\', '/') + "=."));
        assertTrue(command.contains("native-obj/windowsX64/00-common.obj"));
        assertTrue(command.contains("native-obj/windowsX64/runtime.obj"));
    }

    @Test
    public void testCreateZigBuildCommandUsesAbsolutePaths() throws Exception {
        Path workspace = Files.createTempDirectory("ir-native-build-driver-test-");
        IrNativeBuildDriver driver = new IrNativeBuildDriver(workspace);
        Method method = IrNativeBuildDriver.class.getDeclaredMethod(
                "createZigBuildCommand",
                String.class,
                Path.class,
                Path.class,
                BuildTarget.class
        );
        method.setAccessible(true);

        Path outputDirectory = workspace.resolve("native");
        Path buildProjectDirectory = workspace.resolve("zig-build");
        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) method.invoke(
                driver,
                "zig",
                outputDirectory,
                buildProjectDirectory,
                BuildTarget.WINDOWS_X64
        );

        assertEquals(outputDirectory.toAbsolutePath().normalize().toString(), command.get(4));
        assertEquals(buildProjectDirectory.toAbsolutePath().normalize().resolve(".zig-cache").toString(), command.get(6));
        assertEquals(ZigWorkspaceEnvironment.cacheRoot(workspace).resolve("global").toAbsolutePath().normalize().toString(), command.get(8));
    }

    @Test
    public void testCreatesRuntimeObjectCompileCommandWithSanitizedSourceFileName() {
        IrNativeBuildDriver driver = new IrNativeBuildDriver(Path.of("build", "ir"));

        List<String> command = driver.createRuntimeObjectCompileCommand(
                "zig",
                Path.of("build", "ir", "rt00.c"),
                Path.of("build", "ir", "native-obj", "windowsX64", "runtime-00-helper-00.obj"),
                BuildTarget.WINDOWS_X64
        );

        assertTrue(command.containsAll(List.of("-x", "c", "-c")));
        assertTrue(command.contains("rt00.c"));
        assertFalse(command.contains("runtime/helper-00.c"));
        assertTrue(command.contains("native-obj/windowsX64/runtime-00-helper-00.obj"));
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

    @Test
    public void testUsesWorkspaceScopedZigCacheEnvironment() throws Exception {
        Path workspace = Files.createTempDirectory("ir-native-build-driver-test-");
        Map<String, String> environment = new HashMap<>();

        ZigWorkspaceEnvironment.configure(environment, List.of("zig", "cc", "--version"), workspace, isWindows());

        assertEquals(workspace.resolve("zig-cache").resolve("global").toAbsolutePath().toString(),
                environment.get("ZIG_GLOBAL_CACHE_DIR"));
        assertEquals(workspace.resolve("zig-cache").resolve("local").toAbsolutePath().toString(),
                environment.get("ZIG_LOCAL_CACHE_DIR"));
        assertEquals(workspace.resolve("zig-cache").resolve("tmp").toAbsolutePath().toString(),
                environment.get("TMPDIR"));
    }

    @Test
    public void testCleanupIntermediatesRemovesZigCacheAndNativeObj() throws Exception {
        Path workspace = Files.createTempDirectory("ir-native-build-driver-test-");
        Path zigCacheFile = ZigWorkspaceEnvironment.cacheRoot(workspace).resolve("global").resolve("cache.bin");
        Path nativeObjFile = workspace.resolve("native-obj").resolve("windowsX64").resolve("module.obj");
        Files.createDirectories(zigCacheFile.getParent());
        Files.createDirectories(nativeObjFile.getParent());
        Files.writeString(zigCacheFile, "cache", StandardCharsets.UTF_8);
        Files.writeString(nativeObjFile, "object", StandardCharsets.UTF_8);

        new IrNativeBuildDriver(workspace).cleanupIntermediates();

        assertTrue(Files.notExists(ZigWorkspaceEnvironment.cacheRoot(workspace)));
        assertTrue(Files.notExists(workspace.resolve("native-obj")));
    }

    @Test
    public void testDebugBuildKeepsIntermediates() throws Exception {
        Path workspace = Files.createTempDirectory("ir-native-build-driver-test-");
        Path zigCacheFile = ZigWorkspaceEnvironment.cacheRoot(workspace).resolve("global").resolve("cache.bin");
        Path nativeObjFile = workspace.resolve("native-obj").resolve("windowsX64").resolve("module.obj");
        Files.createDirectories(zigCacheFile.getParent());
        Files.createDirectories(nativeObjFile.getParent());
        Files.writeString(zigCacheFile, "cache", StandardCharsets.UTF_8);
        Files.writeString(nativeObjFile, "object", StandardCharsets.UTF_8);

        new IrNativeBuildDriver(workspace, true).cleanupIntermediatesIfNeeded();

        assertTrue(Files.exists(zigCacheFile));
        assertTrue(Files.exists(nativeObjFile));
    }

    @Test
    public void testPrepareZigBuildProjectUsesStripAndRelativeRuntimeSources() throws Exception {
        Path workspace = Files.createTempDirectory("ir-native-build-driver-test-");
        Path runtimeDirectory = workspace.resolve("runtime");
        Path nativeObjDirectory = workspace.resolve("native-obj").resolve("windowsX64");
        Path linuxObjDirectory = workspace.resolve("native-obj").resolve("linuxX64");
        Files.createDirectories(runtimeDirectory);
        Files.createDirectories(nativeObjDirectory);
        Files.createDirectories(linuxObjDirectory);
        Path commonFile = runtimeDirectory.resolve("common.c");
        Path helperFile = runtimeDirectory.resolve("helper-00.c");
        Files.writeString(commonFile, "int common(void){return 1;}", StandardCharsets.UTF_8);
        Files.writeString(helperFile, "int helper(void){return 2;}", StandardCharsets.UTF_8);
        Files.writeString(runtimeDirectory.resolve("ir_runtime_stubs.c"), "int duplicate(void){return 3;}", StandardCharsets.UTF_8);
        Path windowsObj = nativeObjDirectory.resolve("00-common.obj");
        Path linuxObj = linuxObjDirectory.resolve("00-common.o");
        Files.writeString(windowsObj, "obj", StandardCharsets.UTF_8);
        Files.writeString(linuxObj, "obj", StandardCharsets.UTF_8);

        IrNativeBuildDriver driver = new IrNativeBuildDriver(workspace);
        Object windowsState = createTargetBuildState(
                BuildTarget.WINDOWS_X64,
                workspace.resolve("native").resolve("x64-windows.dll"),
                workspace.resolve("logs").resolve("zig-build-windowsX64.log"),
                windowsObj
        );
        Object linuxState = createTargetBuildState(
                BuildTarget.LINUX_X64,
                workspace.resolve("native").resolve("x64-linux.so"),
                workspace.resolve("logs").resolve("zig-build-linuxX64.log"),
                linuxObj
        );
        Method prepareMethod = IrNativeBuildDriver.class.getDeclaredMethod(
                "prepareZigBuildProject",
                Path.class,
                List.class,
                List.class
        );
        prepareMethod.setAccessible(true);
        Path projectDirectory = (Path) prepareMethod.invoke(
                driver,
                workspace.resolve("native"),
                List.of(windowsState, linuxState),
                List.of(commonFile, helperFile)
        );

        String buildText = Files.readString(projectDirectory.resolve("build.zig"), StandardCharsets.UTF_8);
        assertTrue(buildText.contains(".strip = true"));
        assertTrue(buildText.contains("\"common.c\""));
        assertTrue(buildText.contains("\"helper-00.c\""));
        assertFalse(buildText.contains("\"ir_runtime_stubs.c\""));
        assertTrue(buildText.contains("const target_windowsX64"));
        assertTrue(buildText.contains("const target_linuxX64"));
        assertTrue(buildText.contains("artifact_windowsX64"));
        assertTrue(buildText.contains("artifact_linuxX64"));
        assertTrue(buildText.contains("const step_windowsX64 = b.step(\"windowsX64\""));
        assertTrue(buildText.contains("const step_linuxX64 = b.step(\"linuxX64\""));
        assertTrue(buildText.contains("mod_windowsX64.addObjectFile"));
        assertTrue(buildText.contains("mod_linuxX64.addObjectFile"));
        assertTrue(buildText.contains(".implib_dir = .disabled"));
    }

    private Object createTargetBuildState(BuildTarget target, Path libraryFile, Path logFile, Path objectFile) throws Exception {
        Class<?> compileUnitClass = Class.forName("xyz.melodysky.toolchain.IrNativeBuildDriver$CompileUnit");
        var compileUnitConstructor = compileUnitClass.getDeclaredConstructor(String.class, Path.class, List.class);
        compileUnitConstructor.setAccessible(true);
        Object compileUnit = compileUnitConstructor.newInstance("llvm[" + objectFile.getFileName() + "]", objectFile, List.of("zig", "cc"));

        Class<?> compileBatchClass = Class.forName("xyz.melodysky.toolchain.IrNativeBuildDriver$CompileBatchResult");
        var compileBatchConstructor = compileBatchClass.getDeclaredConstructor(LinkedHashMap.class, compileUnitClass);
        compileBatchConstructor.setAccessible(true);
        Object compileBatch = compileBatchConstructor.newInstance(new LinkedHashMap<>(), null);

        Class<?> targetBuildStateClass = Class.forName("xyz.melodysky.toolchain.IrNativeBuildDriver$TargetBuildState");
        var targetBuildStateConstructor = targetBuildStateClass.getDeclaredConstructor(
                BuildTarget.class,
                Path.class,
                Path.class,
                List.class,
                compileBatchClass,
                int.class,
                int.class,
                long.class
        );
        targetBuildStateConstructor.setAccessible(true);
        return targetBuildStateConstructor.newInstance(
                target,
                libraryFile,
                logFile,
                List.of(compileUnit),
                compileBatch,
                1,
                2,
                10L
        );
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

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
