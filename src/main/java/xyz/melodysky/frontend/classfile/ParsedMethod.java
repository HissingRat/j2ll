package xyz.melodysky.frontend.classfile;

import java.util.List;
import java.util.Objects;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.jvm.AccessFlags;

public record ParsedMethod(
        String owner,
        String name,
        String descriptor,
        AccessFlags accessFlags,
        List<ParsedField> ownerFields,
        List<String> exceptions,
        List<ParsedExceptionHandler> exceptionHandlers,
        boolean hasCode,
        int maxLocals,
        int maxStack,
        MethodNode methodNode) {
    public ParsedMethod {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(accessFlags, "accessFlags");
        ownerFields = List.copyOf(Objects.requireNonNull(ownerFields, "ownerFields"));
        exceptions = List.copyOf(Objects.requireNonNull(exceptions, "exceptions"));
        exceptionHandlers = List.copyOf(Objects.requireNonNull(exceptionHandlers, "exceptionHandlers"));
        Objects.requireNonNull(methodNode, "methodNode");
    }

    public String methodKey() {
        return owner + "#" + name + "!" + descriptor;
    }
}
