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
            for (BuildTarget target : targets) {
                Path libraryFile = outputDirectory.resolve(outputFileName(target));
                Path logFile = logsDirectory.resolve("zig-build-" + target.getConfigKey() + ".log");
                BuildTiming timing = buildTarget(zigCommand, llvmModuleFiles, runtimeSourceFiles, libraryFile, target, logFile, progressListener);
                artifacts.add(new BuildArtifact(target, libraryFile, logFile, timing));
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
        if (requiresPic(target)) {
            command.add("-fPIC");
        }
        command.add("-c");
        command.add(llvmModuleFile.toAbsolutePath().toString());
        command.add("-o");
        command.add(outputFile.toAbsolutePath().toString());
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
        if (requiresPic(target)) {
            command.add("-fPIC");
        }
        command.add("-c");
        command.add("-I");
        command.add(jniHeadersDirectory.toAbsolutePath().toString());
        command.add("-I");
        command.add(jniHeadersDirectory.resolve(target.getJniHeaderSubdir()).toAbsolutePath().toString());
        command.add(runtimeStubFile.toAbsolutePath().toString());
        command.add("-o");
        command.add(outputFile.toAbsolutePath().toString());
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
        command.add("-shared");
        command.add("-s");
        for (Path objectFile : objectFiles) {
            command.add(objectFile.toAbsolutePath().toString());
        }
        command.add("-o");
        command.add(outputFile.toAbsolutePath().toString());
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

    private BuildTiming buildTarget(String zigCommand, List<Path> llvmModuleFiles, List<Path> runtimeSourceFiles,
                                    Path outputFile, BuildTarget target, Path logFile,
                                    ProgressListener progressListener) throws Exception {
        Path objectDirectory = workspaceDirectory.resolve("native-obj").resolve(target.getConfigKey());
        Files.createDirectories(objectDirectory);
        long totalStartNanos = System.nanoTime();

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

        int runtimeCompileUnitCount = 0;
        for (int index = 0; index < runtimeSourceFiles.size(); index++) {
            Path runtimeSourceFile = runtimeSourceFiles.get(index);
            Path runtimeObjectFile = objectDirectory.resolve(runtimeObjectName(index, runtimeSourceFile, target));
            compileUnits.add(new CompileUnit(
                    "runtime[" + runtimeSourceFile.getFileName() + "]",
                    runtimeObjectFile,
                    createRuntimeObjectCompileCommand(zigCommand, runtimeSourceFile, runtimeObjectFile, target)
            ));
            runtimeCompileUnitCount++;
        }

        progressListener.onTargetStart(target, compileUnits.size());
        long compileStartNanos = System.nanoTime();
        CompileBatchResult compileBatchResult = runParallelCompiles(compileUnits, target, progressListener);
        long compileEndNanos = System.nanoTime();
        List<String> linkCommand = createLinkCommand(
                zigCommand,
                compileUnits.stream().map(CompileUnit::objectFile).toList(),
                outputFile,
                target
        );
        progressListener.onLinkStart(target);
        long linkStartNanos = System.nanoTime();
        CommandResult linkResult = run(linkCommand);
        long linkEndNanos = System.nanoTime();
        BuildTiming timing = new BuildTiming(
                llvmCompileUnitCount,
                runtimeCompileUnitCount,
                nanosToMillis(compileEndNanos - compileStartNanos),
                nanosToMillis(linkEndNanos - linkStartNanos),
                nanosToMillis(linkEndNanos - totalStartNanos)
        );
        progressListener.onTargetComplete(target, timing);

        StringBuilder logContent = new StringBuilder();
        for (CompileUnit unit : compileUnits) {
            logContent.append("$ ").append(renderCommand(unit.command())).append(System.lineSeparator());
            logContent.append(compileBatchResult.outputByUnit().getOrDefault(unit.label(), ""));
        }
        logContent.append("$ ").append(renderCommand(linkCommand)).append(System.lineSeparator());
        logContent.append(linkResult.output());
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
        Files.writeString(logFile, logContent.toString(), StandardCharsets.UTF_8);

        if (compileBatchResult.failedUnit() != null) {
            throw new IOException("Failed to build IR native artifact for command: "
                    + renderCommand(compileBatchResult.failedUnit().command())
                    + System.lineSeparator() + "See log: " + logFile.toAbsolutePath());
        }
        if (linkResult.exitCode() != 0) {
            throw new IOException("Failed to build IR native artifact for command: " + renderCommand(linkCommand)
                    + System.lineSeparator() + "See log: " + logFile.toAbsolutePath());
        }
        return timing;
    }

    private CompileBatchResult runParallelCompiles(List<CompileUnit> compileUnits, BuildTarget target,
                                                   ProgressListener progressListener) throws Exception {
        int threadCount = Math.max(1, Math.min(compileUnits.size(), Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            ArrayList<Callable<CompileOutcome>> tasks = new ArrayList<>(compileUnits.size());
            for (CompileUnit unit : compileUnits) {
                tasks.add(() -> new CompileOutcome(unit, run(unit.command())));
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

    private CommandResult run(List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspaceDirectory.toFile());
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
        Thread outputReader = new Thread(() -> {
            try (var input = process.getInputStream()) {
                outputRef.set(readProcessOutput(input));
            } catch (IOException exception) {
                outputFailureRef.set(exception);
            }
        }, "ir-native-build-output");
        outputReader.setDaemon(true);
        outputReader.start();
        SubprocessRegistry.Registration registration = SubprocessRegistry.register(process);
        try {
            while (true) {
                throwIfCancellationRequested();
                if (process.waitFor(100L, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }

            String output = awaitCollectedOutput(outputReader, outputRef, outputFailureRef);
            int exitCode = process.exitValue();
            completed = true;
            registration.close();
            return new CommandResult(exitCode, output);
        } finally {
            if (!completed) {
                SubprocessRegistry.destroyProcessTree(process);
                registration.close();
            }
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
        if (outputReader == null) {
            return;
        }
        boolean interrupted = false;
        while (outputReader.isAlive()) {
            try {
                outputReader.join(100L);
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
}
