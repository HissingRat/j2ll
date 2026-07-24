package xyz.melodysky.toolchain;

public enum TargetTriple {
    WINDOWS_X64("windows-x64", "x64-windows.dll", true),
    WINDOWS_ARM64("windows-arm64", "arm64-windows.dll", true),
    LINUX_X64("linux-x64", "x64-linux.so", false),
    LINUX_ARM64("linux-arm64", "arm64-linux.so", false),
    MACOS_X64("macos-x64", "x64-macos.dylib", false),
    MACOS_ARM64("macos-arm64", "arm64-macos.dylib", false);

    private final String directoryName;
    private final String libraryFileName;
    private final boolean windows;

    TargetTriple(String directoryName, String libraryFileName, boolean windows) {
        this.directoryName = directoryName;
        this.libraryFileName = libraryFileName;
        this.windows = windows;
    }

    public String directoryName() {
        return directoryName;
    }

    public String libraryFileName() {
        return libraryFileName;
    }

    public boolean isWindows() {
        return windows;
    }

    public String osClassifier() {
        return switch (this) {
            case WINDOWS_X64, WINDOWS_ARM64 -> "windows";
            case LINUX_X64, LINUX_ARM64 -> "linux";
            case MACOS_X64, MACOS_ARM64 -> "macos";
        };
    }

    public String archClassifier() {
        return switch (this) {
            case WINDOWS_X64, LINUX_X64, MACOS_X64 -> "x64";
            case WINDOWS_ARM64, LINUX_ARM64, MACOS_ARM64 -> "arm64";
        };
    }

    public String libraryExtension() {
        int dot = libraryFileName.lastIndexOf('.');
        return dot < 0 ? "" : libraryFileName.substring(dot + 1);
    }

    public String zigCpuArch() {
        return switch (this) {
            case WINDOWS_X64, LINUX_X64, MACOS_X64 -> "x86_64";
            case WINDOWS_ARM64, LINUX_ARM64, MACOS_ARM64 -> "aarch64";
        };
    }

    public String zigOsTag() {
        return osClassifier();
    }

    public String zigTarget() {
        return switch (this) {
            case WINDOWS_X64, WINDOWS_ARM64 -> zigCpuArch() + "-windows-gnu";
            case LINUX_X64 -> "x86_64-linux.3.2-gnu.2.17";
            case LINUX_ARM64 -> "aarch64-linux.3.7-gnu.2.17";
            case MACOS_X64 -> "x86_64-macos.10.15";
            case MACOS_ARM64 -> "aarch64-macos.11.0";
        };
    }

    public String zigTargetQuery() {
        String base = ".{ .cpu_arch = ." + zigCpuArch() + ", .os_tag = ." + zigOsTag();
        return switch (this) {
            case WINDOWS_X64, WINDOWS_ARM64 -> base + ", .abi = .gnu }";
            case LINUX_X64 -> base
                    + ", .os_version_min = .{ .semver = .{ .major = 3, .minor = 2, .patch = 0 } }"
                    + ", .abi = .gnu, .glibc_version = .{ .major = 2, .minor = 17, .patch = 0 } }";
            case LINUX_ARM64 -> base
                    + ", .os_version_min = .{ .semver = .{ .major = 3, .minor = 7, .patch = 0 } }"
                    + ", .abi = .gnu, .glibc_version = .{ .major = 2, .minor = 17, .patch = 0 } }";
            case MACOS_X64 -> base
                    + ", .os_version_min = .{ .semver = .{ .major = 10, .minor = 15, .patch = 0 } } }";
            case MACOS_ARM64 -> base
                    + ", .os_version_min = .{ .semver = .{ .major = 11, .minor = 0, .patch = 0 } } }";
        };
    }

    public String safeSymbol() {
        return directoryName.replace('-', '_');
    }
}
