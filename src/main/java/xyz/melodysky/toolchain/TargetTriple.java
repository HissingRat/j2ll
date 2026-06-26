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

    public String zigCpuArch() {
        return switch (this) {
            case WINDOWS_X64, LINUX_X64, MACOS_X64 -> "x86_64";
            case WINDOWS_ARM64, LINUX_ARM64, MACOS_ARM64 -> "aarch64";
        };
    }

    public String zigOsTag() {
        return switch (this) {
            case WINDOWS_X64, WINDOWS_ARM64 -> "windows";
            case LINUX_X64, LINUX_ARM64 -> "linux";
            case MACOS_X64, MACOS_ARM64 -> "macos";
        };
    }

    public String zigTarget() {
        return zigCpuArch() + "-" + zigOsTag();
    }

    public String safeSymbol() {
        return directoryName.replace('-', '_');
    }
}
