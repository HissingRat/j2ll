package xyz.melodysky.toolchain;

import java.util.Locale;
import java.util.Optional;

public record HostPlatform(TargetTriple target, String jniIncludeSubdirectory) {
    public static Optional<HostPlatform> detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
        if ((os.contains("mac") || os.contains("darwin")) && arm64) {
            return Optional.of(new HostPlatform(TargetTriple.MACOS_ARM64, "darwin"));
        }
        if ((os.contains("mac") || os.contains("darwin")) && x64) {
            return Optional.of(new HostPlatform(TargetTriple.MACOS_X64, "darwin"));
        }
        if (os.contains("linux") && arm64) {
            return Optional.of(new HostPlatform(TargetTriple.LINUX_ARM64, "linux"));
        }
        if (os.contains("linux") && x64) {
            return Optional.of(new HostPlatform(TargetTriple.LINUX_X64, "linux"));
        }
        if (os.contains("win") && arm64) {
            return Optional.of(new HostPlatform(TargetTriple.WINDOWS_ARM64, "win32"));
        }
        if (os.contains("win") && x64) {
            return Optional.of(new HostPlatform(TargetTriple.WINDOWS_X64, "win32"));
        }
        return Optional.empty();
    }
}
