package xyz.melodysky.analysis.callgraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import xyz.melodysky.frontend.classfile.AsmInstructions;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.jvm.MethodSignature;
import xyz.melodysky.analysis.reflection.ReflectionMethodKind;
import xyz.melodysky.analysis.reflection.ReflectionMethodTarget;
import xyz.melodysky.analysis.reflection.StaticReflectionResolver;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndex;
import xyz.melodysky.runtime.jdk.LambdaMetafactoryBootstrap;
import xyz.melodysky.runtime.jdk.LambdaMetafactoryPlan;

public final class CallSiteCollector implements Opcodes {
    private final LambdaMetafactoryBootstrap lambdaMetafactory = new LambdaMetafactoryBootstrap();

    public List<CallSite> collect(ParsedProgram program) {
        ArrayList<CallSite> callSites = new ArrayList<>();
        for (ParsedClass parsedClass : program.classes()) {
            for (ParsedMethod method : parsedClass.methods()) {
                if (!method.hasCode()) {
                    continue;
                }
                callSites.addAll(collect(method));
            }
        }
        return callSites.stream().sorted(Comparator.naturalOrder()).toList();
    }

    public List<CallSite> collect(ParsedProgram program, RuntimeMetadataIndex metadataIndex) {
        ArrayList<CallSite> callSites = new ArrayList<>();
        for (ParsedClass parsedClass : program.classes()) {
            for (ParsedMethod method : parsedClass.methods()) {
                if (!method.hasCode()) {
                    continue;
                }
                callSites.addAll(collect(method, metadataIndex));
            }
        }
        return callSites.stream().sorted(Comparator.naturalOrder()).toList();
    }

    public List<CallSite> collect(ParsedMethod method) {
        ArrayList<CallSite> callSites = new ArrayList<>();
        MethodSignature caller = new MethodSignature(method.name(), method.descriptor());
        int executableIndex = 0;
        AbstractInsnNode previousExecutable = null;
        for (AbstractInsnNode instruction = method.methodNode().instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!AsmInstructions.isExecutable(instruction)) {
                continue;
            }
            if (instruction instanceof MethodInsnNode methodInsn) {
                callSites.add(new CallSite(
                        CallSiteIds.forInstruction(method.owner(), caller, executableIndex),
                        method.owner(),
                        caller,
                        executableIndex,
                        InvokeKind.fromOpcode(methodInsn.getOpcode()),
                        methodInsn.owner,
                        new MethodSignature(methodInsn.name, methodInsn.desc)));
                if (isMethodHandleInvoke(methodInsn) && previousExecutable instanceof LdcInsnNode ldc
                        && ldc.cst instanceof Handle handle) {
                    callSites.add(new CallSite(
                            CallSiteIds.forInstruction(method.owner(), caller, executableIndex)
                                    + "$methodHandleTarget",
                            method.owner(),
                            caller,
                            executableIndex,
                            invokeKind(handle),
                            handle.getOwner(),
                            new MethodSignature(handle.getName(), handle.getDesc())));
                }
            } else if (instruction instanceof InvokeDynamicInsnNode indy) {
                String callSiteId = CallSiteIds.forInstruction(method.owner(), caller, executableIndex);
                callSites.add(new CallSite(
                        callSiteId,
                        method.owner(),
                        caller,
                        executableIndex,
                        InvokeKind.DYNAMIC,
                        "<invokedynamic>",
                        new MethodSignature(indy.name, indy.desc)));
                int lambdaInstructionIndex = executableIndex;
                LambdaMetafactoryPlan lambdaPlan = lambdaMetafactory.parse(indy.bsm, indy.bsmArgs);
                lambdaPlan.implementationHandle().ifPresent(handle -> callSites.add(new CallSite(
                        callSiteId + "$lambdaTarget",
                        method.owner(),
                        caller,
                        lambdaInstructionIndex,
                        invokeKind(handle),
                        handle.getOwner(),
                        new MethodSignature(handle.getName(), handle.getDesc()))));
            }
            previousExecutable = instruction;
            executableIndex++;
        }
        return List.copyOf(callSites);
    }

    public List<CallSite> collect(ParsedMethod method, RuntimeMetadataIndex metadataIndex) {
        ArrayList<CallSite> callSites = new ArrayList<>(collect(method));
        MethodSignature caller = new MethodSignature(method.name(), method.descriptor());
        int syntheticIndex = 0;
        for (ReflectionMethodTarget target : new StaticReflectionResolver()
                .resolve(method, metadataIndex)
                .resolvedMethods()) {
            callSites.add(new CallSite(
                    CallSiteIds.forInstruction(method.owner(), caller, instructionIndex(target.sourceSite()))
                            + "$reflectionTarget" + syntheticIndex++,
                    method.owner(),
                    caller,
                    instructionIndex(target.sourceSite()),
                    invokeKind(target),
                    target.owner(),
                    target.signature()));
        }
        return callSites.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private InvokeKind invokeKind(Handle handle) {
        return switch (handle.getTag()) {
            case H_INVOKESTATIC -> InvokeKind.STATIC;
            case H_INVOKESPECIAL, H_NEWINVOKESPECIAL -> InvokeKind.SPECIAL;
            case H_INVOKEVIRTUAL -> InvokeKind.VIRTUAL;
            case H_INVOKEINTERFACE -> InvokeKind.INTERFACE;
            default -> InvokeKind.DYNAMIC;
        };
    }

    private boolean isMethodHandleInvoke(MethodInsnNode methodInsn) {
        return methodInsn.owner.equals("java/lang/invoke/MethodHandle")
                && (methodInsn.name.equals("invokeExact") || methodInsn.name.equals("invoke"));
    }

    private InvokeKind invokeKind(ReflectionMethodTarget target) {
        if (target.kind() == ReflectionMethodKind.DECLARED_CONSTRUCTOR
                || target.kind() == ReflectionMethodKind.REFLECTIVE_NEW_INSTANCE) {
            return InvokeKind.SPECIAL;
        }
        return InvokeKind.SPECIAL;
    }

    private int instructionIndex(String sourceSite) {
        int at = sourceSite.indexOf('@');
        int colon = sourceSite.indexOf(':', at);
        if (at < 0 || colon < 0) {
            return 0;
        }
        return Integer.parseInt(sourceSite.substring(at + 1, colon));
    }
}
