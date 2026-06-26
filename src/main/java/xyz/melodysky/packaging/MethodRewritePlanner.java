package xyz.melodysky.packaging;

import java.util.List;
import java.util.Optional;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.toolchain.ClassArtifactPath;

public final class MethodRewritePlanner {
    private final ClassArtifactPath artifactPath = new ClassArtifactPath();

    public List<MethodRewriteDecision> planClass(ParsedClass parsedClass) {
        return parsedClass.methods().stream()
                .map(method -> planMethod(parsedClass, method))
                .toList();
    }

    public MethodRewriteDecision planMethod(ParsedClass parsedClass, ParsedMethod method) {
        Optional<String> notApplicableReason = notApplicableReason(parsedClass, method);
        if (notApplicableReason.isPresent()) {
            return new MethodRewriteDecision(
                    method,
                    MethodRewriteStrategy.NOT_APPLICABLE,
                    parsedClass.internalName(),
                    Optional.empty(),
                    notApplicableReason.orElseThrow());
        }
        if (method.name().equals("<init>")) {
            return new MethodRewriteDecision(
                    method,
                    MethodRewriteStrategy.CONSTRUCTOR_STUB,
                    parsedClass.internalName(),
                    Optional.of("__j2ll_init_body$" + methodId(method)),
                    null);
        }
        if (method.name().equals("<clinit>")) {
            return new MethodRewriteDecision(
                    method,
                    MethodRewriteStrategy.CLASS_INITIALIZER_STUB,
                    parsedClass.internalName(),
                    Optional.of("__j2ll_clinit_body$" + methodId(method)),
                    null);
        }
        if (parsedClass.isInterface()) {
            String helper = helperOwner(parsedClass, "InterfaceMethods");
            return new MethodRewriteDecision(
                    method,
                    MethodRewriteStrategy.INTERFACE_METHOD_STUB,
                    helper,
                    Optional.of("__j2ll_interface_body$" + methodId(method)),
                    null);
        }
        return new MethodRewriteDecision(
                method,
                MethodRewriteStrategy.NATIVE_ORIGINAL,
                parsedClass.internalName(),
                Optional.empty(),
                null);
    }

    private Optional<String> notApplicableReason(ParsedClass parsedClass, ParsedMethod method) {
        if (method.accessFlags().isNative()) {
            return Optional.of("ALREADY_NATIVE");
        }
        if (method.accessFlags().isAbstract()) {
            return Optional.of("ABSTRACT_OR_NO_CODE");
        }
        if (!method.hasCode() && parsedClass.isInterface()) {
            return Optional.of("INTERFACE_NO_CODE");
        }
        if (!method.hasCode()) {
            return Optional.of("NO_CODE");
        }
        return Optional.empty();
    }

    private String helperOwner(ParsedClass parsedClass, String suffix) {
        return "j2ll/generated/" + parsedClass.internalName().replace('/', '_').replace('$', '_') + "/" + suffix;
    }

    private String methodId(ParsedMethod method) {
        return artifactPath.methodId(method.owner(), method.name(), method.descriptor());
    }
}
