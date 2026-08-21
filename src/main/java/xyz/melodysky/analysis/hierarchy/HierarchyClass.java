package xyz.melodysky.analysis.hierarchy;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.jvm.MethodSignature;

public record HierarchyClass(
        String internalName,
        AccessFlags accessFlags,
        String superName,
        List<String> interfaces,
        List<HierarchyMethod> methods,
        List<HierarchyField> fields,
        boolean external) {
    public HierarchyClass {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(accessFlags, "accessFlags");
        interfaces = interfaces.stream().filter(Objects::nonNull).sorted().toList();
        methods = methods.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(HierarchyMethod::signature))
                .toList();
        fields = fields.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(HierarchyField::signature))
                .toList();
    }

    public static HierarchyClass externalPlaceholder(String internalName) {
        return new HierarchyClass(
                internalName,
                new AccessFlags(0),
                null,
                List.of(),
                List.of(),
                List.of(),
                true);
    }

    public boolean isInterface() {
        return accessFlags.isInterface();
    }

    public boolean isFinal() {
        return accessFlags.isFinal();
    }

    public boolean isAbstract() {
        return accessFlags.isAbstract();
    }

    public Optional<HierarchyMethod> declaresMethod(MethodSignature signature) {
        return methods.stream()
                .filter(method -> method.signature().equals(signature))
                .findFirst();
    }
}
