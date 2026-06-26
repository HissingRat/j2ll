package xyz.melodysky.runtime.metadata;

import java.util.List;
import java.util.Objects;

public record InnerClassMetadata(
        String name,
        String outerName,
        String innerName,
        List<String> accessFlags,
        List<String> compilerFlags) implements Comparable<InnerClassMetadata> {
    public InnerClassMetadata {
        Objects.requireNonNull(name, "name");
        accessFlags = accessFlags.stream().filter(Objects::nonNull).sorted().distinct().toList();
        compilerFlags = compilerFlags.stream().filter(Objects::nonNull).sorted().distinct().toList();
    }

    @Override
    public int compareTo(InnerClassMetadata other) {
        return name.compareTo(other.name);
    }
}
