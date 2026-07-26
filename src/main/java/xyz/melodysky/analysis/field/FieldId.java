package xyz.melodysky.analysis.field;

import java.util.Objects;

public record FieldId(String owner, String name, String descriptor) implements Comparable<FieldId> {
    public FieldId {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
    }

    public String fieldKey() {
        return owner + "#" + name + "!" + descriptor;
    }

    @Override
    public int compareTo(FieldId other) {
        return fieldKey().compareTo(other.fieldKey());
    }
}
