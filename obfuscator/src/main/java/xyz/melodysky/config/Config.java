package xyz.melodysky.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Path DEFAULT_CONFIG_PATH = Paths.get("Config.json");
    private static final DateTimeFormatter BUILD_DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public String jarFile = "/path/to/your/jarFile.jar";
    public String outputDirectory = "out";
    public List<String> blackList = new ArrayList<>();
    public List<String> whiteList = new ArrayList<>();
    public TargetConfig target = new TargetConfig();
    public String libraryName;
    public String embeddedLibraryDirectory = "native0";
    public StringObfuscationConfig stringObfuscation = new StringObfuscationConfig();
    public Integer maxShardMB;

    public static class TargetConfig {
        public boolean windowsX64 = true;
        public boolean windowsArm64 = false;
        public boolean linuxX64 = true;
        public boolean linuxArm64 = false;
        public boolean macosX64 = true;
        public boolean macosArm64 = true;
    }

    public static class StringObfuscationConfig {
        public boolean enabled = true;
        public boolean cacheStrings;
    }

    public static Config loadOrCreateDefault() throws Exception {
        return loadOrCreate(DEFAULT_CONFIG_PATH);
    }

    public static Config loadOrCreate(Path path) throws Exception {
        if (Files.notExists(path)) {
            Config config = new Config();
            config.save(path);
            System.out.println("Created config file: " + path.toAbsolutePath());
            System.out.println("Fill it and run again.");
            return null;
        }
        return load(path);
    }

    public static Config load(Path path) throws Exception {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Config config = GSON.fromJson(reader, Config.class);
            if (config == null) {
                throw new IllegalArgumentException("Config file is empty: " + path.toAbsolutePath());
            }
            config.validate(path);
            return config;
        }
    }

    public void save(Path path) throws Exception {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
    }

    public Path getJarFilePath() {
        return Paths.get(jarFile);
    }

    public Path getOutputDirectoryPath() {
        return Paths.get(outputDirectory);
    }

    public Path createBuildDirectory() throws Exception {
        Path outputRoot = getOutputDirectoryPath();
        Files.createDirectories(outputRoot);

        String baseName = "build_" + LocalDateTime.now().format(BUILD_DIR_FORMAT);
        Path candidate = outputRoot.resolve(baseName);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = outputRoot.resolve(baseName + "-" + suffix++);
        }
        Files.createDirectories(candidate);
        return candidate;
    }

    public List<String> getBlackList() {
        return blackList == null ? new ArrayList<>() : blackList;
    }

    public List<String> getWhiteList() {
        return whiteList;
    }

    public List<BuildTarget> getEnabledTargets() {
        List<BuildTarget> targets = new ArrayList<>();
        if (target.windowsX64) {
            targets.add(BuildTarget.WINDOWS_X64);
        }
        if (target.windowsArm64) {
            targets.add(BuildTarget.WINDOWS_ARM64);
        }
        if (target.linuxX64) {
            targets.add(BuildTarget.LINUX_X64);
        }
        if (target.linuxArm64) {
            targets.add(BuildTarget.LINUX_ARM64);
        }
        if (target.macosX64) {
            targets.add(BuildTarget.MACOS_X64);
        }
        if (target.macosArm64) {
            targets.add(BuildTarget.MACOS_ARM64);
        }
        return targets;
    }

    public Integer getMaxShardBytes() {
        return maxShardMB == null ? null : Math.multiplyExact(maxShardMB, 1024 * 1024);
    }

    private void validate(Path source) {
        if (jarFile == null || jarFile.isBlank()) {
            throw new IllegalArgumentException("Missing 'jarFile' in config: " + source.toAbsolutePath());
        }
        if (outputDirectory == null || outputDirectory.isBlank()) {
            throw new IllegalArgumentException("Missing 'outputDirectory' in config: " + source.toAbsolutePath());
        }
        if (target == null) {
            target = new TargetConfig();
        }
        if (stringObfuscation == null) {
            stringObfuscation = new StringObfuscationConfig();
        }
        if (maxShardMB != null && maxShardMB <= 0) {
            throw new IllegalArgumentException("Config.maxShardMB must be positive when set: " + source.toAbsolutePath());
        }
        try {
            getMaxShardBytes();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Config.maxShardMB is too large: " + source.toAbsolutePath(), exception);
        }
        if (getEnabledTargets().isEmpty()) {
            throw new IllegalArgumentException("At least one target must be enabled in config.target: " + source.toAbsolutePath());
        }
    }
}
