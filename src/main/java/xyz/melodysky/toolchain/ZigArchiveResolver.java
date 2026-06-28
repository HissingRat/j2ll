package xyz.melodysky.toolchain;

import java.net.URI;
import java.util.Map;
import java.util.Locale;

public class ZigArchiveResolver {
    public static final String ZIG_VERSION = "0.15.2";
    public static final String DOWNLOAD_BASE = "https://ziglang.org/download/" + ZIG_VERSION + "/";
    public static final String SIGNATURE_POLICY = "notVerifiedBoundary";
    private static final Map<String, String> SHA256_BY_ARCHIVE = Map.of(
            "zig-x86_64-macos-0.15.2.tar.xz",
            "375b6909fc1495d16fc2c7db9538f707456bfc3373b14ee83fdd3e22b3d43f7f",
            "zig-aarch64-macos-0.15.2.tar.xz",
            "3cc2bab367e185cdfb27501c4b30b1b0653c28d9f73df8dc91488e66ece5fa6b",
            "zig-x86_64-linux-0.15.2.tar.xz",
            "02aa270f183da276e5b5920b1dac44a63f1a49e55050ebde3aecc9eb82f93239",
            "zig-aarch64-linux-0.15.2.tar.xz",
            "958ed7d1e00d0ea76590d27666efbf7a932281b3d7ba0c6b01b0ff26498f667f",
            "zig-x86_64-windows-0.15.2.zip",
            "3a0ed1e8799a2f8ce2a6e6290a9ff22e6906f8227865911fb7ddedc3cc14cb0c",
            "zig-aarch64-windows-0.15.2.zip",
            "b926465f8872bf983422257cd9ec248bb2b270996fbe8d57872cca13b56fc370");

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
        String sha256 = SHA256_BY_ARCHIVE.get(archive);
        if (sha256 == null) {
            throw new IllegalStateException("unsupported host archive metadata for managed Zig " + ZIG_VERSION
                    + ": " + archive);
        }
        return new ZigArchiveMetadata(archive, URI.create(DOWNLOAD_BASE + archive), zip, sha256, SIGNATURE_POLICY);
    }
}
