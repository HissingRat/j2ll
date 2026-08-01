package xyz.melodysky.packaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.analysis.method.NativeMethodId;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlan;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;

/** Removes only methods approved by the immutable final internalization plan. */
public final class InternalizedMethodClassTransform {
    public Result apply(
            byte[] classBytes,
            String owner,
            NativeMethodInternalizationPlan plan) {
        Objects.requireNonNull(classBytes, "classBytes");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(plan, "plan");
        List<NativeMethodId> targets = plan.decisions().stream()
                .filter(decision -> decision.internalized()
                        && decision.method().owner().equals(owner))
                .map(decision -> decision.method())
                .sorted()
                .toList();
        if (targets.isEmpty()) {
            return new Result(
                    classBytes,
                    List.of(),
                    Set.of());
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        ArrayList<MethodNode> methods = new ArrayList<>();
        for (NativeMethodId target : targets) {
            List<MethodNode> matches = node.methods.stream()
                    .filter(method -> method.name.equals(target.name())
                            && method.desc.equals(target.descriptor()))
                    .toList();
            if (matches.size() != 1) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.PACKAGING,
                                PackagingDiagnostics
                                        .METHOD_INTERNALIZATION_REWRITE_FAILED,
                                "approved internalized method was not found exactly once")
                        .at(DiagnosticLocation.methodLocation(
                                target.owner(),
                                target.name(),
                                target.descriptor())));
            } else {
                methods.add(matches.get(0));
            }
        }
        if (!diagnostics.isEmpty()) {
            return new Result(
                    classBytes,
                    diagnostics,
                    Set.of());
        }
        node.methods.removeAll(methods);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return new Result(
                writer.toByteArray(),
                List.of(),
                targets.stream()
                        .map(NativeMethodId::methodKey)
                        .collect(java.util.stream.Collectors
                                .toUnmodifiableSet()));
    }

    public record Result(
            byte[] classBytes,
            List<Diagnostic> diagnostics,
            Set<String> removedMethodKeys) {
        public Result {
            classBytes = Objects.requireNonNull(
                    classBytes,
                    "classBytes");
            diagnostics = List.copyOf(
                    Objects.requireNonNull(
                            diagnostics,
                            "diagnostics"));
            removedMethodKeys = Set.copyOf(
                    Objects.requireNonNull(
                            removedMethodKeys,
                            "removedMethodKeys"));
        }
    }
}
