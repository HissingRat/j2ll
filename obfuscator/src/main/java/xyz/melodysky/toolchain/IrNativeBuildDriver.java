package xyz.melodysky.toolchain;

import xyz.melodysky.config.BuildTarget;
import xyz.melodysky.process.SubprocessRegistry;
import xyz.melodysky.zig.ZigWorkspaceEnvironment;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class IrNativeBuildDriver {

    private static final int MAX_CAPTURE_BYTES = 1_048_576;

    private final Path workspaceDirectory;
    private final boolean preserveIntermediates;
    private static final ProgressListener NO_PROGRESS = new ProgressListener() {};

    public interface ProgressListener {
        default void onTargetStart(BuildTarget target, int totalUnits) {}
        default void onCompileProgress(BuildTarget target, int completedUnits, int totalUnits, String unitLabel) {}
        default void onLinkStart(BuildTarget target) {}
        default void onTargetComplete(BuildTarget target, BuildTiming timing) {}
    }

    public IrNativeBuildDriver(Path workspaceDirectory) {
        this(workspaceDirectory, false);
    }

    public IrNativeBuildDriver(Path workspaceDirectory, boolean preserveIntermediates) {
        this.workspaceDirectory = workspaceDirectory;
        this.preserveIntermediates = preserveIntermediates;
    }

    public BuildResult build(String zigCommand, Path llvmFile, Path runtimeStubFile, List<BuildTarget> targets) throws Exception {
        return build(zigCommand, List.of(llvmFile), List.of(runtimeStubFile), targets);
    }

    public BuildResult build(String zigCommand, List<Path> llvmModuleFiles, Path runtimeStubFile, List<BuildTarget> targets) throws Exception {
        return build(zigCommand, llvmModuleFiles, List.of(runtimeStubFile), targets);
    }

    public BuildResult build(String zigCommand, List<Path> llvmModuleFiles, List<Path> runtimeSourceFiles, List<BuildTarget> targets) throws Exception {
        return build(zigCommand, llvmModuleFiles, runtimeSourceFiles, targets, NO_PROGRESS);
    }

    public BuildResult build(String zigCommand, List<Path> llvmModuleFiles, List<Path> runtimeSourceFiles,
                             List<BuildTarget> targets, ProgressListener progressListener) throws Exception {
        Path outputDirectory = workspaceDirectory.resolve("native");
        Path logsDirectory = workspaceDirectory.resolve("logs");
        Files.createDirectories(outputDirectory);
        Files.createDirectories(logsDirectory);

        ArrayList<BuildArtifact> artifacts = new ArrayList<>();
        try {
            ArrayList<TargetBuildState> targetStates = new ArrayList<>();
            for (BuildTarget target : targets) {
                Path libraryFile = outputDirectory.resolve(outputFileName(target));
                Path logFile = logsDirectory.resolve("zig-build-" + target.getConfigKey() + ".log");
                targetStates.add(compileTarget(
                        zigCommand,
                        llvmModuleFiles,
                        runtimeSourceFiles,
                        libraryFile,
                        target,
                        logFile,
                        progressListener
                ));
            }

            Path buildProjectDirectory = prepareZigBuildProject(outputDirectory, targetStates, runtimeSourceFiles);
            for (TargetBuildState targetState : targetStates) {
                List<String> linkCommand = createZigBuildCommand(zigCommand, outputDirectory, buildProjectDirectory, targetState.target());
                progressListener.onLinkStart(targetState.target());
                long linkStartNanos = System.nanoTime();
                CommandResult linkResult = runInWorkingDirectory(linkCommand, buildProjectDirectory);
                long linkEndNanos = System.nanoTime();
                long linkMillis = nanosToMillis(linkEndNanos - linkStartNanos);
                BuildTiming timing = new BuildTiming(
                        targetState.llvmShardCount(),
                        targetState.runtimeSourceCount(),
                        targetState.compileMillis(),
                        linkMillis,
                        targetState.compileMillis() + linkMillis
                );
                writeTargetLog(targetState, linkCommand, linkResult.output(), timing);
                if (linkResult.exitCode() != 0) {
                    throw new IOException("Failed to build IR native artifact for command: " + renderCommand(linkCommand)
                            + System.lineSeparator() + "See log: " + targetState.logFile().toAbsolutePath());
                }
                progressListener.onTargetComplete(targetState.target(), timing);
                artifacts.add(new BuildArtifact(targetState.target(), targetState.libraryFile(), targetState.logFile(), timing));
            }
        } finally {
            cleanupIntermediatesIfNeeded();
        }

        return new BuildResult(outputDirectory, List.copyOf(artifacts));
    }

    List<String> createCompileCommand(String zigCommand, Path llvmFile, Path runtimeStubFile,
                                      Path outputFile, BuildTarget target) {
        return createLinkCommand(
                zigCommand,
                List.of(llvmFile.toAbsolutePath(), runtimeStubFile.toAbsolutePath()),
                outputFile,
                target
        );
    }

    List<String> createLlvmObjectCompileCommand(String zigCommand, Path llvmModuleFile, Path outputFile, BuildTarget target) {
        ArrayList<String> command = new ArrayList<>();
        command.add(zigCommand);
        command.add("cc");
        command.add("-target");
        command.add(target.getZigTarget());
        command.add("-g0");
        command.addAll(createPathSanitizingFlags());
        if (requiresPic(target)) {
            command.add("-fPIC");
        }
        command.add("-c");
        command.add(commandPath(llvmModuleFile));
        command.add("-o");
        command.add(commandPath(outputFile));
        return List.copyOf(command);
    }

    List<String> createRuntimeObjectCompileCommand(String zigCommand, Path runtimeStubFile, Path outputFile, BuildTarget target) {
        ArrayList<String> command = new ArrayList<>();
        Path jniHeadersDirectory = ensureBundledJniHeaders(target);
        command.add(zigCommand);
        command.add("cc");
        command.add("-target");
        command.add(target.getZigTarget());
        command.add("-g0");
        command.addAll(createPathSanitizingFlags());
        if (requiresPic(target)) {
            command.add("-fPIC");
        }
        command.add("-x");
        command.add("c");
        command.add("-c");
        command.add("-I");
        command.add(commandPath(jniHeadersDirectory));
        command.add("-I");
        command.add(commandPath(jniHeadersDirectory.resolve(target.getJniHeaderSubdir())));
        command.add(commandPath(runtimeStubFile));
        command.add("-o");
        command.add(commandPath(outputFile));
        return List.copyOf(command);
    }

    private boolean requiresPic(BuildTarget target) {
        return switch (target) {
            case LINUX_X64, LINUX_ARM64, MACOS_X64, MACOS_ARM64 -> true;
            case WINDOWS_X64, WINDOWS_ARM64 -> false;
        };
    }

    List<String> createLinkCommand(String zigCommand, List<Path> objectFiles, Path outputFile, BuildTarget target) {
        ArrayList<String> command = new ArrayList<>();
        command.add(zigCommand);
        command.add("cc");
        command.add("-target");
        command.add(target.getZigTarget());
        command.add("-g0");
        command.addAll(createPathSanitizingFlags());
        command.add("-shared");
        command.add("-s");
        for (Path objectFile : objectFiles) {
            command.add(commandPath(objectFile));
        }
        command.add("-o");
        command.add(commandPath(outputFile));
        return List.copyOf(command);
    }

    String outputFileName(BuildTarget target) {
        String arch = switch (target) {
            case WINDOWS_X64, LINUX_X64, MACOS_X64 -> "x64";
            case WINDOWS_ARM64, LINUX_ARM64, MACOS_ARM64 -> "arm64";
        };

        String suffix = switch (target) {
            case WINDOWS_X64, WINDOWS_ARM64 -> "windows.dll";
            case LINUX_X64, LINUX_ARM64 -> "linux.so";
            case MACOS_X64, MACOS_ARM64 -> "macos.dylib";
        };

        return arch + "-" + suffix;
    }

    private Path ensureBundledJniHeaders(BuildTarget target) {
        Path includeDirectory = workspaceDirectory.resolve("jni-headers");
        writeResource("/jni-headers/jni.h", includeDirectory.resolve("jni.h"));
        writeResource(
                "/jni-headers/" + target.getJniHeaderSubdir() + "/jni_md.h",
                includeDirectory.resolve(target.getJniHeaderSubdir()).resolve("jni_md.h")
        );
        return includeDirectory;
    }

    private void writeResource(String resourcePath, Path outputPath) {
        try {
            Files.createDirectories(outputPath.toAbsolutePath().getParent());
            try (InputStream input = IrNativeBuildDriver.class.getResourceAsStream(resourcePath)) {
                if (input == null) {
                    throw new IllegalStateException("Missing bundled resource: " + resourcePath);
                }
                Files.write(outputPath, input.readAllBytes());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to materialize bundled resource: " + resourcePath, exception);
        }
    }

    private TargetBuildState compileTarget(String zigCommand, List<Path> llvmModuleFiles, List<Path> runtimeSourceFiles,
                                           Path outputFile, BuildTarget target, Path logFile,
                                           ProgressListener progressListener) throws Exception {
        Path objectDirectory = workspaceDirectory.resolve("native-obj").resolve(target.getConfigKey());
        Files.createDirectories(objectDirectory);

        ArrayList<CompileUnit> compileUnits = new ArrayList<>();
        int llvmCompileUnitCount = 0;
        for (int index = 0; index < llvmModuleFiles.size(); index++) {
            Path llvmModuleFile = llvmModuleFiles.get(index);
            Path objectFile = objectDirectory.resolve(moduleObjectName(index, llvmModuleFile, target));
            compileUnits.add(new CompileUnit(
                    "llvm[" + llvmModuleFile.getFileName() + "]",
                    objectFile,
                    createLlvmObjectCompileCommand(zigCommand, llvmModuleFile, objectFile, target)
            ));
            llvmCompileUnitCount++;
        }
        int runtimeCompileUnitCount = runtimeSourceFiles.size();

        progressListener.onTargetStart(target, compileUnits.size());
        long compileStartNanos = System.nanoTime();
        CompileBatchResult compileBatchResult = runParallelCompiles(compileUnits, target, progressListener);
        long compileEndNanos = System.nanoTime();

        if (compileBatchResult.failedUnit() != null) {
            Files.writeString(logFile, renderCompileLog(compileUnits, compileBatchResult), StandardCharsets.UTF_8);
            throw new IOException("Failed to build IR native artifact for command: "
                    + renderCommand(compileBatchResult.failedUnit().command())
                    + System.lineSeparator() + "See log: " + logFile.toAbsolutePath());
        }
        return new TargetBuildState(
                target,
                outputFile,
                logFile,
                List.copyOf(compileUnits),
                compileBatchResult,
                llvmCompileUnitCount,
                runtimeCompileUnitCount,
                nanosToMillis(compileEndNanos - compileStartNanos)
        );
    }

    private void writeTargetLog(TargetBuildState targetState, List<String> linkCommand, String linkOutput,
                                BuildTiming timing) throws IOException {
        StringBuilder logContent = new StringBuilder(renderCompileLog(targetState.compileUnits(), targetState.compileBatchResult()));
        logContent.append("$ ").append(renderCommand(linkCommand)).append(System.lineSeparator());
        logContent.append(linkOutput);
        logContent.append(System.lineSeparator())
                .append("== timing ==")
                .append(System.lineSeparator())
                .append("llvm module compiles: ")
                .append(timing.llvmShardCount())
                .append(System.lineSeparator())
                .append("runtime source compiles: ")
                .append(timing.runtimeSourceCount())
                .append(System.lineSeparator())
                .append("compile phase ms: ")
                .append(timing.compileMillis())
                .append(System.lineSeparator())
                .append("link phase ms: ")
                .append(timing.linkMillis())
                .append(System.lineSeparator())
                .append("total ms: ")
                .append(timing.totalMillis())
                .append(System.lineSeparator());
        Files.writeString(targetState.logFile(), logContent.toString(), StandardCharsets.UTF_8);
    }

    private String renderCompileLog(List<CompileUnit> compileUnits, CompileBatchResult compileBatchResult) {
        StringBuilder logContent = new StringBuilder();
        for (CompileUnit unit : compileUnits) {
            logContent.append("$ ").append(renderCommand(unit.command())).append(System.lineSeparator());
            logContent.append(compileBatchResult.outputByUnit().getOrDefault(unit.label(), ""));
        }
        return logContent.toString();
    }

    private CompileBatchResult runParallelCompiles(List<CompileUnit> compileUnits, BuildTarget target,
                                                   ProgressListener progressListener) throws Exception {
        int threadCount = Math.max(1, Math.min(compileUnits.size(), Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            ArrayList<Callable<CompileOutcome>> tasks = new ArrayList<>(compileUnits.size());
            for (CompileUnit unit : compileUnits) {
                tasks.add(() -> new CompileOutcome(unit, runCompileUnit(unit)));
            }
            ExecutorCompletionService<CompileOutcome> completionService =
                    new ExecutorCompletionService<>(executor);
            for (Callable<CompileOutcome> task : tasks) {
                completionService.submit(task);
            }
            ArrayList<CompileOutcome> outcomes = new ArrayList<>(compileUnits.size());
            for (int completed = 1; completed <= compileUnits.size(); completed++) {
                Future<CompileOutcome> future = waitForCompletedCompile(completionService);
                outcomes.add(future.get());
                progressListener.onCompileProgress(target, completed, compileUnits.size(),
                        outcomes.getLast().unit().label());
            }
            outcomes.sort(Comparator.comparingInt(outcome -> compileUnits.indexOf(outcome.unit())));

            LinkedHashMap<String, String> outputByUnit = new LinkedHashMap<>();
            CompileUnit failedUnit = null;
            for (CompileOutcome outcome : outcomes) {
                outputByUnit.put(outcome.unit().label(), outcome.result().output());
                if (failedUnit == null && outcome.result().exitCode() != 0) {
                    failedUnit = outcome.unit();
                }
            }
            return new CompileBatchResult(outputByUnit, failedUnit);
        } finally {
            executor.shutdownNow();
        }
    }

    private void run(List<String> command, Path logFile) throws Exception {
        CommandResult result = run(command);
        String logContent = "$ " + renderCommand(command) + System.lineSeparator() + result.output();
        Files.writeString(logFile, logContent, StandardCharsets.UTF_8);
        if (result.exitCode() != 0) {
            throw new IOException("Failed to build IR native artifact for command: " + renderCommand(command)
                    + System.lineSeparator() + "See log: " + logFile.toAbsolutePath());
        }
    }

    private CommandResult runCompileUnit(CompileUnit unit) throws Exception {
        return run(unit.command());
    }

    private CommandResult run(List<String> command) throws Exception {
        return runInWorkingDirectory(command, workspaceDirectory);
    }

    private CommandResult runInWorkingDirectory(List<String> command, Path workingDirectory) throws Exception {
        return run(command, workingDirectory, null);
    }

    private CommandResult run(List<String> command, String stdinText) throws Exception {
        return run(command, workspaceDirectory, stdinText);
    }

    private CommandResult run(List<String> command, Path workingDirectory, String stdinText) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        ZigWorkspaceEnvironment.configure(
                builder.environment(),
                command,
                workspaceDirectory,
                System.getProperty("os.name", "").toLowerCase().contains("win")
        );

        Process process = builder.start();
        boolean completed = false;
        AtomicReference<String> outputRef = new AtomicReference<>("");
        AtomicReference<IOException> outputFailureRef = new AtomicReference<>();
        AtomicReference<IOException> inputFailureRef = new AtomicReference<>();
        Thread outputReader = new Thread(() -> {
            try (var input = process.getInputStream()) {
                outputRef.set(readProcessOutput(input));
            } catch (IOException exception) {
                outputFailureRef.set(exception);
            }
        }, "ir-native-build-output");
        outputReader.setDaemon(true);
        outputReader.start();
        Thread inputWriter = new Thread(() -> {
            try (var output = process.getOutputStream()) {
                if (stdinText != null) {
                    output.write(stdinText.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException exception) {
                inputFailureRef.set(exception);
            }
        }, "ir-native-build-input");
        inputWriter.setDaemon(true);
        inputWriter.start();
        SubprocessRegistry.Registration registration = SubprocessRegistry.register(process);
        try {
            while (true) {
                throwIfCancellationRequested();
                if (process.waitFor(100L, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }

            joinInputWriter(inputWriter);
            String output = awaitCollectedOutput(outputReader, outputRef, outputFailureRef);
            int exitCode = process.exitValue();
            completed = true;
            registration.close();
            IOException inputFailure = inputFailureRef.get();
            if (inputFailure != null && exitCode != 0) {
                output = output + System.lineSeparator() + "[stdin write failed: " + inputFailure.getMessage() + "]";
            }
            return new CommandResult(exitCode, output);
        } finally {
            if (!completed) {
                SubprocessRegistry.destroyProcessTree(process);
                registration.close();
            }
            joinThreadQuietly(inputWriter);
            joinOutputReaderQuietly(outputReader);
        }
    }

    private Future<CompileOutcome> waitForCompletedCompile(ExecutorCompletionService<CompileOutcome> completionService)
            throws Exception {
        while (true) {
            throwIfCancellationRequested();
            Future<CompileOutcome> completedFuture = completionService.poll(100L, TimeUnit.MILLISECONDS);
            if (completedFuture != null) {
                return completedFuture;
            }
        }
    }

    private void throwIfCancellationRequested() throws InterruptedException {
        if (SubprocessRegistry.isShutdownRequested() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Native build cancelled");
        }
    }

    private String awaitCollectedOutput(Thread outputReader, AtomicReference<String> outputRef,
                                        AtomicReference<IOException> outputFailureRef) throws Exception {
        while (outputReader.isAlive()) {
            throwIfCancellationRequested();
            outputReader.join(100L);
        }
        IOException outputFailure = outputFailureRef.get();
        if (outputFailure != null) {
            throw outputFailure;
        }
        return outputRef.get();
    }

    private void joinOutputReaderQuietly(Thread outputReader) {
        joinThreadQuietly(outputReader);
    }

    private void joinInputWriter(Thread inputWriter) throws InterruptedException {
        while (inputWriter.isAlive()) {
            throwIfCancellationRequested();
            inputWriter.join(100L);
        }
    }

    private void joinThreadQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join(100L);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String readProcessOutput(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int remaining = MAX_CAPTURE_BYTES - output.size();
            if (remaining > 0) {
                output.write(buffer, 0, Math.min(read, remaining));
            }
            if (read > remaining) {
                truncated = true;
            }
        }
        String text = output.toString(StandardCharsets.UTF_8);
        if (truncated) {
            return text + System.lineSeparator() + "[output truncated]";
        }
        return text;
    }

    private String renderCommand(List<String> command) {
        ArrayList<String> rendered = new ArrayList<>(command.size());
        for (String part : command) {
            rendered.add(quoteIfNeeded(part));
        }
        return String.join(" ", rendered);
    }

    private String quoteIfNeeded(String part) {
        if (part.indexOf(' ') < 0 && part.indexOf('\t') < 0) {
            return part;
        }
        return "\"" + part.replace("\"", "\\\"") + "\"";
    }

    private List<String> createPathSanitizingFlags() {
        String workspacePath = workspaceDirectory.toAbsolutePath().normalize().toString().replace('\\', '/');
        return List.of(
                "-ffile-prefix-map=" + workspacePath + "=.",
                "-fdebug-prefix-map=" + workspacePath + "=.",
                "-fmacro-prefix-map=" + workspacePath + "=."
        );
    }

    private String commandPath(Path path) {
        Path absolutePath = path.toAbsolutePath().normalize();
        Path workspacePath = workspaceDirectory.toAbsolutePath().normalize();
        try {
            if (absolutePath.startsWith(workspacePath)) {
                return workspacePath.relativize(absolutePath).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
        }
        return absolutePath.toString();
    }

    private List<String> createZigBuildCommand(String zigCommand, Path outputDirectory, Path buildProjectDirectory,
                                               BuildTarget target) {
        Path cacheRoot = ZigWorkspaceEnvironment.cacheRoot(workspaceDirectory);
        Path absoluteOutputDirectory = outputDirectory.toAbsolutePath().normalize();
        Path absoluteBuildProjectDirectory = buildProjectDirectory.toAbsolutePath().normalize();
        Path absoluteGlobalCacheDirectory = cacheRoot.resolve("global").toAbsolutePath().normalize();
        ArrayList<String> command = new ArrayList<>();
        command.add(zigCommand);
        command.add("build");
        command.add(target.getConfigKey());
        command.add("--prefix");
        command.add(absoluteOutputDirectory.toString());
        command.add("--cache-dir");
        command.add(absoluteBuildProjectDirectory.resolve(".zig-cache").toString());
        command.add("--global-cache-dir");
        command.add(absoluteGlobalCacheDirectory.toString());
        return List.copyOf(command);
    }

    private Path prepareZigBuildProject(Path outputDirectory, List<TargetBuildState> targetStates,
                                        List<Path> runtimeSourceFiles) throws IOException {
        Path buildProjectDirectory = workspaceDirectory.resolve("zig-build");
        Files.createDirectories(buildProjectDirectory);
        Path buildFile = buildProjectDirectory.resolve("build.zig");
        Files.writeString(
                buildFile,
                createZigBuildFileText(outputDirectory, buildProjectDirectory, targetStates, runtimeSourceFiles),
                StandardCharsets.UTF_8
        );
        return buildProjectDirectory;
    }

    private String createZigBuildFileText(Path outputDirectory, Path buildProjectDirectory,
                                          List<TargetBuildState> targetStates, List<Path> runtimeSourceFiles) {
        String runtimeFiles = runtimeSourceFiles.stream()
                .map(path -> quoteZigString(path.getFileName().toString()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String pathFlags = createPathSanitizingFlags().stream()
                .map(this::quoteZigString)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String targetBlocks = targetStates.stream()
                .map(targetState -> createZigTargetBlock(targetState, buildProjectDirectory, runtimeFiles, pathFlags))
                .reduce((left, right) -> left + System.lineSeparator() + System.lineSeparator() + right)
                .orElse("");
        return """
                const std = @import("std");

                pub fn build(b: *std.Build) void {
                %s
                }
                """.formatted(indentBlock(targetBlocks, 4));
    }

    private String createZigTargetBlock(TargetBuildState targetState, Path buildProjectDirectory,
                                        String runtimeFiles, String pathFlags) {
        String symbol = targetState.target().getConfigKey();
        Path jniHeadersDirectory = ensureBundledJniHeaders(targetState.target());
        String includeDir = quoteZigString(relativeTo(buildProjectDirectory, jniHeadersDirectory));
        String includePlatformDir = quoteZigString(relativeTo(buildProjectDirectory, jniHeadersDirectory.resolve(targetState.target().getJniHeaderSubdir())));
        String outputName = quoteZigString(targetState.libraryFile().getFileName().toString());
        String arch = quoteZigEnum(zigCpuArch(targetState.target()));
        String os = quoteZigEnum(zigOsTag(targetState.target()));
        String objectFileLines = targetState.compileUnits().stream()
                .map(CompileUnit::objectFile)
                .map(path -> "mod_" + symbol + ".addObjectFile(b.path(" + quoteZigString(relativeTo(buildProjectDirectory, path)) + "));")
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
        String macosDiscardLine = targetState.target().name().startsWith("MACOS")
                ? "lib_" + symbol + ".discard_local_symbols = true;" + System.lineSeparator()
                : "";
        String implibDirLine = targetState.target().name().startsWith("WINDOWS")
                ? "    .implib_dir = .disabled," + System.lineSeparator()
                : "";
        return """
                const target_%s = b.resolveTargetQuery(.{ .cpu_arch = %s, .os_tag = %s });
                const mod_%s = b.createModule(.{
                    .target = target_%s,
                    .optimize = .ReleaseSafe,
                    .strip = true,
                    .link_libc = true,
                });
                const lib_%s = b.addLibrary(.{
                    .linkage = .dynamic,
                    .name = %s,
                    .root_module = mod_%s,
                });
                %s
                mod_%s.addIncludePath(b.path(%s));
                mod_%s.addIncludePath(b.path(%s));
                mod_%s.addCSourceFiles(.{
                    .root = b.path(%s),
                    .files = &.{ %s },
                    .language = .c,
                    .flags = &.{ "-g0", "-ffile-compilation-dir=.", "-fdebug-compilation-dir=.", %s },
                });
                %s
                const artifact_%s = b.addInstallArtifact(lib_%s, .{
                    .dest_dir = .{ .override = .prefix },
                %s
                    .dest_sub_path = %s,
                });
                const step_%s = b.step(%s, %s);
                step_%s.dependOn(&artifact_%s.step);
                """.formatted(
                symbol,
                arch,
                os,
                symbol,
                symbol,
                symbol,
                quoteZigString("irnative_" + symbol),
                symbol,
                indentBlock(macosDiscardLine, 0),
                symbol,
                includeDir,
                symbol,
                includePlatformDir,
                symbol,
                quoteZigString(relativeTo(buildProjectDirectory, workspaceDirectory.resolve("runtime"))),
                runtimeFiles,
                pathFlags,
                objectFileLines.isBlank() ? "" : indentBlock(objectFileLines, 0) + System.lineSeparator(),
                symbol,
                symbol,
                indentBlock(implibDirLine, 0),
                outputName,
                symbol,
                quoteZigString(symbol),
                quoteZigString("Build " + symbol + " native library"),
                symbol,
                symbol
        );
    }

    private String indentBlock(String text, int spaces) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String indent = " ".repeat(Math.max(0, spaces));
        return text.lines()
                .map(line -> line.isEmpty() ? line : indent + line)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    private String relativeTo(Path root, Path child) {
        return root.toAbsolutePath().normalize().relativize(child.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private String quoteZigString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String quoteZigEnum(String value) {
        return "." + value;
    }

    private String zigCpuArch(BuildTarget target) {
        return switch (target) {
            case WINDOWS_X64, LINUX_X64, MACOS_X64 -> "x86_64";
            case WINDOWS_ARM64, LINUX_ARM64, MACOS_ARM64 -> "aarch64";
        };
    }

    private String zigOsTag(BuildTarget target) {
        return switch (target) {
            case WINDOWS_X64, WINDOWS_ARM64 -> "windows";
            case LINUX_X64, LINUX_ARM64 -> "linux";
            case MACOS_X64, MACOS_ARM64 -> "macos";
        };
    }

    private String moduleObjectName(int index, Path llvmModuleFile, BuildTarget target) {
        return String.format("%02d-%s%s", index, baseName(llvmModuleFile.getFileName().toString()), objectFileExtension(target));
    }

    private String runtimeObjectName(int index, Path runtimeSourceFile, BuildTarget target) {
        return String.format("runtime-%02d-%s%s", index, baseName(runtimeSourceFile.getFileName().toString()), objectFileExtension(target));
    }

    private String baseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private String objectFileExtension(BuildTarget target) {
        return switch (target) {
            case WINDOWS_X64, WINDOWS_ARM64 -> ".obj";
            case LINUX_X64, LINUX_ARM64, MACOS_X64, MACOS_ARM64 -> ".o";
        };
    }

    private long nanosToMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    private void deleteWorkspacePathQuietly(Path rootDirectory) {
        if (rootDirectory == null || Files.notExists(rootDirectory)) {
            return;
        }
        try (var stream = Files.walk(rootDirectory)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    void cleanupIntermediates() {
        deleteWorkspacePathQuietly(ZigWorkspaceEnvironment.cacheRoot(workspaceDirectory));
        deleteWorkspacePathQuietly(workspaceDirectory.resolve("native-obj"));
        deleteWorkspacePathQuietly(workspaceDirectory.resolve("zig-build"));
    }

    void cleanupIntermediatesIfNeeded() {
        if (!preserveIntermediates) {
            cleanupIntermediates();
        }
    }

    public record BuildArtifact(BuildTarget target, Path libraryFile, Path logFile, BuildTiming timing) {
    }

    public record BuildResult(Path outputDirectory, List<BuildArtifact> artifacts) {
    }

    public record BuildTiming(int llvmShardCount, int runtimeSourceCount, long compileMillis, long linkMillis, long totalMillis) {
    }

    private record CompileUnit(String label, Path objectFile, List<String> command) {
    }

    private record CompileOutcome(CompileUnit unit, CommandResult result) {
    }

    private record CompileBatchResult(LinkedHashMap<String, String> outputByUnit, CompileUnit failedUnit) {
    }

    private record CommandResult(int exitCode, String output) {
    }

    private record TargetBuildState(BuildTarget target, Path libraryFile, Path logFile,
                                    List<CompileUnit> compileUnits, CompileBatchResult compileBatchResult,
                                    int llvmShardCount, int runtimeSourceCount, long compileMillis) {
    }
}
