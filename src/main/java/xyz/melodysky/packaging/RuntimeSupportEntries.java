package xyz.melodysky.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import xyz.melodysky.runtime.loader.J2llNativeLoaderSupport;

public final class RuntimeSupportEntries {
    public Map<String, byte[]> loaderSupportEntries() throws IOException {
        String entryName = J2llNativeLoaderSupport.class.getName().replace('.', '/') + ".class";
        try (InputStream input = J2llNativeLoaderSupport.class.getClassLoader().getResourceAsStream(entryName)) {
            if (input == null) {
                throw new IOException("missing runtime support class bytes: " + entryName);
            }
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put(entryName, input.readAllBytes());
            return entries;
        }
    }
}
