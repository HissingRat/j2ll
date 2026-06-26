package xyz.melodysky.runtime.metadata;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record AnnotationMetadata(String descriptor, boolean runtimeVisible, Map<String, String> values)
        implements Comparable<AnnotationMetadata> {
    public AnnotationMetadata {
        Objects.requireNonNull(descriptor, "descriptor");
        values = Map.copyOf(new TreeMap<>(Objects.requireNonNull(values, "values")));
    }

    @Override
    public int compareTo(AnnotationMetadata other) {
        int descriptorCompare = descriptor.compareTo(other.descriptor);
        if (descriptorCompare != 0) {
            return descriptorCompare;
        }
        return Boolean.compare(runtimeVisible, other.runtimeVisible);
    }
}
