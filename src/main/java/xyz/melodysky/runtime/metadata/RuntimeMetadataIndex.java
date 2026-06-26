package xyz.melodysky.runtime.metadata;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RuntimeMetadataIndex(List<ClassMetadata> classes) {
    public RuntimeMetadataIndex {
        classes = classes.stream()
                .filter(Objects::nonNull)
                .sorted(ClassMetadata.ORDERING)
                .toList();
    }

    public Optional<ClassMetadata> findClass(String internalName) {
        return classes.stream()
                .filter(metadata -> metadata.internalName().equals(internalName))
                .findFirst();
    }

    public Optional<MethodMetadata> findMethod(String owner, String name, String descriptor) {
        return findClass(owner).stream()
                .flatMap(metadata -> metadata.methods().stream())
                .filter(method -> method.name().equals(name) && method.descriptor().equals(descriptor))
                .findFirst();
    }

    public Optional<FieldMetadata> findField(String owner, String name, String descriptor) {
        return findClass(owner).stream()
                .flatMap(metadata -> metadata.fields().stream())
                .filter(field -> field.name().equals(name) && field.descriptor().equals(descriptor))
                .findFirst();
    }

    public List<MethodMetadata> allMethods() {
        return classes.stream()
                .flatMap(metadata -> metadata.methods().stream())
                .sorted(Comparator
                        .comparing(MethodMetadata::owner)
                        .thenComparing(MethodMetadata::name)
                        .thenComparing(MethodMetadata::descriptor))
                .toList();
    }
}
