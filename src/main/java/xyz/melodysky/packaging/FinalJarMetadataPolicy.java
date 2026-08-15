package xyz.melodysky.packaging;

import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.jar.Manifest;

/** Final-JAR policy for tool-private metadata that must not escape the workspace. */
public final class FinalJarMetadataPolicy {
    private static final String PRIVATE_J2LL_ROOT = "meta-inf/j2ll";

    private FinalJarMetadataPolicy() {}

    public static boolean isPrivateJ2llEntry(String entryName) {
        String normalized = Objects.requireNonNull(entryName, "entryName")
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);
        if (isPrivateRoot(normalized)) {
            return true;
        }
        String versionedPrefix = "meta-inf/versions/";
        if (!normalized.startsWith(versionedPrefix)) {
            return false;
        }
        int versionEnd = normalized.indexOf('/', versionedPrefix.length());
        return versionEnd >= 0 && isPrivateRoot(normalized.substring(versionEnd + 1));
    }

    public static List<String> privateManifestSections(Manifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        return manifest.getEntries().keySet().stream()
                .filter(FinalJarMetadataPolicy::isPrivateJ2llEntry)
                .sorted()
                .toList();
    }

    private static boolean isPrivateRoot(String entryName) {
        return entryName.equals(PRIVATE_J2LL_ROOT)
                || entryName.startsWith(PRIVATE_J2LL_ROOT + "/");
    }
}
