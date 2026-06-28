package xyz.melodysky.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import xyz.melodysky.runtime.fallback.J2llFallbackSupport;
import xyz.melodysky.runtime.loader.J2llNativeLoaderSupport;

public final class RuntimeSupportEntries {
    public Map<String, byte[]> loaderSupportEntries() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        addRuntimeClass(entries, J2llNativeLoaderSupport.class);
        addRuntimeClass(entries, J2llFallbackSupport.class);
        return entries;
    }

    private void addRuntimeClass(Map<String, byte[]> entries, Class<?> runtimeClass) throws IOException {
        String entryName = runtimeClass.getName().replace('.', '/') + ".class";
        try (InputStream input = J2llNativeLoaderSupport.class.getClassLoader().getResourceAsStream(entryName)) {
            if (input == null) {
                throw new IOException("missing runtime support class bytes: " + entryName);
            }
            entries.put(entryName, input.readAllBytes());
        }
    }
}
