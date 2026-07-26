package xyz.melodysky.packaging;

import java.util.Objects;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

/**
 * Adds a JVM-managed, per-defining-Class reference sidecar to the one generated
 * Loader class. No nested, anonymous, or companion class is emitted.
 */
public final class LoaderClassValueSidecarInjector implements Opcodes {
    public static final String ACCESSOR_NAME = "referenceSidecar";
    public static final String ACCESSOR_DESCRIPTOR =
            "(Ljava/lang/Class;)[Ljava/lang/Object;";
    private static final String CLASS_VALUE = "java/lang/ClassValue";
    private static final String FIELD_NAME = "referenceSidecars";

    void inject(ClassNode loader, int slotCount) {
        Objects.requireNonNull(loader, "loader");
        if (slotCount <= 0) {
            return;
        }
        if (!"java/lang/Object".equals(loader.superName)) {
            throw new IllegalArgumentException("Loader template must directly extend Object");
        }
        loader.superName = CLASS_VALUE;
        rewriteConstructorSuperCall(loader);
        loader.fields.add(new FieldNode(
                ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
                FIELD_NAME,
                "L" + loader.name + ";",
                null,
                null));
        loader.methods.add(computeValue(slotCount));
        loader.methods.add(sidecarAccessor(loader.name));
        loader.methods.add(classInitializer(loader.name));
    }

    private void rewriteConstructorSuperCall(ClassNode loader) {
        MethodNode constructor = loader.methods.stream()
                .filter(method -> method.name.equals("<init>") && method.desc.equals("()V"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Loader template has no constructor"));
        boolean changed = false;
        for (var instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.getOpcode() == INVOKESPECIAL
                    && invocation.owner.equals("java/lang/Object")
                    && invocation.name.equals("<init>")
                    && invocation.desc.equals("()V")) {
                invocation.owner = CLASS_VALUE;
                changed = true;
            }
        }
        if (!changed) {
            throw new IllegalArgumentException("Loader constructor has no Object super call");
        }
    }

    private MethodNode computeValue(int slotCount) {
        MethodNode method = new MethodNode(
                ACC_PROTECTED,
                "computeValue",
                "(Ljava/lang/Class;)Ljava/lang/Object;",
                null,
                null);
        pushInt(method.instructions, slotCount);
        method.instructions.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
        method.instructions.add(new InsnNode(ARETURN));
        return method;
    }

    private MethodNode sidecarAccessor(String loaderName) {
        MethodNode method = new MethodNode(
                ACC_PRIVATE | ACC_STATIC,
                ACCESSOR_NAME,
                ACCESSOR_DESCRIPTOR,
                null,
                null);
        method.instructions.add(new FieldInsnNode(
                GETSTATIC,
                loaderName,
                FIELD_NAME,
                "L" + loaderName + ";"));
        method.instructions.add(new org.objectweb.asm.tree.VarInsnNode(ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                INVOKEVIRTUAL,
                CLASS_VALUE,
                "get",
                "(Ljava/lang/Class;)Ljava/lang/Object;",
                false));
        method.instructions.add(new TypeInsnNode(CHECKCAST, "[Ljava/lang/Object;"));
        method.instructions.add(new InsnNode(ARETURN));
        return method;
    }

    private MethodNode classInitializer(String loaderName) {
        if (loaderName.isBlank()) {
            throw new IllegalArgumentException("loaderName must not be blank");
        }
        MethodNode method = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
        method.instructions.add(new TypeInsnNode(NEW, loaderName));
        method.instructions.add(new InsnNode(DUP));
        method.instructions.add(new MethodInsnNode(
                INVOKESPECIAL,
                loaderName,
                "<init>",
                "()V",
                false));
        method.instructions.add(new FieldInsnNode(
                PUTSTATIC,
                loaderName,
                FIELD_NAME,
                "L" + loaderName + ";"));
        method.instructions.add(new InsnNode(RETURN));
        return method;
    }

    private void pushInt(InsnList instructions, int value) {
        if (value >= 0 && value <= 5) {
            instructions.add(new InsnNode(ICONST_0 + value));
        } else if (value <= Byte.MAX_VALUE) {
            instructions.add(new IntInsnNode(BIPUSH, value));
        } else if (value <= Short.MAX_VALUE) {
            instructions.add(new IntInsnNode(SIPUSH, value));
        } else {
            instructions.add(new org.objectweb.asm.tree.LdcInsnNode(value));
        }
    }
}
