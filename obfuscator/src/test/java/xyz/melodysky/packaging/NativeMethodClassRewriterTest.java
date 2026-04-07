package xyz.melodysky.packaging;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NativeMethodClassRewriterTest {

    @Test
    public void testRewritesSelectedMethodToNative() {
        byte[] rewritten = new NativeMethodClassRewriter().rewrite(
                buildClassBytes(),
                Set.of(new NativeMethodClassRewriter.MethodKey("add", "(II)I"))
        );

        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(rewritten).accept(classNode, 0);
        MethodNode methodNode = classNode.methods.stream()
                .filter(method -> method.name.equals("add"))
                .findFirst()
                .orElseThrow();

        assertTrue((methodNode.access & Opcodes.ACC_NATIVE) != 0);
        assertEquals(0, methodNode.instructions.size());
    }

    @Test
    public void testKeepsInterfaceDefaultMethodAsBytecode() {
        byte[] rewritten = new NativeMethodClassRewriter().rewrite(
                buildInterfaceBytes(),
                Set.of(new NativeMethodClassRewriter.MethodKey("plusOffset", "(I)I"))
        );

        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(rewritten).accept(classNode, 0);
        MethodNode methodNode = classNode.methods.stream()
                .filter(method -> method.name.equals("plusOffset"))
                .findFirst()
                .orElseThrow();

        assertTrue((methodNode.access & Opcodes.ACC_NATIVE) == 0);
        assertTrue(methodNode.instructions.size() > 0);
    }

    @Test
    public void testKeepsPublicStaticMainAsBytecode() {
        byte[] rewritten = new NativeMethodClassRewriter().rewrite(
                buildMainClassBytes(),
                Set.of(new NativeMethodClassRewriter.MethodKey("main", "([Ljava/lang/String;)V"))
        );

        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(rewritten).accept(classNode, 0);
        MethodNode methodNode = classNode.methods.stream()
                .filter(method -> method.name.equals("main"))
                .findFirst()
                .orElseThrow();

        assertTrue((methodNode.access & Opcodes.ACC_NATIVE) == 0);
        assertTrue(methodNode.instructions.size() > 0);
    }

    @Test
    public void testKeepsClinitLambdaTargetsAsBytecode() {
        byte[] rewritten = new NativeMethodClassRewriter().rewrite(
                buildClassWithClinitLambdaTarget(),
                Set.of(new NativeMethodClassRewriter.MethodKey("supply", "()Ljava/lang/String;"))
        );

        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(rewritten).accept(classNode, 0);
        MethodNode methodNode = classNode.methods.stream()
                .filter(method -> method.name.equals("supply"))
                .findFirst()
                .orElseThrow();

        assertTrue((methodNode.access & Opcodes.ACC_NATIVE) == 0);
        assertTrue(methodNode.instructions.size() > 0);
    }

    @Test
    public void testKeepsRecordObjectMethodsAsBytecode() {
        byte[] rewritten = new NativeMethodClassRewriter().rewrite(
                buildRecordLikeClass(),
                Set.of(
                        new NativeMethodClassRewriter.MethodKey("equals", "(Ljava/lang/Object;)Z"),
                        new NativeMethodClassRewriter.MethodKey("hashCode", "()I"),
                        new NativeMethodClassRewriter.MethodKey("toString", "()Ljava/lang/String;")
                )
        );

        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(rewritten).accept(classNode, 0);

        MethodNode equalsMethod = classNode.methods.stream()
                .filter(method -> method.name.equals("equals"))
                .findFirst()
                .orElseThrow();
        MethodNode hashCodeMethod = classNode.methods.stream()
                .filter(method -> method.name.equals("hashCode"))
                .findFirst()
                .orElseThrow();
        MethodNode toStringMethod = classNode.methods.stream()
                .filter(method -> method.name.equals("toString"))
                .findFirst()
                .orElseThrow();

        assertTrue((equalsMethod.access & Opcodes.ACC_NATIVE) == 0);
        assertTrue((hashCodeMethod.access & Opcodes.ACC_NATIVE) == 0);
        assertTrue((toStringMethod.access & Opcodes.ACC_NATIVE) == 0);
        assertTrue(equalsMethod.instructions.size() > 0);
        assertTrue(hashCodeMethod.instructions.size() > 0);
        assertTrue(toStringMethod.instructions.size() > 0);
    }

    private byte[] buildClassBytes() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "sample/Test";
        classNode.superName = "java/lang/Object";

        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "add", "(II)I", null, null);
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        classNode.methods.add(methodNode);

        ClassWriter classWriter = new ClassWriter(0);
        classNode.accept(classWriter);
        return classWriter.toByteArray();
    }

    private byte[] buildInterfaceBytes() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        classNode.name = "sample/TestInterface";
        classNode.superName = "java/lang/Object";

        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC, "plusOffset", "(I)I", null, null);
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        classNode.methods.add(methodNode);

        ClassWriter classWriter = new ClassWriter(0);
        classNode.accept(classWriter);
        return classWriter.toByteArray();
    }

    private byte[] buildMainClassBytes() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "sample/Main";
        classNode.superName = "java/lang/Object";

        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(methodNode);

        ClassWriter classWriter = new ClassWriter(0);
        classNode.accept(classWriter);
        return classWriter.toByteArray();
    }

    private byte[] buildClassWithClinitLambdaTarget() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "sample/LambdaClinit";
        classNode.superName = "java/lang/Object";

        MethodNode supply = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "supply", "()Ljava/lang/String;", null, null);
        supply.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("ok"));
        supply.instructions.add(new InsnNode(Opcodes.ARETURN));
        classNode.methods.add(supply);

        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        Handle bootstrap = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false
        );
        clinit.instructions.add(new InvokeDynamicInsnNode(
                "get",
                "()Ljava/util/function/Supplier;",
                bootstrap,
                org.objectweb.asm.Type.getType("()Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKESTATIC, "sample/LambdaClinit", "supply", "()Ljava/lang/String;", false),
                org.objectweb.asm.Type.getType("()Ljava/lang/String;")
        ));
        clinit.instructions.add(new InsnNode(Opcodes.POP));
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(clinit);

        ClassWriter classWriter = new ClassWriter(0);
        classNode.accept(classWriter);
        return classWriter.toByteArray();
    }

    private byte[] buildRecordLikeClass() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD;
        classNode.name = "sample/RecordLike";
        classNode.superName = "java/lang/Record";

        MethodNode equalsMethod = new MethodNode(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
        equalsMethod.instructions.add(new InsnNode(Opcodes.ICONST_1));
        equalsMethod.instructions.add(new InsnNode(Opcodes.IRETURN));
        classNode.methods.add(equalsMethod);

        MethodNode hashCodeMethod = new MethodNode(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null);
        hashCodeMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        hashCodeMethod.instructions.add(new InsnNode(Opcodes.IRETURN));
        classNode.methods.add(hashCodeMethod);

        MethodNode toStringMethod = new MethodNode(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        toStringMethod.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("record"));
        toStringMethod.instructions.add(new InsnNode(Opcodes.ARETURN));
        classNode.methods.add(toStringMethod);

        ClassWriter classWriter = new ClassWriter(0);
        classNode.accept(classWriter);
        return classWriter.toByteArray();
    }
}
