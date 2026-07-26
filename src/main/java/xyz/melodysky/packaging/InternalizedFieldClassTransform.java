package xyz.melodysky.packaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.field.NativeFieldStorageKind;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;

/**
 * Removes fields only after consuming the same immutable plan used by the IR
 * and native-storage stages.
 */
public final class InternalizedFieldClassTransform {
    public InternalizedFieldTransformResult apply(
            byte[] rewrittenClassBytes,
            String expectedOwner,
            NativeFieldInternalizationPlan plan) {
        ClassNode node = read(rewrittenClassBytes);
        boolean rewrittenOwnerHasClassInitializer = node.methods.stream()
                .anyMatch(method -> method.name.equals("<clinit>"));
        return apply(
                rewrittenClassBytes,
                expectedOwner,
                plan,
                rewrittenOwnerHasClassInitializer);
    }

    /**
     * Applies the removal contract using the source-class initializer fact.
     *
     * <p>The source owner may have an unrelated {@code <clinit>}. The field
     * planner separately rejects any candidate that is actually accessed by
     * that initializer, so owner-wide initializer presence is not a removal
     * blocker.
     */
    public InternalizedFieldTransformResult apply(
            byte[] rewrittenClassBytes,
            String expectedOwner,
            NativeFieldInternalizationPlan plan,
            boolean sourceOwnerHasClassInitializer) {
        Set<FieldId> approved = plan.approvedFieldIds().stream()
                .filter(field -> field.owner().equals(expectedOwner))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (approved.isEmpty()) {
            return new InternalizedFieldTransformResult(
                    rewrittenClassBytes,
                    List.of(),
                    List.of());
        }
        ClassNode node = read(rewrittenClassBytes);
        ArrayList<String> removed = new ArrayList<>();
        ArrayList<String> seen = new ArrayList<>();
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        ArrayList<FieldNode> retained = new ArrayList<>();
        for (FieldNode field : node.fields) {
            FieldId id = new FieldId(node.name, field.name, field.desc);
            if (!approved.contains(id)) {
                retained.add(field);
                continue;
            }
            seen.add(id.fieldKey());
            boolean structurallySafe = (field.access & Opcodes.ACC_PRIVATE) != 0
                    && (field.access & Opcodes.ACC_STATIC) != 0
                    && (field.access
                                    & (Opcodes.ACC_FINAL
                                            | Opcodes.ACC_VOLATILE
                                            | Opcodes.ACC_SYNTHETIC
                                            | Opcodes.ACC_ENUM))
                            == 0
                    && field.value == null
                    && field.signature == null
                    && !hasEntries(field.visibleAnnotations)
                    && !hasEntries(field.invisibleAnnotations)
                    && !hasEntries(field.visibleTypeAnnotations)
                    && !hasEntries(field.invisibleTypeAnnotations)
                    && NativeFieldStorageKind.fromDescriptor(field.desc).isPresent();
            if (!structurallySafe) {
                retained.add(field);
                diagnostics.add(Diagnostic.error(
                        DiagnosticStage.PACKAGING,
                        PackagingDiagnostics.FIELD_INTERNALIZATION_REWRITE_FAILED,
                        "approved field no longer satisfies removal contract: fieldIdHash="
                                + FieldPrivacyHash.sha256(id.fieldKey())));
                continue;
            }
            removed.add(id.fieldKey());
        }
        node.fields = retained;
        for (FieldId expected : approved) {
            if (!seen.contains(expected.fieldKey())) {
                diagnostics.add(Diagnostic.error(
                        DiagnosticStage.PACKAGING,
                        PackagingDiagnostics.FIELD_INTERNALIZATION_REWRITE_FAILED,
                        "approved field was not found in rewritten class: fieldIdHash="
                                + FieldPrivacyHash.sha256(expected.fieldKey())));
            }
        }
        if (!diagnostics.isEmpty()) {
            return new InternalizedFieldTransformResult(
                    rewrittenClassBytes,
                    List.of(),
                    diagnostics);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return new InternalizedFieldTransformResult(
                writer.toByteArray(),
                removed,
                List.of());
    }

    private boolean hasEntries(List<?> entries) {
        return entries != null && !entries.isEmpty();
    }

    private ClassNode read(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }
}
