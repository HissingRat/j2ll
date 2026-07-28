package xyz.melodysky.toolchain;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import xyz.melodysky.backend.llvm.model.LlvmDeclaration;

/**
 * Runtime source-family closure rooted in the final LLVM module model.
 *
 * <p>Unrecognized runtime references fail closed to the conservative all-family
 * plan. Declarations do not make a helper reachable; they only classify
 * build-scoped localized helper symbols that are actually referenced by the
 * final model.</p>
 */
final class RuntimeHelperReachabilityPlan {
    private final Set<String> rootSymbols;
    private final EnumSet<HostJniRuntimeSourceFamily> families;
    private final boolean conservative;

    private RuntimeHelperReachabilityPlan(
            Set<String> rootSymbols,
            EnumSet<HostJniRuntimeSourceFamily> families,
            boolean conservative) {
        this.rootSymbols = Set.copyOf(rootSymbols);
        this.families = families.clone();
        this.conservative = conservative;
    }

    static RuntimeHelperReachabilityPlan conservative() {
        return new RuntimeHelperReachabilityPlan(
                Set.of(),
                EnumSet.allOf(HostJniRuntimeSourceFamily.class),
                true);
    }

    static RuntimeHelperReachabilityPlan from(
            NativeLlvmCompilation compilation) {
        Objects.requireNonNull(compilation, "compilation");
        LlvmModelSymbolReferenceCollector.Result references =
                new LlvmModelSymbolReferenceCollector().collect(
                        compilation);
        if (!references.complete()) {
            return conservative();
        }
        Map<String, String> comments = declarationComments(compilation);
        EnumSet<HostJniRuntimeSourceFamily> families =
                EnumSet.noneOf(HostJniRuntimeSourceFamily.class);
        TreeSet<String> roots = new TreeSet<>();
        HostJniRuntimeSourceClassifier classifier =
                new HostJniRuntimeSourceClassifier();
        for (String reference : references.references()) {
            HostJniRuntimeSourceClassifier.Classification
                    classification = classifier.classify(
                            reference,
                            comments.get(reference));
            if (!classification.runtimeReference()) {
                continue;
            }
            roots.add(reference);
            if (!classification.recognized()) {
                return new RuntimeHelperReachabilityPlan(
                        roots,
                        EnumSet.allOf(
                                HostJniRuntimeSourceFamily.class),
                        true);
            }
            families.addAll(classification.families());
        }
        return new RuntimeHelperReachabilityPlan(
                roots,
                families,
                false);
    }

    boolean emits(HostJniRuntimeSourceFamily family) {
        return families.contains(Objects.requireNonNull(family, "family"));
    }

    Set<String> rootSymbols() {
        return rootSymbols;
    }

    Set<HostJniRuntimeSourceFamily> families() {
        return Set.copyOf(families);
    }

    boolean isConservative() {
        return conservative;
    }

    private static Map<String, String> declarationComments(
            NativeLlvmCompilation compilation) {
        LinkedHashMap<String, String> comments = new LinkedHashMap<>();
        for (NativeLlvmModuleCompilation module : compilation.modules()) {
            for (LlvmDeclaration declaration
                    : module.module().declarations()) {
                String comment = declaration.comment() == null
                        ? ""
                        : declaration.comment();
                String previous = comments.putIfAbsent(
                        declaration.name(),
                        comment);
                if (previous != null
                        && !Objects.equals(previous, comment)) {
                    comments.put(declaration.name(), "");
                }
            }
        }
        return java.util.Collections.unmodifiableMap(comments);
    }

}
