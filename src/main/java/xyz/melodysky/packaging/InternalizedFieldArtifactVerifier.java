package xyz.melodysky.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;

/**
 * Final-JAR verifier for plan-approved field removal.
 *
 * <p>It checks both declarations and every bytecode/bootstrap field reference.
 * This runs after repackaging and before the final artifact is retained.</p>
 */
public final class InternalizedFieldArtifactVerifier {
    public List<String> residuals(
            JarFile jar,
            NativeFieldInternalizationPlan plan) throws IOException {
        Set<FieldId> approved = plan.approvedFieldIds();
        if (approved.isEmpty()) {
            return List.of();
        }
        ArrayList<String> residuals = new ArrayList<>();
        for (var entry : jar.stream()
                .filter(candidate -> !candidate.isDirectory())
                .filter(candidate -> candidate.getName().endsWith(".class"))
                .sorted(java.util.Comparator.comparing(java.util.jar.JarEntry::getName))
                .toList()) {
            ClassNode node = new ClassNode();
            try (InputStream input = jar.getInputStream(entry)) {
                new ClassReader(input).accept(
                        node,
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            node.fields.stream()
                    .map(field -> new FieldId(node.name, field.name, field.desc))
                    .filter(approved::contains)
                    .forEach(field -> residuals.add(
                            residual(
                                    entry.getName() + ":declaration",
                                    field,
                                    "declaration")));
            node.methods.forEach(method -> {
                int instructionIndex = 0;
                for (AbstractInsnNode instruction : method.instructions) {
                    collectInstructionReferences(
                            instruction,
                            approved,
                            entry.getName(),
                            method.name + method.desc,
                            instructionIndex,
                            residuals);
                    instructionIndex++;
                }
            });
        }
        return residuals.stream().sorted().distinct().toList();
    }

    private void collectInstructionReferences(
            AbstractInsnNode instruction,
            Set<FieldId> approved,
            String entry,
            String method,
            int instructionIndex,
            List<String> residuals) {
        if (instruction instanceof FieldInsnNode field) {
            addIfApproved(
                    new FieldId(field.owner, field.name, field.desc),
                    approved,
                    location(entry, method, instructionIndex, "field instruction"),
                    residuals);
            return;
        }
        if (instruction instanceof LdcInsnNode ldc) {
            collectConstant(
                    ldc.cst,
                    approved,
                    location(entry, method, instructionIndex, "ldc"),
                    residuals);
            return;
        }
        if (instruction instanceof InvokeDynamicInsnNode dynamic) {
            collectHandle(
                    dynamic.bsm,
                    approved,
                    location(entry, method, instructionIndex, "invokedynamic bootstrap"),
                    residuals);
            for (Object argument : dynamic.bsmArgs) {
                collectConstant(
                        argument,
                        approved,
                        location(entry, method, instructionIndex, "invokedynamic argument"),
                        residuals);
            }
        }
    }

    private void collectConstant(
            Object value,
            Set<FieldId> approved,
            String location,
            List<String> residuals) {
        if (value instanceof Handle handle) {
            collectHandle(handle, approved, location, residuals);
        } else if (value instanceof ConstantDynamic dynamic) {
            collectHandle(dynamic.getBootstrapMethod(), approved, location, residuals);
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                collectConstant(
                        dynamic.getBootstrapMethodArgument(index),
                        approved,
                        location,
                        residuals);
            }
        }
    }

    private void collectHandle(
            Handle handle,
            Set<FieldId> approved,
            String location,
            List<String> residuals) {
        if (handle == null || !isFieldHandle(handle.getTag())) {
            return;
        }
        addIfApproved(
                new FieldId(handle.getOwner(), handle.getName(), handle.getDesc()),
                approved,
                location + " field handle",
                residuals);
    }

    private boolean isFieldHandle(int tag) {
        return tag == Opcodes.H_GETFIELD
                || tag == Opcodes.H_GETSTATIC
                || tag == Opcodes.H_PUTFIELD
                || tag == Opcodes.H_PUTSTATIC;
    }

    private void addIfApproved(
            FieldId field,
            Set<FieldId> approved,
            String location,
            List<String> residuals) {
        if (approved.contains(field)) {
            residuals.add(residual(location, field, "reference"));
        }
    }

    private String residual(String location, FieldId field, String kind) {
        return "locationHash=" + FieldPrivacyHash.sha256(location)
                + " kind=" + kind
                + " fieldIdHash=" + FieldPrivacyHash.sha256(field.fieldKey());
    }

    private String location(
            String entry,
            String method,
            int instructionIndex,
            String kind) {
        return entry + ":" + method + "@" + instructionIndex + " " + kind;
    }
}
