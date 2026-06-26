package xyz.melodysky.toolchain;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

public interface ZigDownloader {
    void download(URI uri, Path destination) throws IOException;

    static ZigDownloader http() {
        return (uri, destination) -> {
            Files.createDirectories(destination.toAbsolutePath().getParent());
            try (var input = uri.toURL().openStream()) {
                Files.copy(input, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        };
    }
}
