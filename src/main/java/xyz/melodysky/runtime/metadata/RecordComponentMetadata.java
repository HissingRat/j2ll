package xyz.melodysky.runtime.metadata;

import java.util.List;
import java.util.Objects;

public record RecordComponentMetadata(
        String name,
        String descriptor,
        SignatureMetadata signature,
        List<AnnotationMetadata> annotations) implements Comparable<RecordComponentMetadata> {
    public RecordComponentMetadata {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(signature, "signature");
        annotations = annotations.stream().filter(Objects::nonNull).sorted().toList();
    }

    @Override
    public int compareTo(RecordComponentMetadata other) {
        int nameCompare = name.compareTo(other.name);
        if (nameCompare != 0) {
            return nameCompare;
        }
        return descriptor.compareTo(other.descriptor);
    }
}
