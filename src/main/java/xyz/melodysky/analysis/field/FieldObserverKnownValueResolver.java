package xyz.melodysky.analysis.field;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.SourceValue;

/** Resolves constant String/Class values without carrying field semantics. */
final class FieldObserverKnownValueResolver implements Opcodes {
    private static final int MAX_RESOLUTION_DEPTH = 128;

    private final FieldObserverFrameView frames;
    private final FieldObserverSourceInterpreter interpreter;

    FieldObserverKnownValueResolver(
            FieldObserverFrameView frames,
            FieldObserverSourceInterpreter interpreter) {
        this.frames = frames;
        this.interpreter = interpreter;
    }

    Set<String> strings(SourceValue value, FieldObserverResolutionBudget budget) {
        return resolve(value, ValueKind.STRING, new IdentityHashMap<>(), budget);
    }

    Set<String> classOwners(SourceValue value, FieldObserverResolutionBudget budget) {
        return resolve(value, ValueKind.CLASS_OWNER, new IdentityHashMap<>(), budget);
    }

    Set<String> classDescriptors(SourceValue value, FieldObserverResolutionBudget budget) {
        return resolve(value, ValueKind.CLASS_DESCRIPTOR, new IdentityHashMap<>(), budget);
    }

    private Set<String> resolve(
            SourceValue value,
            ValueKind kind,
            IdentityHashMap<SourceValue, Boolean> visiting,
            FieldObserverResolutionBudget budget) {
        if (value == null
                || visiting.size() >= MAX_RESOLUTION_DEPTH
                || visiting.containsKey(value)
                || !budget.tryConsume()) {
            return null;
        }
        visiting.put(value, Boolean.TRUE);
        try {
            if (value.insns.isEmpty()) {
                return null;
            }
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (AbstractInsnNode source : value.insns) {
                Set<String> fromSource = resolveSource(source, kind, visiting, budget);
                if (fromSource == null) {
                    return null;
                }
                values.addAll(fromSource);
            }
            return Set.copyOf(values);
        } finally {
            visiting.remove(value);
        }
    }

    private Set<String> resolveSource(
            AbstractInsnNode source,
            ValueKind kind,
            IdentityHashMap<SourceValue, Boolean> visiting,
            FieldObserverResolutionBudget budget) {
        if (interpreter.isUnknownSource(source)) {
            return null;
        }
        if (source instanceof LdcInsnNode ldc) {
            if (kind == ValueKind.STRING && ldc.cst instanceof String string) {
                return Set.of(string);
            }
            if (kind != ValueKind.STRING && ldc.cst instanceof Type type) {
                if (kind == ValueKind.CLASS_DESCRIPTOR) {
                    return Set.of(type.getDescriptor());
                }
                return classOwner(type.getDescriptor()).map(Set::of).orElse(null);
            }
        }
        if (source instanceof FieldInsnNode field
                && source.getOpcode() == GETSTATIC
                && kind != ValueKind.STRING
                && field.name.equals("TYPE")) {
            String descriptor = primitiveTypeDescriptor(field.owner);
            return descriptor == null ? null : Set.of(descriptor);
        }
        if (source instanceof MethodInsnNode call
                && kind == ValueKind.CLASS_OWNER
                && call.owner.equals("java/lang/Class")
                && call.name.equals("forName")) {
            FieldObserverFrameView.InvocationOperands operands = frames.operands(call);
            if (operands == null || operands.arguments().length == 0) {
                return null;
            }
            Set<String> names = resolve(
                    operands.arguments()[0],
                    ValueKind.STRING,
                    visiting,
                    budget);
            return names == null
                    ? null
                    : names.stream()
                            .map(name -> name.replace('.', '/'))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        SourceValue copied = copiedValue(source);
        return copied == null ? null : resolve(copied, kind, visiting, budget);
    }

    private SourceValue copiedValue(AbstractInsnNode source) {
        int opcode = source.getOpcode();
        if (source instanceof VarInsnNode variable && opcode >= ILOAD && opcode <= ALOAD) {
            return frames.localBefore(source, variable.var);
        }
        if (source instanceof VarInsnNode && opcode >= ISTORE && opcode <= ASTORE) {
            return frames.stackOperand(source, 1);
        }
        if (opcode == CHECKCAST || opcode == DUP) {
            return frames.stackOperand(source, 1);
        }
        return null;
    }

    private Optional<String> classOwner(String descriptor) {
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return Optional.of(descriptor.substring(1, descriptor.length() - 1));
        }
        return descriptor.startsWith("[")
                ? Optional.of(descriptor)
                : Optional.empty();
    }

    private String primitiveTypeDescriptor(String owner) {
        return switch (owner) {
            case "java/lang/Boolean" -> "Z";
            case "java/lang/Byte" -> "B";
            case "java/lang/Short" -> "S";
            case "java/lang/Character" -> "C";
            case "java/lang/Integer" -> "I";
            case "java/lang/Long" -> "J";
            case "java/lang/Float" -> "F";
            case "java/lang/Double" -> "D";
            case "java/lang/Void" -> "V";
            default -> null;
        };
    }

    private enum ValueKind {
        STRING,
        CLASS_OWNER,
        CLASS_DESCRIPTOR
    }
}
