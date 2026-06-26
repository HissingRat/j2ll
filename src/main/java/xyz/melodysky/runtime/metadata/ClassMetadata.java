package xyz.melodysky.runtime.metadata;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record ClassMetadata(
        String internalName,
        String binaryName,
        List<String> accessFlags,
        List<String> compilerFlags,
        int majorVersion,
        int minorVersion,
        String superName,
        List<String> interfaces,
        SignatureMetadata signature,
        List<AnnotationMetadata> annotations,
        List<FieldMetadata> fields,
        List<MethodMetadata> methods,
        RecordMetadata recordMetadata,
        NestMetadata nestMetadata,
        List<InnerClassMetadata> innerClasses,
        ClassInitMetadata classInitMetadata) implements Comparable<ClassMetadata> {
    public ClassMetadata {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(binaryName, "binaryName");
        accessFlags = accessFlags.stream().filter(Objects::nonNull).sorted().distinct().toList();
        compilerFlags = compilerFlags.stream().filter(Objects::nonNull).sorted().distinct().toList();
        interfaces = interfaces.stream().filter(Objects::nonNull).sorted().distinct().toList();
        Objects.requireNonNull(signature, "signature");
        annotations = annotations.stream().filter(Objects::nonNull).sorted().toList();
        fields = fields.stream().filter(Objects::nonNull).sorted().toList();
        methods = methods.stream().filter(Objects::nonNull).sorted().toList();
        Objects.requireNonNull(recordMetadata, "recordMetadata");
        Objects.requireNonNull(nestMetadata, "nestMetadata");
        innerClasses = innerClasses.stream().filter(Objects::nonNull).sorted().toList();
        Objects.requireNonNull(classInitMetadata, "classInitMetadata");
    }

    public boolean isRecord() {
        return compilerFlags.contains("record");
    }

    public List<MethodMetadata> constructors() {
        return methods.stream().filter(method -> method.name().equals("<init>")).toList();
    }

    @Override
    public int compareTo(ClassMetadata other) {
        return internalName.compareTo(other.internalName);
    }

    static final Comparator<ClassMetadata> ORDERING = Comparator.comparing(ClassMetadata::internalName);
}
