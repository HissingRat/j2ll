package xyz.melodysky.analysis.method;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallResolution;
import xyz.melodysky.analysis.callgraph.CallTarget;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;

/**
 * Builds a stable reverse-use index for method-removal decisions.
 *
 * <p>Ordinary calls come from the already resolved call graph. ASM scanning is
 * intentionally limited to non-call references that the graph cannot express:
 * handles, bootstrap constants, constant dynamics and EnclosingMethod
 * metadata.</p>
 */
public final class NativeMethodUseAnalyzer {
    public NativeMethodUseIndex analyze(
            ParsedProgram program,
            CallGraph callGraph,
            ReflectionPlan reflectionPlan,
            Set<NativeMethodId> candidates) {
        return analyze(
                program,
                List.of(),
                callGraph,
                reflectionPlan,
                candidates);
    }

    public NativeMethodUseIndex analyze(
            ParsedProgram program,
            List<ParsedProgram> externalObservers,
            CallGraph callGraph,
            ReflectionPlan reflectionPlan,
            Set<NativeMethodId> candidates) {
        Map<String, NativeMethodId> candidatesByKey = candidates.stream()
                .collect(java.util.stream.Collectors.toMap(
                        NativeMethodId::methodKey,
                        value -> value));
        LinkedHashMap<NativeMethodId, ArrayList<NativeMethodCallUse>> incoming =
                new LinkedHashMap<>();
        candidates.stream().sorted().forEach(candidate ->
                incoming.put(candidate, new ArrayList<>()));
        for (CallResolution resolution : callGraph.resolutions()) {
            for (NativeMethodId candidate : referencedCandidates(
                    resolution,
                    candidatesByKey)) {
                List<CallTarget> knownTargets = resolution.targets().stream()
                        .filter(target -> !target.unknownExternal())
                        .toList();
                boolean exact = !knownTargets.isEmpty()
                        && knownTargets.stream().allMatch(target ->
                                target.owner().orElseThrow()
                                                .equals(candidate.owner())
                                        && target.signature().orElseThrow()
                                                .name().equals(candidate.name())
                                        && target.signature().orElseThrow()
                                                .descriptor().equals(
                                                        candidate.descriptor()));
                incoming.get(candidate).add(new NativeMethodCallUse(
                        resolution.callSite().id(),
                        resolution.callSite().callerOwner()
                                + "#"
                                + resolution.callSite().caller().name()
                                + "!"
                                + resolution.callSite().caller().descriptor(),
                        resolution.callSite().kind(),
                        exact,
                        resolution.hasUnknownTarget()));
            }
        }

        LinkedHashSet<NativeMethodId> handles = new LinkedHashSet<>();
        LinkedHashSet<NativeMethodId> reflective = reflectionPlan
                .resolvedMethods()
                .stream()
                .map(target -> new NativeMethodId(
                        target.owner(),
                        target.name(),
                        target.descriptor()))
                .filter(candidates::contains)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        LinkedHashSet<NativeMethodId> enclosing = new LinkedHashSet<>();
        for (ParsedClass parsedClass : program.classes()) {
            scanEnclosingMethod(
                    parsedClass,
                    candidatesByKey,
                    enclosing);
            for (ParsedMethod method : parsedClass.methods()) {
                scanMethod(
                        method,
                        candidatesByKey,
                        handles,
                        reflective);
            }
        }
        for (ParsedProgram observer : externalObservers) {
            for (ParsedClass parsedClass : observer.classes()) {
                for (ParsedMethod method : parsedClass.methods()) {
                    scanExternalCalls(
                            method,
                            candidatesByKey,
                            incoming);
                    scanMethod(
                            method,
                            candidatesByKey,
                            handles,
                            reflective);
                }
            }
        }
        LinkedHashMap<NativeMethodId, List<NativeMethodCallUse>> stableIncoming =
                new LinkedHashMap<>();
        incoming.forEach((key, value) -> stableIncoming.put(key, List.copyOf(value)));
        return new NativeMethodUseIndex(
                stableIncoming,
                handles,
                reflective,
                enclosing);
    }

    private void scanExternalCalls(
            ParsedMethod method,
            Map<String, NativeMethodId> candidatesByKey,
            Map<NativeMethodId, ArrayList<NativeMethodCallUse>> incoming) {
        int instructionIndex = -1;
        for (AbstractInsnNode instruction =
                        method.methodNode().instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) {
                instructionIndex++;
            }
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            NativeMethodId target = candidatesByKey.get(
                    call.owner
                            + "#"
                            + call.name
                            + "!"
                            + call.desc);
            if (target == null) {
                continue;
            }
            incoming.get(target).add(new NativeMethodCallUse(
                    method.methodKey() + "@" + instructionIndex,
                    method.methodKey(),
                    xyz.melodysky.analysis.callgraph.InvokeKind
                            .fromOpcode(call.getOpcode()),
                    call.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC
                            || call.getOpcode()
                                    == org.objectweb.asm.Opcodes.INVOKESPECIAL,
                    false));
        }
    }

    private Set<NativeMethodId> referencedCandidates(
            CallResolution resolution,
            Map<String, NativeMethodId> candidatesByKey) {
        LinkedHashSet<NativeMethodId> result = new LinkedHashSet<>();
        String declaredKey = resolution.callSite().declaredOwner()
                + "#"
                + resolution.callSite().declaredTarget().name()
                + "!"
                + resolution.callSite().declaredTarget().descriptor();
        NativeMethodId declared = candidatesByKey.get(declaredKey);
        if (declared != null) {
            result.add(declared);
        }
        resolution.targets().stream()
                .filter(target -> !target.unknownExternal())
                .map(target -> target.owner().orElseThrow()
                        + "#"
                        + target.signature().orElseThrow().name()
                        + "!"
                        + target.signature().orElseThrow().descriptor())
                .map(candidatesByKey::get)
                .filter(java.util.Objects::nonNull)
                .forEach(result::add);
        return result;
    }

    private void scanEnclosingMethod(
            ParsedClass parsedClass,
            Map<String, NativeMethodId> candidatesByKey,
            Set<NativeMethodId> enclosing) {
        var node = parsedClass.classNode();
        if (node.outerClass == null
                || node.outerMethod == null
                || node.outerMethodDesc == null) {
            return;
        }
        NativeMethodId target = candidatesByKey.get(
                node.outerClass
                        + "#"
                        + node.outerMethod
                        + "!"
                        + node.outerMethodDesc);
        if (target != null) {
            enclosing.add(target);
        }
    }

    private void scanMethod(
            ParsedMethod method,
            Map<String, NativeMethodId> candidatesByKey,
            Set<NativeMethodId> handles,
            Set<NativeMethodId> reflective) {
        ArrayList<String> stringConstants = new ArrayList<>();
        boolean reflectiveLookup = false;
        for (AbstractInsnNode instruction = method.methodNode().instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof LdcInsnNode ldc) {
                if (ldc.cst instanceof String value) {
                    stringConstants.add(value);
                } else {
                    scanConstant(
                            ldc.cst,
                            candidatesByKey,
                            handles);
                }
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                scanHandle(
                        dynamic.bsm,
                        candidatesByKey,
                        handles);
                for (Object argument : dynamic.bsmArgs) {
                    scanConstant(
                            argument,
                            candidatesByKey,
                            handles);
                }
            } else if (instruction instanceof MethodInsnNode call
                    && isReflectiveLookup(call.owner, call.name)) {
                reflectiveLookup = true;
            }
        }
        if (reflectiveLookup) {
            candidatesByKey.values().stream()
                    .filter(candidate ->
                            stringConstants.contains(candidate.name()))
                    .forEach(reflective::add);
        }
    }

    private boolean isReflectiveLookup(String owner, String name) {
        if (owner.equals("java/lang/Class")) {
            return name.equals("getMethod")
                    || name.equals("getDeclaredMethod");
        }
        return owner.equals("java/lang/invoke/MethodHandles$Lookup")
                && (name.equals("findStatic")
                        || name.equals("findVirtual")
                        || name.equals("findSpecial"));
    }

    private void scanConstant(
            Object value,
            Map<String, NativeMethodId> candidatesByKey,
            Set<NativeMethodId> handles) {
        if (value instanceof Handle handle) {
            scanHandle(handle, candidatesByKey, handles);
        } else if (value instanceof ConstantDynamic dynamic) {
            scanHandle(
                    dynamic.getBootstrapMethod(),
                    candidatesByKey,
                    handles);
            for (int index = 0;
                    index < dynamic.getBootstrapMethodArgumentCount();
                    index++) {
                scanConstant(
                        dynamic.getBootstrapMethodArgument(index),
                        candidatesByKey,
                        handles);
            }
        }
    }

    private void scanHandle(
            Handle handle,
            Map<String, NativeMethodId> candidatesByKey,
            Set<NativeMethodId> handles) {
        NativeMethodId target = candidatesByKey.get(
                handle.getOwner()
                        + "#"
                        + handle.getName()
                        + "!"
                        + handle.getDesc());
        if (target != null) {
            handles.add(target);
        }
    }
}
