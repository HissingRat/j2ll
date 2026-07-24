package xyz.melodysky.toolchain;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ManagedZigTargetCapabilities {
    private static final String REQUIRED_CAPABILITY = "managedZig0.15.2CrossTargetSharedLibrary";
    private final Path jniHeader;

    public ManagedZigTargetCapabilities() {
        this(Path.of(System.getProperty("java.home")).resolve("include/jni.h"));
    }

    ManagedZigTargetCapabilities(Path jniHeader) {
        this.jniHeader = jniHeader;
    }

    public ZigTargetCapability capability(TargetTriple target, boolean currentHost) {
        if (!Files.isRegularFile(jniHeader)) {
            return new ZigTargetCapability(
                    false,
                    "JNI_HEADERS_UNAVAILABLE",
                    "the current Java runtime does not provide include/jni.h: " + jniHeader,
                    REQUIRED_CAPABILITY,
                    sdkRequirement(target) + "; current JDK JNI headers required",
                    "missingJniHeaders",
                    "target preflight stopped before Zig invocation");
        }
        String reasonCode = currentHost ? "CURRENT_HOST_TARGET" : "ZIG_CROSS_TARGET_SUPPORTED";
        String reason = currentHost
                ? "selected target matches the current JVM host and is supported by managed Zig 0.15.2"
                : "selected target is supported by the managed Zig 0.15.2 cross-target workspace";
        return new ZigTargetCapability(
                true,
                reasonCode,
                reason,
                REQUIRED_CAPABILITY,
                sdkRequirement(target),
                "none",
                "preflight buildable; the matrix-wide Zig build log is recorded after invocation");
    }

    private String sdkRequirement(TargetTriple target) {
        return switch (target) {
            case WINDOWS_X64, WINDOWS_ARM64 ->
                    "managed Zig 0.15.2 COFF/Windows libc target support; no host Windows SDK required";
            case LINUX_X64, LINUX_ARM64 ->
                    "managed Zig 0.15.2 ELF/Linux libc target support; no host Linux SDK required";
            case MACOS_X64, MACOS_ARM64 ->
                    "managed Zig 0.15.2 Mach-O/Darwin target support; no host macOS SDK required";
        };
    }
}
