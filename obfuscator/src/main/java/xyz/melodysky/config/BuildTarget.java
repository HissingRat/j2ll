package xyz.melodysky.config;

public enum BuildTarget {
    WINDOWS_X64("windowsX64", "x86_64-windows", "win32"),
    WINDOWS_ARM64("windowsArm64", "aarch64-windows", "win32"),
    LINUX_X64("linuxX64", "x86_64-linux", "linux"),
    LINUX_ARM64("linuxArm64", "aarch64-linux", "linux"),
    MACOS_X64("macosX64", "x86_64-macos", "darwin"),
    MACOS_ARM64("macosArm64", "aarch64-macos", "darwin");

    private final String configKey;
    private final String zigTarget;
    private final String jniHeaderSubdir;

    BuildTarget(String configKey, String zigTarget, String jniHeaderSubdir) {
        this.configKey = configKey;
        this.zigTarget = zigTarget;
        this.jniHeaderSubdir = jniHeaderSubdir;
    }

    public String getConfigKey() {
        return configKey;
    }

    public String getZigTarget() {
        return zigTarget;
    }

    public String getJniHeaderSubdir() {
        return jniHeaderSubdir;
    }
}
