package xyz.melodysky.toolchain;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;

public final class J2llHomeResolver {
    public static final String OVERRIDE_PROPERTY = "j2ll.home";

    public Path resolve() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        CodeSource source = J2llHomeResolver.class.getProtectionDomain().getCodeSource();
        if (source != null) {
            try {
                Path location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
                if (Files.isRegularFile(location)) {
                    return location.getParent();
                }
            } catch (URISyntaxException ignored) {
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }
}
