package xyz.melodysky.packaging;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashSet;
import java.util.Set;

public class NativeMethodClassRewriter {

    public byte[] rewrite(byte[] classBytes, Set<MethodKey> nativeMethods) {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(classBytes).accept(classNode, 0);

        Set<MethodKey> rewritableMethods = filterRewritableMethods(classNode, nativeMethods);
        if (rewritableMethods.isEmpty()) {
            return classBytes;
        }

        for (MethodNode methodNode : classNode.methods) {
            if (!rewritableMethods.contains(new MethodKey(methodNode.name, methodNode.desc))) {
                continue;
            }
            methodNode.access |= Opcodes.ACC_NATIVE;
            methodNode.instructions.clear();
            methodNode.tryCatchBlocks.clear();
            if (methodNode.localVariables != null) {
                methodNode.localVariables.clear();
            }
            methodNode.maxStack = 0;
            methodNode.maxLocals = 0;
        }

        ClassWriter classWriter = new ClassWriter(0);
        classNode.accept(classWriter);
        return classWriter.toByteArray();
    }

    public Set<MethodKey> filterRewritableMethods(byte[] classBytes, Set<MethodKey> nativeMethods) {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(classBytes).accept(classNode, 0);
        return filterRewritableMethods(classNode, nativeMethods);
    }

    private Set<MethodKey> filterRewritableMethods(ClassNode classNode, Set<MethodKey> nativeMethods) {
        // Interface default methods cannot be turned into ACC_NATIVE methods
        // without tripping class format checks on the JVM. Keep interface code
        // in bytecode form until the pipeline models that shape explicitly.
        if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) {
            return Set.of();
        }

        Set<MethodKey> clinitLambdaTargets = collectClinitLambdaTargets(classNode);
        HashSet<MethodKey> rewritableMethods = new HashSet<>();
        for (MethodNode methodNode : classNode.methods) {
            MethodKey methodKey = new MethodKey(methodNode.name, methodNode.desc);
            if (!nativeMethods.contains(methodKey) || isJavaEntryPoint(methodNode)) {
                continue;
            }
            if (shouldKeepRecordObjectMethodAsBytecode(classNode, methodNode)) {
                continue;
            }
            if (clinitLambdaTargets.contains(methodKey)) {
                continue;
            }
            rewritableMethods.add(methodKey);
        }
        return Set.copyOf(rewritableMethods);
    }

    private Set<MethodKey> collectClinitLambdaTargets(ClassNode classNode) {
        HashSet<MethodKey> targets = new HashSet<>();
        for (MethodNode methodNode : classNode.methods) {
            if (!"<clinit>".equals(methodNode.name)) {
                continue;
            }
            for (AbstractInsnNode instruction = methodNode.instructions.getFirst();
                 instruction != null;
                 instruction = instruction.getNext()) {
                if (!(instruction instanceof InvokeDynamicInsnNode invokeDynamicInsnNode)) {
                    continue;
                }
                if (!(invokeDynamicInsnNode.bsm instanceof Handle bootstrapHandle) || !isLambdaMetafactory(bootstrapHandle)) {
                    continue;
                }
                for (Object bootstrapArgument : invokeDynamicInsnNode.bsmArgs) {
                    if (!(bootstrapArgument instanceof Handle targetHandle)) {
                        continue;
                    }
                    if (!classNode.name.equals(targetHandle.getOwner())) {
                        continue;
                    }
                    targets.add(new MethodKey(targetHandle.getName(), targetHandle.getDesc()));
                }
            }
        }
        return Set.copyOf(targets);
    }

    private boolean isLambdaMetafactory(Handle bootstrapHandle) {
        if (!"java/lang/invoke/LambdaMetafactory".equals(bootstrapHandle.getOwner())) {
            return false;
        }
        String bootstrapName = bootstrapHandle.getName();
        return "metafactory".equals(bootstrapName) || "altMetafactory".equals(bootstrapName);
    }

    private boolean shouldKeepRecordObjectMethodAsBytecode(ClassNode classNode, MethodNode methodNode) {
        if ((classNode.access & Opcodes.ACC_RECORD) == 0) {
            return false;
        }
        // Record object methods are usually linked through ObjectMethods bootstrap
        // semantics. Preserving them as bytecode keeps equals/hashCode/toString
        // behavior identical to the JVM-generated version in whole-jar workloads.
        return isRecordEquals(methodNode) || isRecordHashCode(methodNode) || isRecordToString(methodNode);
    }

    private boolean isRecordEquals(MethodNode methodNode) {
        return "equals".equals(methodNode.name) && "(Ljava/lang/Object;)Z".equals(methodNode.desc);
    }

    private boolean isRecordHashCode(MethodNode methodNode) {
        return "hashCode".equals(methodNode.name) && "()I".equals(methodNode.desc);
    }

    private boolean isRecordToString(MethodNode methodNode) {
        return "toString".equals(methodNode.name) && "()Ljava/lang/String;".equals(methodNode.desc);
    }

    private boolean isJavaEntryPoint(MethodNode methodNode) {
        return "main".equals(methodNode.name)
                && "([Ljava/lang/String;)V".equals(methodNode.desc)
                && (methodNode.access & Opcodes.ACC_STATIC) != 0
                && (methodNode.access & Opcodes.ACC_PUBLIC) != 0;
    }

    public record MethodKey(String name, String descriptor) {
    }
}
