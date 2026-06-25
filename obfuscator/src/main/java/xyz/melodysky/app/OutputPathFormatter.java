package xyz.melodysky.app;

import java.nio.file.Path;

public final class OutputPathFormatter {

    private OutputPathFormatter() {
    }

    public static String relativizeOutputPath(Path artifactPath) {
        try {
            Path base = Path.of("").toAbsolutePath().normalize();
            return base.relativize(artifactPath.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (Exception ignored) {
            return formatIrOutputHint(artifactPath);
        }
    }

    public static String formatIrOutputHint(Path artifactPath) {
        return artifactPath.toString().replace('\\', '/');
    }
}
