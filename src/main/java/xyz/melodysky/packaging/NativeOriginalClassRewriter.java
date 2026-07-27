package xyz.melodysky.packaging;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ParsedClass;

public final class NativeOriginalClassRewriter implements Opcodes {
    public ClassRewriteResult rewrite(ParsedClass parsedClass, List<MethodRewriteDecision> decisions) {
        return rewrite(parsedClass, decisions, null);
    }

    public ClassRewriteResult rewrite(
            ParsedClass parsedClass,
            List<MethodRewriteDecision> decisions,
            String loaderInternalName) {
        ClassNode copy = cloneClass(parsedClass.classNode());
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        ArrayList<MethodRewriteDecision> applied = new ArrayList<>();

        for (MethodRewriteDecision decision : decisions) {
            if (!decision.method().owner().equals(parsedClass.internalName())) {
                continue;
            }
            if (decision.strategy() == MethodRewriteStrategy.NATIVE_ORIGINAL) {
                MethodNode method = findMethod(copy, decision);
                if (method == null) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.PACKAGING,
                                    PackagingDiagnostics.NATIVE_ORIGINAL_REWRITE_FAILED,
                                    "method selected for nativeOriginal rewrite was not found")
                            .at(location(decision)));
                    continue;
                }
                makeNative(method);
                applied.add(decision);
            } else if (decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB
                    || decision.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB
                    || decision.strategy() == MethodRewriteStrategy.INTERFACE_METHOD_STUB) {
                if (decision.strategy() == MethodRewriteStrategy.INTERFACE_METHOD_STUB) {
                    diagnostics.add(Diagnostic.warning(
                                    DiagnosticStage.PACKAGING,
                                    PackagingDiagnostics.STUB_REWRITE_NOT_IMPLEMENTED,
                                    decision.strategy().wireName() + " rewrite is planned but not implemented yet")
                            .at(location(decision))
                            .withDecision("skipped"));
                    continue;
                }
                MethodNode method = findMethod(copy, decision);
                if (method == null) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.PACKAGING,
                                    PackagingDiagnostics.NATIVE_ORIGINAL_REWRITE_FAILED,
                                    "method selected for stub rewrite was not found")
                            .at(location(decision)));
                    continue;
                }
                rewriteStub(copy, method, decision);
                applied.add(decision);
            }
        }
        if (loaderInternalName != null && !applied.isEmpty()) {
            injectLoaderTrigger(copy, loaderInternalName);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        copy.accept(writer);
        return new ClassRewriteResult(writer.toByteArray(), diagnostics, applied);
    }

    private ClassNode cloneClass(ClassNode original) {
        ClassWriter writer = new ClassWriter(0);
        original.accept(writer);
        ClassNode copy = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(copy, ClassReader.EXPAND_FRAMES);
        return copy;
    }

    private MethodNode findMethod(ClassNode classNode, MethodRewriteDecision decision) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(decision.method().name()) && method.desc.equals(decision.method().descriptor())) {
                return method;
            }
        }
        return null;
    }

    private void makeNative(MethodNode method) {
        method.access = (method.access | ACC_NATIVE) & ~ACC_ABSTRACT;
        method.instructions = new InsnList();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = 0;
        method.maxLocals = 0;
    }

    private void rewriteStub(ClassNode classNode, MethodNode method, MethodRewriteDecision decision) {
        if (decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB) {
            rewriteConstructorStub(classNode, method, decision);
            return;
        }
        rewriteClassInitializerStub(classNode, method, decision);
    }

    private void rewriteConstructorStub(ClassNode classNode, MethodNode method, MethodRewriteDecision decision) {
        String helperDescriptor = constructorHelperDescriptor(decision);
        method.instructions = new InsnList();
        method.instructions.add(new VarInsnNode(ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                INVOKESPECIAL,
                classNode.superName == null ? "java/lang/Object" : classNode.superName,
                "<init>",
                "()V",
                false));
        method.instructions.add(new VarInsnNode(ALOAD, 0));
        addOriginalArguments(method.instructions, decision.method().descriptor(), 1);
        method.instructions.add(new MethodInsnNode(
                INVOKESTATIC,
                classNode.name,
                decision.generatedHelperName().orElseThrow(),
                helperDescriptor,
                false));
        method.instructions.add(new InsnNode(RETURN));
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        addNativeHelper(classNode, decision.generatedHelperName().orElseThrow(), helperDescriptor);
    }

    private void rewriteClassInitializerStub(ClassNode classNode, MethodNode method, MethodRewriteDecision decision) {
        String helperDescriptor = "()V";
        method.instructions = new InsnList();
        method.instructions.add(new MethodInsnNode(
                INVOKESTATIC,
                classNode.name,
                decision.generatedHelperName().orElseThrow(),
                helperDescriptor,
                false));
        method.instructions.add(new InsnNode(RETURN));
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        addNativeHelper(classNode, decision.generatedHelperName().orElseThrow(), helperDescriptor);
    }

    private void addOriginalArguments(InsnList instructions, String descriptor, int firstLocal) {
        int local = firstLocal;
        for (Type type : Type.getArgumentTypes(descriptor)) {
            instructions.add(new VarInsnNode(type.getOpcode(ILOAD), local));
            local += type.getSize();
        }
    }

    private void addNativeHelper(ClassNode classNode, String name, String descriptor) {
        if (classNode.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor))) {
            return;
        }
        MethodNode helper = new MethodNode(
                ACC_PRIVATE | ACC_STATIC | ACC_NATIVE | ACC_SYNTHETIC,
                name,
                descriptor,
                null,
                null);
        helper.instructions = new InsnList();
        classNode.methods.add(helper);
    }

    private String constructorHelperDescriptor(MethodRewriteDecision decision) {
        String descriptor = decision.method().descriptor();
        int close = descriptor.indexOf(')');
        return "(L" + decision.method().owner() + ";" + descriptor.substring(1, close) + ")V";
    }

    private void injectLoaderTrigger(ClassNode classNode, String loaderInternalName) {
        MethodNode clinit = classNode.methods.stream()
                .filter(method -> method.name.equals("<clinit>") && method.desc.equals("()V"))
                .findFirst()
                .orElse(null);
        InsnList trigger = new InsnList();
        trigger.add(new MethodInsnNode(INVOKESTATIC, loaderInternalName, "ensureLoaded", "()V", false));
        if (clinit == null) {
            clinit = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(trigger);
            clinit.instructions.add(new org.objectweb.asm.tree.InsnNode(RETURN));
            classNode.methods.add(clinit);
            return;
        }
        clinit.instructions.insert(trigger);
    }

    private DiagnosticLocation location(MethodRewriteDecision decision) {
        return DiagnosticLocation.methodLocation(
                decision.method().owner(),
                decision.method().name(),
                decision.method().descriptor());
    }
}
