package xyz.melodysky.runtime.metadata;

import java.util.List;
import java.util.Objects;

public record FieldMetadata(
        String owner,
        String name,
        String descriptor,
        List<String> accessFlags,
        List<String> compilerFlags,
        SignatureMetadata signature,
        List<AnnotationMetadata> annotations) implements Comparable<FieldMetadata> {
    public FieldMetadata {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        accessFlags = accessFlags.stream().filter(Objects::nonNull).sorted().distinct().toList();
        compilerFlags = compilerFlags.stream().filter(Objects::nonNull).sorted().distinct().toList();
        Objects.requireNonNull(signature, "signature");
        annotations = annotations.stream().filter(Objects::nonNull).sorted().toList();
    }

    public String fieldKey() {
        return owner + "#" + name + "!" + descriptor;
    }

    @Override
    public int compareTo(FieldMetadata other) {
        int nameCompare = name.compareTo(other.name);
        if (nameCompare != 0) {
            return nameCompare;
        }
        return descriptor.compareTo(other.descriptor);
    }
}
