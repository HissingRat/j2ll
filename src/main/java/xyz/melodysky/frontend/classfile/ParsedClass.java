package xyz.melodysky.frontend.classfile;

import java.util.List;
import java.util.Objects;
import org.objectweb.asm.tree.ClassNode;
import xyz.melodysky.jvm.AccessFlags;

public record ParsedClass(
        String internalName,
        AccessFlags accessFlags,
        int majorVersion,
        int minorVersion,
        String superName,
        List<String> interfaces,
        List<ParsedField> fields,
        List<ParsedMethod> methods,
        String sourceEntry,
        String sourceDescription,
        ClassNode classNode) {
    public ParsedClass {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(accessFlags, "accessFlags");
        interfaces = List.copyOf(Objects.requireNonNull(interfaces, "interfaces"));
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
        Objects.requireNonNull(sourceEntry, "sourceEntry");
        Objects.requireNonNull(sourceDescription, "sourceDescription");
        Objects.requireNonNull(classNode, "classNode");
    }

    public boolean isInterface() {
        return accessFlags.isInterface();
    }

    public boolean isAnnotation() {
        return accessFlags.isAnnotation();
    }
}
