package xyz.melodysky.toolchain;

import java.net.URI;
import java.util.Locale;

public class ZigArchiveResolver {
    public static final String ZIG_VERSION = "0.15.2";
    public static final String DOWNLOAD_BASE = "https://ziglang.org/download/" + ZIG_VERSION + "/";
    public static final String CHECKSUM_BOUNDARY = "checksum-signature-interface-present-not-hardcoded-yet";

    public ZigArchiveMetadata currentHostArchive() {
        return archiveFor(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    public ZigArchiveMetadata archiveFor(String osName, String archName) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = archName.toLowerCase(Locale.ROOT);
        String zigArch;
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            zigArch = "aarch64";
        } else if (arch.contains("64") || arch.contains("amd64") || arch.contains("x86_64")) {
            zigArch = "x86_64";
        } else {
            throw new IllegalStateException("unsupported host architecture for managed Zig: " + archName);
        }

        String zigOs;
        boolean zip;
        if (os.contains("win")) {
            zigOs = "windows";
            zip = true;
        } else if (os.contains("mac") || os.contains("darwin")) {
            zigOs = "macos";
            zip = false;
        } else if (os.contains("linux")) {
            zigOs = "linux";
            zip = false;
        } else {
            throw new IllegalStateException("unsupported host OS for managed Zig: " + osName);
        }

        String archive = "zig-" + zigArch + "-" + zigOs + "-" + ZIG_VERSION + (zip ? ".zip" : ".tar.xz");
        return new ZigArchiveMetadata(archive, URI.create(DOWNLOAD_BASE + archive), zip, CHECKSUM_BOUNDARY);
    }
}
