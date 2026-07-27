package xyz.melodysky.runtime.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Java 17 template relocated into the output JAR by the packaging stage.
 *
 * <p>This source must not use nested, anonymous, or lambda classes because the output contract is one
 * physical Loader.class entry.
 */
public final class LoaderTemplate {
    private static volatile boolean loaded;

    private LoaderTemplate() {
    }

    public static synchronized void ensureLoaded() {
        throw new AssertionError("Loader template metadata was not injected");
    }

    private static void loadForCurrentTarget(
            Class<?> anchor,
            String[] targets,
            String[] resourcePaths,
            String[] expectedSha256s) {
        if (targets.length != resourcePaths.length || targets.length != expectedSha256s.length) {
            throw unsatisfied("invalid j2ll native library metadata arrays", null);
        }
        for (int index = 0; index < targets.length; index++) {
            if (matchesCurrentTarget(targets[index])) {
                load(anchor, resourcePaths[index], expectedSha256s[index]);
                return;
            }
        }
        throw unsatisfied("unsupported OS/arch for j2ll native library: current="
                + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
                + ", available targets=" + String.join(",", targets), null);
    }

    private static void load(Class<?> anchor, String resourcePath, String expectedSha256) {
        byte[] bytes = readResource(anchor, resourcePath);
        String actualSha256 = sha256(bytes);
        if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
            throw unsatisfied("hash mismatch for j2ll native library " + resourcePath
                    + ": expected " + expectedSha256 + " but found " + actualSha256, null);
        }
        Path extracted = extract(
                resourcePath,
                classLoaderId(anchor.getClassLoader()),
                actualSha256,
                bytes);
        System.load(extracted.toAbsolutePath().toString());
    }

    private static byte[] readResource(Class<?> anchor, String resourcePath) {
        ClassLoader loader = anchor.getClassLoader();
        try (InputStream input = loader == null
                ? ClassLoader.getSystemResourceAsStream(resourcePath)
                : loader.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw unsatisfied("j2ll native library resource not found: " + resourcePath, null);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw unsatisfied("failed to read j2ll native library resource: " + resourcePath, exception);
        }
    }

    private static Path extract(String resourcePath, String loaderId, String sha256, byte[] bytes) {
        String fileName = sanitizeFileName(resourcePath.substring(resourcePath.lastIndexOf('/') + 1));
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "j2ll", sha256, loaderId);
        Path output = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            if (!Files.exists(output) || !sha256(Files.readAllBytes(output)).equals(sha256)) {
                Files.write(output, bytes);
            }
            return output;
        } catch (IOException exception) {
            throw unsatisfied("failed to extract j2ll native library " + resourcePath + " to " + output, exception);
        }
    }

    private static String classLoaderId(ClassLoader loader) {
        return loader == null ? "bootstrap" : Integer.toHexString(System.identityHashCode(loader));
    }

    private static String sanitizeFileName(String fileName) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < fileName.length(); index++) {
            char ch = fileName.charAt(index);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '.'
                    || ch == '-'
                    || ch == '_') {
                builder.append(ch);
            } else {
                builder.append('_');
            }
        }
        return builder.length() == 0 ? "j2ll-native" : builder.toString();
    }

    private static boolean matchesCurrentTarget(String expectedTarget) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
        return switch (expectedTarget) {
            case "macos-arm64" -> (os.contains("mac") || os.contains("darwin")) && arm64;
            case "macos-x64" -> (os.contains("mac") || os.contains("darwin")) && x64;
            case "linux-arm64" -> os.contains("linux") && arm64;
            case "linux-x64" -> os.contains("linux") && x64;
            case "windows-arm64" -> os.contains("win") && arm64;
            case "windows-x64" -> os.contains("win") && x64;
            default -> false;
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    private static UnsatisfiedLinkError unsatisfied(String message, Throwable cause) {
        UnsatisfiedLinkError error = new UnsatisfiedLinkError(message);
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
    }
}
