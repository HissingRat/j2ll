package xyz.melodysky.zig;

import xyz.melodysky.config.BuildTarget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class ZigManager {

    private static final String ZIG_VERSION = "0.15.2";
    private static final String DOWNLOAD_BASE = "https://ziglang.org/download/" + ZIG_VERSION + "/";
    private static final String ZIG_MINISIGN_PUBLIC_KEY = "RWSGOq2NVecA2UPNdBUZykf1CCb147pkmdtYxgb3Ti+JO/wCYvhbAb/U";
    private static final String MINISIGN_SIGNATURE_SUFFIX = ".minisig";
    private static final String BUILD_PROGRESS_MARKER = "__NATIVE_OBFUSCATOR_PROGRESS__";
    private static final int TARGET_BUILD_STAGE_COUNT = 4;
    private static final int TARGET_PROGRESS_BAR_WIDTH = 16;
    private static final int OVERALL_PROGRESS_BAR_WIDTH = TARGET_PROGRESS_BAR_WIDTH;

    private final Path applicationDirectory;
    private final Path workspaceDirectory;
    private final ProgressLog progressLog;

    public ZigManager(Path applicationDirectory, Path workspaceDirectory) {
        this.applicationDirectory = applicationDirectory;
        this.workspaceDirectory = workspaceDirectory;
        this.progressLog = new ProgressLog(isWindows());
    }

    public static Path resolveApplicationDirectory(Class<?> anchor) throws Exception {
        CodeSource source = anchor.getProtectionDomain().getCodeSource();
        if (source == null) {
            return Path.of("").toAbsolutePath().normalize();
        }

        Path location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        if (Files.isRegularFile(location)) {
            return location.getParent();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    public String ensureZigCommand() throws Exception {
        Path localZig = getLocalZigExecutable();
        if (isExactVersion(localZig.toString())) {
            return localZig.toString();
        }

        if (isExactVersion("zig")) {
            return "zig";
        }

        installLocalZig();
        if (!isExactVersion(localZig.toString())) {
            throw new IllegalStateException("Installed Zig is not usable: " + localZig);
        }
        return localZig.toString();
    }

    public void buildTargets(String zigCommand, List<BuildTarget> targets) throws Exception {
        Path logFile = workspaceDirectory.resolve("zig-build.log");
        Files.deleteIfExists(logFile);

        for (int i = 0; i < targets.size(); i++) {
            BuildTarget target = targets.get(i);
            updateBuildProgress(target, i, targets.size(), 0, "starting");
            progressLog.appendFileLog(logFile, "==> Building target " + target.getConfigKey() + " (" + target.getZigTarget() + ")\n");
            runBuildCommand(createBuildCommand(zigCommand, target), logFile, target, i, targets.size());
            updateBuildProgress(target, i + 1, targets.size(), TARGET_BUILD_STAGE_COUNT, "done");
        }

        if (!targets.isEmpty()) {
            progressLog.appendLog(logFile, formatOverallBuildProgress(targets.size(), targets.size(), true) + System.lineSeparator());
        }
    }

    private Path getLocalZigExecutable() {
        return applicationDirectory.resolve("zig-" + ZIG_VERSION).resolve(isWindows() ? "zig.exe" : "zig");
    }

    private List<String> createBuildCommand(String zigCommand, BuildTarget target) {
        return List.of(
                zigCommand,
                "build",
                "-Dtarget=" + target.getZigTarget()
        );
    }

    private void installLocalZig() throws Exception {
        Files.createDirectories(applicationDirectory);

        HostZigPackage host = HostZigPackage.detect();
        Path logFile = workspaceDirectory.resolve("zig-setup.log");
        Files.deleteIfExists(logFile);
        progressLog.appendLog(logFile, "Preparing Zig " + ZIG_VERSION + " for " + host.archiveName + "\n");

        Path archive = applicationDirectory.resolve(host.archiveName);
        Path signatureFile = applicationDirectory.resolve(host.archiveName + MINISIGN_SIGNATURE_SUFFIX);
        Path extractDir = applicationDirectory.resolve("zig-" + ZIG_VERSION + "-extract");
        Path installDir = applicationDirectory.resolve("zig-" + ZIG_VERSION);

        deleteRecursively(extractDir);
        prepareArchive(host, archive, signatureFile, logFile);

        Files.createDirectories(extractDir);
        progressLog.appendLog(logFile, "Extracting " + archive + "\n");
        extractArchive(archive, extractDir, host.zipArchive, logFile);

        Path extractedRoot;
        try (Stream<Path> stream = Files.list(extractDir).filter(Files::isDirectory)) {
            List<Path> roots = stream.toList();
            if (roots.size() != 1) {
                throw new IllegalStateException("Unexpected Zig archive layout in " + extractDir);
            }
            extractedRoot = roots.getFirst();
        }

        deleteRecursively(installDir);
        Files.move(extractedRoot, installDir, StandardCopyOption.REPLACE_EXISTING);
        deleteRecursively(extractDir);

        if (!isWindows()) {
            installDir.resolve("zig").toFile().setExecutable(true);
        }

        progressLog.appendLog(logFile, "Installed Zig to " + installDir + "\n");
    }

    private void prepareArchive(HostZigPackage host, Path archive, Path signatureFile, Path logFile) throws Exception {
        if (Files.exists(archive)) {
            progressLog.appendLog(logFile, "[CACHE] Found local Zig archive: " + archive + "\n");
            ensureSignatureAvailable(host.signatureUrl, signatureFile, logFile);
            try {
                verifyArchiveSignature(archive, signatureFile, host.archiveName);
                progressLog.appendLog(logFile, "[VERIFY] Local Zig archive passed minisig verification\n");
                return;
            } catch (Exception verificationError) {
                progressLog.appendLog(logFile, "[CACHE] Local Zig archive verification failed, redownloading: " +
                        verificationError.getMessage() + "\n");
                Files.deleteIfExists(archive);
                Files.deleteIfExists(signatureFile);
            }
        }

        progressLog.appendLog(logFile, "[DOWNLOAD] Fetching Zig archive from " + host.downloadUrl + "\n");
        downloadFile(host.downloadUrl, archive, logFile);
        progressLog.appendLog(logFile, "[DOWNLOAD] Fetching Zig minisig from " + host.signatureUrl + "\n");
        downloadFile(host.signatureUrl, signatureFile, logFile);
        verifyArchiveSignature(archive, signatureFile, host.archiveName);
        progressLog.appendLog(logFile, "[VERIFY] Downloaded Zig archive passed minisig verification\n");
    }

    private void ensureSignatureAvailable(String signatureUrl, Path signatureFile, Path logFile) throws Exception {
        if (Files.exists(signatureFile)) {
            progressLog.appendLog(logFile, "[CACHE] Found local Zig minisig: " + signatureFile + "\n");
            return;
        }
        progressLog.appendLog(logFile, "[DOWNLOAD] Fetching Zig minisig from " + signatureUrl + "\n");
        downloadFile(signatureUrl, signatureFile, logFile);
    }

    private boolean isExactVersion(String zigCommand) {
        try {
            CommandRunner.Result result = createCommandRunner().run(List.of(zigCommand, "version"), null, null, null);
            return result.exitCode() == 0 && ZIG_VERSION.equals(result.output().trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    protected void downloadFile(String url, Path destination, Path logFile) throws Exception {
        HttpDownloader.download(url, destination, logFile, progressLog);
    }

    protected void verifyArchiveSignature(Path archive, Path signatureFile, String expectedArchiveName) throws Exception {
        MinisignVerifier.verify(archive, signatureFile, expectedArchiveName, getMinisignPublicKey());
    }

    protected String getMinisignPublicKey() {
        return ZIG_MINISIGN_PUBLIC_KEY;
    }

    protected void extractArchive(Path archive, Path extractDir, boolean zipArchive, Path logFile) throws Exception {
        if (zipArchive) {
            ZipExtractor.extract(archive, extractDir);
            return;
        }
        runCommand(List.of("tar", "-xf", archive.toAbsolutePath().toString(), "-C", extractDir.toAbsolutePath().toString()), logFile);
    }

    private void runCommand(List<String> command, Path logFile) throws Exception {
        CommandRunner.Result result = createCommandRunner().run(command, logFile, null, createCommandOutputHandler());
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Command failed (" + result.exitCode() + "): " + String.join(" ", command) +
                    "\nSee log: " + logFile.toAbsolutePath());
        }
    }

    private void runBuildCommand(List<String> command, Path logFile, BuildTarget target, int targetIndex, int totalTargets) throws Exception {
        BuildProgressContext context = new BuildProgressContext(target, targetIndex, totalTargets);
        CommandRunner.Result result = createCommandRunner().run(command, logFile, context, createCommandOutputHandler());
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Command failed (" + result.exitCode() + "): " + String.join(" ", command) +
                    "\nSee log: " + logFile.toAbsolutePath());
        }
    }

    private boolean handleBuildProgressLine(Path logFile, String line, BuildProgressContext buildProgressContext) throws Exception {
        if (buildProgressContext == null || !line.startsWith(BUILD_PROGRESS_MARKER)) {
            return false;
        }

        String stage = line.substring(BUILD_PROGRESS_MARKER.length()).trim();
        int stageNumber;
        String stageLabel;
        switch (stage) {
            case "compile-start" -> {
                stageNumber = 1;
                stageLabel = "compiling native";
            }
            case "compile-done" -> {
                stageNumber = 2;
                stageLabel = "compiled native";
            }
            case "package-start" -> {
                stageNumber = 3;
                stageLabel = "packing jar";
            }
            case "package-done" -> {
                stageNumber = 4;
                stageLabel = "embedded library";
            }
            default -> {
                progressLog.appendFileLog(logFile, line + System.lineSeparator());
                return true;
            }
        }

        progressLog.appendFileLog(logFile, "Build stage: " + stageLabel + System.lineSeparator());
        updateBuildProgress(buildProgressContext.target, buildProgressContext.targetIndex, buildProgressContext.totalTargets, stageNumber, stageLabel);
        return true;
    }

    private void updateBuildProgress(BuildTarget target, int targetIndex, int totalTargets, int stageNumber, String stageLabel) {
        progressLog.updateConsoleProgressLines(List.of(
                String.format(Locale.ROOT,
                        "%s %d/%d (%s) - %s",
                        progressLog.formatProgressBar(stageNumber, TARGET_BUILD_STAGE_COUNT, TARGET_PROGRESS_BAR_WIDTH),
                        stageNumber, TARGET_BUILD_STAGE_COUNT,
                        target.getZigTarget(), stageLabel),
                formatOverallBuildProgress(targetIndex, totalTargets, false)
        ));
    }

    private String formatOverallBuildProgress(int completedTargets, int totalTargets, boolean finished) {
        return String.format(Locale.ROOT,
                "Build progress: %s %d/%d completed%s",
                progressLog.formatProgressBar(completedTargets, totalTargets, OVERALL_PROGRESS_BAR_WIDTH),
                completedTargets, totalTargets,
                finished ? "." : "");
    }

    private static class BuildProgressContext {
        private final BuildTarget target;
        private final int targetIndex;
        private final int totalTargets;

        private BuildProgressContext(BuildTarget target, int targetIndex, int totalTargets) {
            this.target = target;
            this.targetIndex = targetIndex;
            this.totalTargets = totalTargets;
        }
    }

    private void deleteRecursively(Path path) throws Exception {
        if (Files.notExists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : paths) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private CommandRunner createCommandRunner() {
        return new CommandRunner(workspaceDirectory, isWindows(), System.getProperty("java.home"));
    }

    private CommandRunner.OutputHandler<BuildProgressContext> createCommandOutputHandler() {
        return new CommandRunner.OutputHandler<>() {
            @Override
            public void onCommandStart(Path logFile, String renderedCommand, boolean mirrorToConsole,
                                       BuildProgressContext context) throws Exception {
                if (mirrorToConsole) {
                    progressLog.appendLog(logFile, "$ " + renderedCommand + "\n");
                } else {
                    progressLog.appendFileLog(logFile, "$ " + renderedCommand + "\n");
                }
            }

            @Override
            public void onPartialOutput(String text, boolean mirrorToConsole, BuildProgressContext context) {
                if (mirrorToConsole) {
                    progressLog.updateConsoleProgress(text);
                }
            }

            @Override
            public boolean onLine(Path logFile, String line, boolean carriageReturn, boolean mirrorToConsole,
                                  BuildProgressContext context) throws Exception {
                if (handleBuildProgressLine(logFile, line, context)) {
                    return true;
                }

                if (carriageReturn) {
                    progressLog.appendFileLog(logFile, line + System.lineSeparator());
                    if (mirrorToConsole) {
                        progressLog.updateConsoleProgress(line);
                    }
                    return true;
                }

                if (mirrorToConsole) {
                    progressLog.appendLog(logFile, line + "\n");
                } else {
                    progressLog.appendFileLog(logFile, line + "\n");
                }
                return true;
            }
        };
    }

    private static class HostZigPackage {
        private final String archiveName;
        private final String downloadUrl;
        private final String signatureUrl;
        private final boolean zipArchive;

        private HostZigPackage(String archiveName, String downloadUrl, String signatureUrl, boolean zipArchive) {
            this.archiveName = archiveName;
            this.downloadUrl = downloadUrl;
            this.signatureUrl = signatureUrl;
            this.zipArchive = zipArchive;
        }

        private static HostZigPackage detect() {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

            String zigArch;
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                zigArch = "aarch64";
            } else if (arch.contains("64")) {
                zigArch = "x86_64";
            } else {
                throw new IllegalStateException("Unsupported host arch for Zig download: " + arch);
            }

            String zigOs;
            boolean zipArchive;
            if (os.contains("mac")) {
                zigOs = "macos";
                zipArchive = false;
            } else if (os.contains("win")) {
                zigOs = "windows";
                zipArchive = true;
            } else if (os.contains("linux")) {
                zigOs = "linux";
                zipArchive = false;
            } else {
                throw new IllegalStateException("Unsupported host OS for Zig download: " + os);
            }

            String archiveName = "zig-" + zigArch + "-" + zigOs + "-" + ZIG_VERSION + (zipArchive ? ".zip" : ".tar.xz");
            return new HostZigPackage(
                    archiveName,
                    DOWNLOAD_BASE + archiveName,
                    DOWNLOAD_BASE + archiveName + MINISIGN_SIGNATURE_SUFFIX,
                    zipArchive
            );
        }
    }
}
