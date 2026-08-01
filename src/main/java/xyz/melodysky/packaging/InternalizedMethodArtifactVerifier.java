package xyz.melodysky.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import xyz.melodysky.analysis.method.NativeMethodId;
import xyz.melodysky.analysis.method.NativeMethodInternalizationDecision;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlan;

/**
 * Final-JAR verifier for plan-approved method removal.
 *
 * <p>It checks declarations, direct bytecode invocations, method handles,
 * recursive dynamic constants, invokedynamic bootstrap surfaces, and
 * EnclosingMethod metadata. Findings contain only stable hashes of locations
 * and method identities.</p>
 */
public final class InternalizedMethodArtifactVerifier {
    public List<String> residuals(
            JarFile jar,
            NativeMethodInternalizationPlan plan) throws IOException {
        Set<NativeMethodId> approved = plan.decisions().stream()
                .filter(NativeMethodInternalizationDecision::internalized)
                .map(NativeMethodInternalizationDecision::method)
                .collect(Collectors.toUnmodifiableSet());
        if (approved.isEmpty()) {
            return List.of();
        }

        ArrayList<String> residuals = new ArrayList<>();
        for (var entry : jar.stream()
                .filter(candidate -> !candidate.isDirectory())
                .filter(candidate -> candidate.getName().endsWith(".class"))
                .sorted(java.util.Comparator.comparing(
                        java.util.jar.JarEntry::getName))
                .toList()) {
            ClassNode node = new ClassNode();
            try (InputStream input = jar.getInputStream(entry)) {
                new ClassReader(input).accept(
                        node,
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            collectDeclarations(node, approved, entry.getName(), residuals);
            collectEnclosingMethod(node, approved, entry.getName(), residuals);
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

    private void collectDeclarations(
            ClassNode node,
            Set<NativeMethodId> approved,
            String entry,
            List<String> residuals) {
        node.methods.stream()
                .map(method -> new NativeMethodId(
                        node.name,
                        method.name,
                        method.desc))
                .filter(approved::contains)
                .forEach(method -> addResidual(
                        entry + ":declaration",
                        method,
                        "declaration",
                        residuals));
    }

    private void collectEnclosingMethod(
            ClassNode node,
            Set<NativeMethodId> approved,
            String entry,
            List<String> residuals) {
        if (node.outerClass == null
                || node.outerMethod == null
                || node.outerMethodDesc == null) {
            return;
        }
        addIfApproved(
                new NativeMethodId(
                        node.outerClass,
                        node.outerMethod,
                        node.outerMethodDesc),
                approved,
                entry + ":enclosingMethod",
                "enclosingMethod",
                residuals);
    }

    private void collectInstructionReferences(
            AbstractInsnNode instruction,
            Set<NativeMethodId> approved,
            String entry,
            String method,
            int instructionIndex,
            List<String> residuals) {
        if (instruction instanceof MethodInsnNode invocation) {
            addIfApproved(
                    new NativeMethodId(
                            invocation.owner,
                            invocation.name,
                            invocation.desc),
                    approved,
                    location(
                            entry,
                            method,
                            instructionIndex,
                            "methodInstruction"),
                    "methodInstruction",
                    residuals);
            return;
        }
        if (instruction instanceof LdcInsnNode ldc) {
            collectConstant(
                    ldc.cst,
                    approved,
                    location(entry, method, instructionIndex, "ldc"),
                    "ldcHandle",
                    residuals);
            return;
        }
        if (instruction instanceof InvokeDynamicInsnNode dynamic) {
            collectHandle(
                    dynamic.bsm,
                    approved,
                    location(
                            entry,
                            method,
                            instructionIndex,
                            "invokedynamicBootstrap"),
                    "invokedynamicBootstrap",
                    residuals);
            for (int index = 0; index < dynamic.bsmArgs.length; index++) {
                collectConstant(
                        dynamic.bsmArgs[index],
                        approved,
                        location(
                                entry,
                                method,
                                instructionIndex,
                                "invokedynamicArgument[" + index + "]"),
                        "invokedynamicArgument",
                        residuals);
            }
        }
    }

    private void collectConstant(
            Object value,
            Set<NativeMethodId> approved,
            String location,
            String directHandleKind,
            List<String> residuals) {
        if (value instanceof Handle handle) {
            collectHandle(
                    handle,
                    approved,
                    location,
                    directHandleKind,
                    residuals);
            return;
        }
        if (!(value instanceof ConstantDynamic dynamic)) {
            return;
        }
        collectHandle(
                dynamic.getBootstrapMethod(),
                approved,
                location + ":constantDynamicBootstrap",
                "constantDynamicBootstrap",
                residuals);
        for (int index = 0;
                index < dynamic.getBootstrapMethodArgumentCount();
                index++) {
            collectConstant(
                    dynamic.getBootstrapMethodArgument(index),
                    approved,
                    location + ":constantDynamicArgument[" + index + "]",
                    "constantDynamicArgument",
                    residuals);
        }
    }

    private void collectHandle(
            Handle handle,
            Set<NativeMethodId> approved,
            String location,
            String kind,
            List<String> residuals) {
        if (handle == null || !isMethodHandle(handle.getTag())) {
            return;
        }
        addIfApproved(
                new NativeMethodId(
                        handle.getOwner(),
                        handle.getName(),
                        handle.getDesc()),
                approved,
                location,
                kind,
                residuals);
    }

    private boolean isMethodHandle(int tag) {
        return tag == Opcodes.H_INVOKEVIRTUAL
                || tag == Opcodes.H_INVOKESTATIC
                || tag == Opcodes.H_INVOKESPECIAL
                || tag == Opcodes.H_NEWINVOKESPECIAL
                || tag == Opcodes.H_INVOKEINTERFACE;
    }

    private void addIfApproved(
            NativeMethodId method,
            Set<NativeMethodId> approved,
            String location,
            String kind,
            List<String> residuals) {
        if (approved.contains(method)) {
            addResidual(location, method, kind, residuals);
        }
    }

    private void addResidual(
            String location,
            NativeMethodId method,
            String kind,
            List<String> residuals) {
        residuals.add("locationHash=" + FieldPrivacyHash.sha256(location)
                + " kind=" + kind
                + " methodIdHash="
                + FieldPrivacyHash.sha256(method.methodKey()));
    }

    private String location(
            String entry,
            String method,
            int instructionIndex,
            String kind) {
        return entry + ":" + method + "@" + instructionIndex + " " + kind;
    }
}
