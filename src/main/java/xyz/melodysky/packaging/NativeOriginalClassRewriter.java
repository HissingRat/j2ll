package xyz.melodysky.packaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import xyz.melodysky.toolchain.initializer.ConstructorPrefixPlan;
import xyz.melodysky.toolchain.initializer.InitializerImplementationKind;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlan;

public final class NativeOriginalClassRewriter implements Opcodes {
    public ClassRewriteResult rewrite(ParsedClass parsedClass, List<MethodRewriteDecision> decisions) {
        return rewrite(parsedClass, decisions, Map.of(), null);
    }

    public ClassRewriteResult rewrite(
            ParsedClass parsedClass,
            List<MethodRewriteDecision> decisions,
            String loaderInternalName) {
        return rewrite(parsedClass, decisions, Map.of(), loaderInternalName);
    }

    public ClassRewriteResult rewrite(
            ParsedClass parsedClass,
            List<MethodRewriteDecision> decisions,
            Map<String, InitializerImplementationPlan> initializerPlans,
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
                    MethodNode method = findMethod(copy, decision);
                    if (method == null) {
                        diagnostics.add(Diagnostic.error(
                                        DiagnosticStage.PACKAGING,
                                        PackagingDiagnostics.NATIVE_ORIGINAL_REWRITE_FAILED,
                                        "method selected for interface stub rewrite was not found")
                                .at(location(decision)));
                        continue;
                    }
                    if (loaderInternalName == null) {
                        diagnostics.add(Diagnostic.error(
                                        DiagnosticStage.PACKAGING,
                                        PackagingDiagnostics.INTERFACE_METHOD_LOADER_MISSING,
                                        "interface method stub requires the generated runtime Loader")
                                .at(location(decision)));
                        continue;
                    }
                    rewriteInterfaceMethodStub(method, decision, loaderInternalName);
                    applied.add(decision);
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
                String helperName =
                        decision.generatedHelperName().orElseThrow();
                String helperDescriptor =
                        NativeHelperDescriptor.forDecision(decision);
                if (hasMethod(copy, helperName, helperDescriptor)) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.PACKAGING,
                                    PackagingDiagnostics
                                            .GENERATED_INITIALIZER_HELPER_COLLISION,
                                    "initializer native carrier collides with another method")
                            .at(location(decision)));
                    continue;
                }
                InitializerImplementationPlan initializerPlan =
                        initializerPlans.get(decision.method().methodKey());
                if (initializerPlan == null) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.PACKAGING,
                                    PackagingDiagnostics.INITIALIZER_IMPLEMENTATION_PLAN_MISSING,
                                    "stub rewrite requires the final initializer implementation plan")
                            .at(location(decision)));
                    continue;
                }
                if (!rewriteStub(copy, method, decision, initializerPlan)) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.PACKAGING,
                                    PackagingDiagnostics.CONSTRUCTOR_PREFIX_REWRITE_FAILED,
                                    "constructor bytecode no longer matches its validated initialization prefix")
                            .at(location(decision)));
                    continue;
                }
                applied.add(decision);
            }
        }
        if (loaderInternalName != null && applied.stream().anyMatch(
                decision -> decision.strategy() != MethodRewriteStrategy.INTERFACE_METHOD_STUB)) {
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

    private boolean hasMethod(
            ClassNode classNode,
            String name,
            String descriptor) {
        return classNode.methods.stream().anyMatch(method ->
                method.name.equals(name) && method.desc.equals(descriptor));
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

    private boolean rewriteStub(
            ClassNode classNode,
            MethodNode method,
            MethodRewriteDecision decision,
            InitializerImplementationPlan initializerPlan) {
        if (decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB) {
            if (initializerPlan.kind() != InitializerImplementationKind.CONSTRUCTOR) {
                return false;
            }
            return rewriteConstructorStub(classNode, method, decision, initializerPlan);
        }
        if (initializerPlan.kind() != InitializerImplementationKind.CLASS_INITIALIZER) {
            return false;
        }
        rewriteClassInitializerStub(classNode, method, decision);
        return true;
    }

    private boolean rewriteConstructorStub(
            ClassNode classNode,
            MethodNode method,
            MethodRewriteDecision decision,
            InitializerImplementationPlan initializerPlan) {
        ConstructorPrefixPlan prefix = initializerPlan.constructorPrefix().orElseThrow();
        MethodInsnNode initialization = constructorInitialization(method, prefix);
        if (initialization == null) {
            return false;
        }
        String helperDescriptor = constructorHelperDescriptor(decision);
        removeAfter(method.instructions, initialization);
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
        return true;
    }

    private MethodInsnNode constructorInitialization(
            MethodNode method,
            ConstructorPrefixPlan prefix) {
        int opcodeIndex = -1;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() < 0) {
                continue;
            }
            opcodeIndex++;
            if (opcodeIndex != prefix.initializationOpcodeIndex()) {
                continue;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == INVOKESPECIAL
                    && call.name.equals("<init>")
                    && call.owner.equals(prefix.targetOwner())
                    && call.desc.equals(prefix.targetDescriptor())
                    && call.itf == prefix.interfaceTarget()) {
                return call;
            }
            return null;
        }
        return null;
    }

    private void removeAfter(InsnList instructions, MethodInsnNode boundary) {
        var instruction = boundary.getNext();
        while (instruction != null) {
            var next = instruction.getNext();
            instructions.remove(instruction);
            instruction = next;
        }
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

    private void rewriteInterfaceMethodStub(
            MethodNode method,
            MethodRewriteDecision decision,
            String loaderInternalName) {
        String helperDescriptor = NativeHelperDescriptor.forDecision(decision);
        method.instructions = new InsnList();
        method.instructions.add(new MethodInsnNode(
                INVOKESTATIC,
                loaderInternalName,
                "ensureLoaded",
                "()V",
                false));
        int firstLocal = 0;
        if (!decision.method().accessFlags().isStatic()) {
            method.instructions.add(new VarInsnNode(ALOAD, 0));
            firstLocal = 1;
        }
        addOriginalArguments(method.instructions, decision.method().descriptor(), firstLocal);
        method.instructions.add(new MethodInsnNode(
                INVOKESTATIC,
                decision.registrationOwner(),
                decision.generatedHelperName().orElseThrow(),
                helperDescriptor,
                false));
        method.instructions.add(new InsnNode(
                Type.getReturnType(decision.method().descriptor()).getOpcode(IRETURN)));
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
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
