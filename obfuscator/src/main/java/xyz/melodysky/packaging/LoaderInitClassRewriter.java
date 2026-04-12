package xyz.melodysky.packaging;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

public class LoaderInitClassRewriter {

    public byte[] injectLoaderCalls(byte[] classBytes, String loaderInternalName, int classIndex) {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(classBytes).accept(classNode, 0);

        MethodNode clinit = classNode.methods.stream()
                .filter(method -> method.name.equals("<clinit>") && method.desc.equals("()V"))
                .findFirst()
                .orElseGet(() -> createClinit(classNode));

        InsnList injectedInstructions = new InsnList();
        if (!containsEnsureLoadedCall(clinit, loaderInternalName)) {
            injectedInstructions.add(buildEnsureLoadedInstructions(loaderInternalName));
        }
        if (!containsRegisterCall(clinit, loaderInternalName)) {
            injectedInstructions.add(buildRegisterInstructions(loaderInternalName, classNode.name, classIndex));
        }
        if (injectedInstructions.size() > 0) {
            clinit.instructions.insert(injectedInstructions);
        }

        // Keep existing stack map frames so ASM does not try to resolve external game classes
        // through getCommonSuperClass() while rewriting <clinit>.
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(classWriter);
        return classWriter.toByteArray();
    }

    private MethodNode createClinit(ClassNode classNode) {
        MethodNode clinit = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_STATIC,
                "<clinit>",
                "()V",
                null,
                null
        );
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(clinit);
        return clinit;
    }

    private boolean containsEnsureLoadedCall(MethodNode methodNode, String loaderInternalName) {
        return containsLoaderCall(methodNode, loaderInternalName, "ensureLoaded", "()V");
    }

    private boolean containsRegisterCall(MethodNode methodNode, String loaderInternalName) {
        return containsLoaderCall(methodNode, loaderInternalName, "registerNativesForClass", "(ILjava/lang/Class;)V");
    }

    private boolean containsLoaderCall(MethodNode methodNode, String loaderInternalName, String methodName, String descriptor) {
        for (AbstractInsnNode instruction = methodNode.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode methodInsnNode
                    && methodInsnNode.owner.equals(loaderInternalName)
                    && methodInsnNode.name.equals(methodName)
                    && methodInsnNode.desc.equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private MethodInsnNode buildEnsureLoadedInstructions(String loaderInternalName) {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                loaderInternalName,
                "ensureLoaded",
                "()V",
                false
        );
    }

    private InsnList buildRegisterInstructions(String loaderInternalName, String classInternalName, int classIndex) {
        InsnList instructions = new InsnList();
        instructions.add(new LdcInsnNode(classIndex));
        instructions.add(new LdcInsnNode(Type.getObjectType(classInternalName)));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                loaderInternalName,
                "registerNativesForClass",
                "(ILjava/lang/Class;)V",
                false
        ));
        return instructions;
    }
}
