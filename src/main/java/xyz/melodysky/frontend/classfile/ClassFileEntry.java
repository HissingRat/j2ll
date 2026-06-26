package xyz.melodysky.frontend.classfile;

import java.util.Arrays;
import java.util.Objects;

public final class ClassFileEntry implements Comparable<ClassFileEntry> {
    private final String entryName;
    private final byte[] bytes;
    private final String sourceDescription;

    public ClassFileEntry(String entryName, byte[] bytes, String sourceDescription) {
        this.entryName = requireEntryName(entryName);
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        this.sourceDescription = Objects.requireNonNull(sourceDescription, "sourceDescription");
    }

    public String entryName() {
        return entryName;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String sourceDescription() {
        return sourceDescription;
    }

    @Override
    public int compareTo(ClassFileEntry other) {
        return entryName.compareTo(other.entryName);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ClassFileEntry that)) {
            return false;
        }
        return entryName.equals(that.entryName)
                && Arrays.equals(bytes, that.bytes)
                && sourceDescription.equals(that.sourceDescription);
    }

    @Override
    public int hashCode() {
        int result = entryName.hashCode();
        result = 31 * result + Arrays.hashCode(bytes);
        result = 31 * result + sourceDescription.hashCode();
        return result;
    }

    private static String requireEntryName(String entryName) {
        Objects.requireNonNull(entryName, "entryName");
        if (entryName.isBlank()) {
            throw new IllegalArgumentException("class file entry name must not be blank");
        }
        return entryName;
    }
}
