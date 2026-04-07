package xyz.melodysky.frontend.bytecode;

import xyz.melodysky.filter.ClassMethodFilter;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrClassRef;
import xyz.melodysky.ir.model.IrMethod;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClassIrBuilder {

    private final MethodIrBuilder methodIrBuilder = new MethodIrBuilder();

    public BuildResult build(ClassNode classNode) {
        return build(classNode, ClassMethodFilter.allowAll());
    }

    public BuildResult build(ClassNode classNode, ClassMethodFilter classMethodFilter) {
        ArrayList<IrMethod> liftedMethods = new ArrayList<>();
        LinkedHashMap<String, SkippedMethod> skippedMethodsByKey = new LinkedHashMap<>();
        ArrayList<MethodNode> attemptableMethods = new ArrayList<>();
        boolean unsupportedSensitiveClass = isAnnotationClass(classNode);
        Map<String, Set<String>> sameClassCallees = collectSameClassCallees(classNode);

        for (MethodNode methodNode : classNode.methods) {
            if (!shouldAttempt(methodNode) || !classMethodFilter.shouldProcess(classNode, methodNode)) {
                continue;
            }
            attemptableMethods.add(methodNode);

            if (unsupportedSensitiveClass) {
                skippedMethodsByKey.put(methodKey(methodNode.name, methodNode.desc), new SkippedMethod(
                        methodNode.name,
                        methodNode.desc,
                        "annotation classes are not native-lowered yet"
                ));
            }
        }

        boolean changed;
        do {
            changed = false;
            for (MethodNode methodNode : attemptableMethods) {
                String key = methodKey(methodNode.name, methodNode.desc);
                if (skippedMethodsByKey.containsKey(key)) {
                    continue;
                }
                if (isSpecialMethod(methodNode)) {
                    continue;
                }
                for (String calleeKey : sameClassCallees.getOrDefault(key, Set.of())) {
                    if (!skippedMethodsByKey.containsKey(calleeKey)) {
                        continue;
                    }
                    skippedMethodsByKey.put(key, new SkippedMethod(
                            methodNode.name,
                            methodNode.desc,
                            "methods calling same-class methods that are not native-lowered yet"
                    ));
                    changed = true;
                    break;
                }
            }
        } while (changed);

        for (MethodNode methodNode : attemptableMethods) {
            String key = methodKey(methodNode.name, methodNode.desc);
            if (skippedMethodsByKey.containsKey(key)) {
                continue;
            }
            try {
                liftedMethods.add(methodIrBuilder.build(classNode.name, methodNode));
            } catch (UnsupportedBytecodeException exception) {
                skippedMethodsByKey.put(key, new SkippedMethod(methodNode.name, methodNode.desc, exception.getMessage()));
            }
        }

        return new BuildResult(
                new IrClass(new IrClassRef(classNode.name), liftedMethods),
                new ArrayList<>(skippedMethodsByKey.values())
        );
    }

    private boolean shouldAttempt(MethodNode methodNode) {
        return (methodNode.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
    }

    private boolean isSpecialMethod(MethodNode methodNode) {
        return methodNode.name.startsWith("<");
    }

    private boolean isAnnotationClass(ClassNode classNode) {
        return (classNode.access & Opcodes.ACC_ANNOTATION) != 0;
    }

    private Map<String, Set<String>> collectSameClassCallees(ClassNode classNode) {
        LinkedHashMap<String, Set<String>> calleesByMethod = new LinkedHashMap<>();
        for (MethodNode methodNode : classNode.methods) {
            LinkedHashSet<String> callees = new LinkedHashSet<>();
            for (var instruction = methodNode.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode methodInsnNode) {
                    if (!classNode.name.equals(methodInsnNode.owner)) {
                        continue;
                    }
                    callees.add(methodKey(methodInsnNode.name, methodInsnNode.desc));
                    continue;
                }
                if (!(instruction instanceof InvokeDynamicInsnNode invokeDynamicInsnNode)) {
                    continue;
                }
                if (!(invokeDynamicInsnNode.bsm instanceof Handle handle) || !isLambdaMetafactory(handle)) {
                    continue;
                }
                for (Object bootstrapArgument : invokeDynamicInsnNode.bsmArgs) {
                    if (!(bootstrapArgument instanceof Handle targetHandle)) {
                        continue;
                    }
                    if (targetHandle.getTag() == Opcodes.H_NEWINVOKESPECIAL) {
                        continue;
                    }
                    if (!classNode.name.equals(targetHandle.getOwner())) {
                        continue;
                    }
                    callees.add(methodKey(targetHandle.getName(), targetHandle.getDesc()));
                }
            }
            calleesByMethod.put(methodKey(methodNode.name, methodNode.desc), Set.copyOf(callees));
        }
        return Map.copyOf(calleesByMethod);
    }

    private boolean isLambdaMetafactory(Handle handle) {
        if (!"java/lang/invoke/LambdaMetafactory".equals(handle.getOwner())) {
            return false;
        }
        String bootstrapName = handle.getName();
        return "metafactory".equals(bootstrapName) || "altMetafactory".equals(bootstrapName);
    }

    private String methodKey(String name, String descriptor) {
        return name + descriptor;
    }

    public record BuildResult(IrClass irClass, List<SkippedMethod> skippedMethods) {
        public BuildResult {
            skippedMethods = List.copyOf(skippedMethods);
        }
    }

    public record SkippedMethod(String name, String descriptor, String reason) {
    }
}
