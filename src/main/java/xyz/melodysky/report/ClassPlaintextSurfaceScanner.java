package xyz.melodysky.report;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

final class ClassPlaintextSurfaceScanner {
    ScanResult scan(byte[] classBytes, Collection<String> forbiddenPlaintexts) {
        Objects.requireNonNull(classBytes, "classBytes");
        Set<String> forbidden = forbiddenPlaintexts.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (forbidden.isEmpty()) {
            return new ScanResult(Set.of(), false);
        }

        try {
            ClassNode classNode = new ClassNode(Opcodes.ASM9);
            new ClassReader(classBytes).accept(
                    classNode,
                    ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            collectAnnotations(classNode.visibleAnnotations, candidates);
            collectAnnotations(classNode.invisibleAnnotations, candidates);
            collectAnnotations(classNode.visibleTypeAnnotations, candidates);
            collectAnnotations(classNode.invisibleTypeAnnotations, candidates);
            for (RecordComponentNode component : classNode.recordComponents == null
                    ? List.<RecordComponentNode>of()
                    : classNode.recordComponents) {
                collectAnnotations(component.visibleAnnotations, candidates);
                collectAnnotations(component.invisibleAnnotations, candidates);
                collectAnnotations(component.visibleTypeAnnotations, candidates);
                collectAnnotations(component.invisibleTypeAnnotations, candidates);
            }
            for (FieldNode field : classNode.fields) {
                collectRuntimeValue(field.value, candidates, newIdentitySet());
                collectAnnotations(field.visibleAnnotations, candidates);
                collectAnnotations(field.invisibleAnnotations, candidates);
                collectAnnotations(field.visibleTypeAnnotations, candidates);
                collectAnnotations(field.invisibleTypeAnnotations, candidates);
            }
            for (MethodNode method : classNode.methods) {
                collectMethod(method, candidates);
            }
            return new ScanResult(intersection(candidates, forbidden), false);
        } catch (RuntimeException exception) {
            String raw = new String(classBytes, StandardCharsets.ISO_8859_1);
            LinkedHashSet<String> matches = forbidden.stream()
                    .filter(raw::contains)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return new ScanResult(matches, true);
        }
    }

    private void collectMethod(MethodNode method, Set<String> candidates) {
        collectRuntimeValue(method.annotationDefault, candidates, newIdentitySet());
        collectAnnotations(method.visibleAnnotations, candidates);
        collectAnnotations(method.invisibleAnnotations, candidates);
        collectAnnotations(method.visibleTypeAnnotations, candidates);
        collectAnnotations(method.invisibleTypeAnnotations, candidates);
        collectParameterAnnotations(method.visibleParameterAnnotations, candidates);
        collectParameterAnnotations(method.invisibleParameterAnnotations, candidates);
        collectAnnotations(method.visibleLocalVariableAnnotations, candidates);
        collectAnnotations(method.invisibleLocalVariableAnnotations, candidates);
        for (TryCatchBlockNode tryCatch : method.tryCatchBlocks) {
            collectAnnotations(tryCatch.visibleTypeAnnotations, candidates);
            collectAnnotations(tryCatch.invisibleTypeAnnotations, candidates);
        }
        for (AbstractInsnNode instruction : method.instructions) {
            collectAnnotations(instruction.visibleTypeAnnotations, candidates);
            collectAnnotations(instruction.invisibleTypeAnnotations, candidates);
            if (instruction instanceof LdcInsnNode ldc) {
                collectRuntimeValue(ldc.cst, candidates, newIdentitySet());
            } else if (instruction instanceof InvokeDynamicInsnNode invokeDynamic) {
                Set<Object> visited = newIdentitySet();
                for (Object argument : invokeDynamic.bsmArgs) {
                    collectRuntimeValue(argument, candidates, visited);
                }
            }
        }
    }

    private void collectParameterAnnotations(
            List<AnnotationNode>[] parameterAnnotations,
            Set<String> candidates) {
        if (parameterAnnotations == null) {
            return;
        }
        for (List<AnnotationNode> annotations : parameterAnnotations) {
            collectAnnotations(annotations, candidates);
        }
    }

    private void collectAnnotations(
            List<? extends AnnotationNode> annotations,
            Set<String> candidates) {
        if (annotations == null) {
            return;
        }
        for (AnnotationNode annotation : annotations) {
            collectAnnotation(annotation, candidates, newIdentitySet());
        }
    }

    private void collectAnnotation(
            AnnotationNode annotation,
            Set<String> candidates,
            Set<Object> visited) {
        if (annotation == null || !visited.add(annotation) || annotation.values == null) {
            return;
        }
        for (int index = 1; index < annotation.values.size(); index += 2) {
            collectRuntimeValue(annotation.values.get(index), candidates, visited);
        }
    }

    private void collectRuntimeValue(
            Object value,
            Set<String> candidates,
            Set<Object> visited) {
        if (value == null) {
            return;
        }
        if (value instanceof String string) {
            candidates.add(string);
            return;
        }
        if (value instanceof Type) {
            return;
        }
        if (value instanceof ConstantDynamic constantDynamic) {
            if (!visited.add(constantDynamic)) {
                return;
            }
            for (int index = 0; index < constantDynamic.getBootstrapMethodArgumentCount(); index++) {
                collectRuntimeValue(
                        constantDynamic.getBootstrapMethodArgument(index),
                        candidates,
                        visited);
            }
            return;
        }
        if (value instanceof AnnotationNode annotation) {
            collectAnnotation(annotation, candidates, visited);
            return;
        }
        if (value instanceof String[] enumValue) {
            if (enumValue.length > 1) {
                candidates.add(enumValue[1]);
            }
            return;
        }
        if (value instanceof List<?> values) {
            if (!visited.add(value)) {
                return;
            }
            for (Object element : values) {
                collectRuntimeValue(element, candidates, visited);
            }
        }
    }

    private Set<Object> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private Set<String> intersection(Set<String> candidates, Set<String> forbidden) {
        ArrayList<String> matches = new ArrayList<>();
        for (String plaintext : forbidden) {
            if (candidates.stream().anyMatch(candidate -> candidate.contains(plaintext))) {
                matches.add(plaintext);
            }
        }
        Collections.sort(matches);
        return Set.copyOf(matches);
    }

    record ScanResult(Set<String> matches, boolean rawFallback) {
        ScanResult {
            matches = Set.copyOf(matches);
        }
    }
}
