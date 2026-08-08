package xyz.melodysky.analysis.field;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import xyz.melodysky.frontend.classfile.ParsedMethod;

/** Performs SourceValue-backed dynamic field-observer analysis for one method. */
final class FieldObserverMethodAnalyzer implements Opcodes {
    private final IndirectFieldObserverClassifier indirectClassifier =
            new IndirectFieldObserverClassifier();

    List<FieldDynamicObservation> analyze(
            ParsedMethod parsedMethod,
            FieldObserverDeclarationIndex declarations) {
        if (!parsedMethod.hasCode() || parsedMethod.methodNode().instructions.size() == 0) {
            return List.of();
        }
        if (!hasObserverCall(parsedMethod.methodNode())) {
            // InvokeDynamic/ConstantDynamic bootstrap observations are handled
            // directly by FieldUseAnalyzer and do not need whole-method frames.
            return List.of();
        }
        MethodNode method = analysisClone(parsedMethod.methodNode());
        IdentityHashMap<AbstractInsnNode, Integer> indices = instructionIndices(method);
        Frame<SourceValue>[] frames;
        FieldObserverSourceInterpreter interpreter =
                new FieldObserverSourceInterpreter();
        try {
            frames = new Analyzer<>(interpreter).analyze(parsedMethod.owner(), method);
        } catch (AnalyzerException | RuntimeException exception) {
            return failClosedObservations(parsedMethod, method);
        }

        FieldObserverValueResolver resolver = new FieldObserverValueResolver(
                frames,
                indices,
                declarations,
                interpreter);
        ArrayList<FieldDynamicObservation> observations = new ArrayList<>();
        int instructionIndex = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext(), instructionIndex++) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            FieldDynamicBoundaryKind kind = observerKind(call);
            if (kind == null) {
                continue;
            }
            FieldObserverProvenance provenance = observationProvenance(call, resolver);
            emit(
                    provenance,
                    kind,
                    parsedMethod.methodKey(),
                    instructionIndex,
                    observations);
            if (call.owner.equals("java/lang/invoke/MethodHandles$Lookup")
                    && (call.name.equals("findVarHandle")
                            || call.name.equals("findStaticVarHandle")
                            || call.name.equals("unreflectVarHandle"))) {
                emit(
                        provenance,
                        FieldDynamicBoundaryKind.METHOD_HANDLE,
                        parsedMethod.methodKey(),
                        instructionIndex,
                        observations);
            }
        }
        return observations;
    }

    private boolean hasObserverCall(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && observerKind(call) != null) {
                return true;
            }
        }
        return false;
    }

    private FieldObserverProvenance observationProvenance(
            MethodInsnNode call,
            FieldObserverValueResolver resolver) {
        if (indirectClassifier.isBytecodeDefinition(call.owner, call.name)) {
            return FieldObserverProvenance.global();
        }
        if (call.owner.equals("java/lang/Class") && isReflectionLookup(call.name)) {
            return resolver.reflectionLookup(call);
        }
        FieldObserverFrameView.InvocationOperands operands = resolver.operands(call);
        if (call.owner.equals("java/lang/reflect/Field")) {
            return operands == null
                    ? FieldObserverProvenance.global()
                    : resolver.reflectionField(operands.receiver());
        }
        if (call.owner.equals("java/lang/reflect/Method")
                && call.name.equals("invoke")) {
            return FieldObserverProvenance.global();
        }
        if (call.owner.equals("java/lang/invoke/MethodHandles$Lookup")
                && isLookupFieldApi(call.name)) {
            return resolver.lookupHandle(call);
        }
        if (call.owner.equals("java/lang/invoke/MethodHandle")) {
            return operands == null
                    ? FieldObserverProvenance.global()
                    : resolver.methodHandle(operands.receiver());
        }
        if (call.owner.equals("java/lang/invoke/VarHandle")) {
            return operands == null
                    ? FieldObserverProvenance.global()
                    : resolver.varHandle(operands.receiver());
        }
        if (isUnsafeOwner(call.owner)) {
            if (call.name.equals("staticFieldBase") || call.name.equals("staticFieldOffset")) {
                return operands == null || operands.arguments().length != 1
                        ? FieldObserverProvenance.global()
                        : resolver.reflectionField(operands.arguments()[0]);
            }
            return unsafeAccessProvenance(operands, resolver);
        }
        if (isAgentOwner(call.owner)) {
            return FieldObserverProvenance.global();
        }
        if (isNativeLoadingCall(call)) {
            return FieldObserverProvenance.global();
        }
        return FieldObserverProvenance.nonField();
    }

    private FieldObserverProvenance unsafeAccessProvenance(
            FieldObserverFrameView.InvocationOperands operands,
            FieldObserverValueResolver resolver) {
        if (operands == null) {
            return FieldObserverProvenance.global();
        }
        ArrayList<FieldObserverProvenance> pairs = new ArrayList<>();
        Type[] types = operands.argumentTypes();
        SourceValue[] values = operands.arguments();
        for (int index = 0; index + 1 < types.length; index++) {
            if (referenceType(types[index]) && types[index + 1].getSort() == Type.LONG) {
                pairs.add(FieldObserverProvenance.constrain(
                        resolver.unsafeBase(values[index]),
                        resolver.unsafeOffset(values[index + 1])));
            }
        }
        return pairs.isEmpty()
                ? FieldObserverProvenance.nonField()
                : FieldObserverProvenance.union(pairs);
    }

    private void emit(
            FieldObserverProvenance provenance,
            FieldDynamicBoundaryKind observerKind,
            String methodKey,
            int instructionIndex,
            List<FieldDynamicObservation> output) {
        if (provenance.globalScope()) {
            output.add(FieldDynamicObservation.global(
                    observerKind,
                    methodKey,
                    instructionIndex));
            return;
        }
        for (String owner : provenance.owners().stream().sorted().toList()) {
            output.add(FieldDynamicObservation.owner(
                    observerKind,
                    owner,
                    methodKey,
                    instructionIndex));
        }
        for (FieldId field : provenance.exactFields().stream().sorted().toList()) {
            if (!provenance.owners().contains(field.owner())) {
                output.add(FieldDynamicObservation.exact(
                        observerKind,
                        field,
                        methodKey,
                        instructionIndex));
            }
        }
    }

    private List<FieldDynamicObservation> failClosedObservations(
            ParsedMethod parsedMethod,
            MethodNode method) {
        Set<FieldDynamicBoundaryKind> kinds = new LinkedHashSet<>();
        int firstObserverIndex = 0;
        int instructionIndex = 0;
        boolean found = false;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext(), instructionIndex++) {
            if (instruction instanceof MethodInsnNode call) {
                FieldDynamicBoundaryKind kind = observerKind(call);
                if (kind != null) {
                    kinds.add(kind);
                    if (call.owner.equals("java/lang/invoke/MethodHandles$Lookup")
                            && (call.name.equals("findVarHandle")
                                    || call.name.equals("findStaticVarHandle")
                                    || call.name.equals("unreflectVarHandle"))) {
                        kinds.add(FieldDynamicBoundaryKind.METHOD_HANDLE);
                    }
                    if (!found) {
                        firstObserverIndex = instructionIndex;
                        found = true;
                    }
                }
            }
        }
        if (!found) {
            return List.of();
        }
        ArrayList<FieldDynamicObservation> result = new ArrayList<>();
        for (FieldDynamicBoundaryKind kind : kinds) {
            result.add(FieldDynamicObservation.global(
                    kind,
                    parsedMethod.methodKey(),
                    firstObserverIndex));
        }
        return result;
    }

    private FieldDynamicBoundaryKind observerKind(MethodInsnNode call) {
        if (indirectClassifier.isBytecodeDefinition(call.owner, call.name)) {
            return FieldDynamicBoundaryKind.DYNAMIC_CLASS_LOADING;
        }
        if (call.owner.equals("java/lang/Class") && isReflectionLookup(call.name)
                || call.owner.equals("java/lang/reflect/Field")
                || call.owner.equals("java/lang/reflect/Method")
                        && call.name.equals("invoke")) {
            return FieldDynamicBoundaryKind.REFLECTION;
        }
        if (call.owner.equals("java/lang/invoke/MethodHandles$Lookup")
                && isLookupFieldApi(call.name)
                || call.owner.equals("java/lang/invoke/MethodHandle")
                        && (call.name.equals("invoke")
                                || call.name.equals("invokeExact")
                                || call.name.equals("invokeWithArguments"))) {
            return call.name.equals("findVarHandle")
                            || call.name.equals("findStaticVarHandle")
                            || call.name.equals("unreflectVarHandle")
                    ? FieldDynamicBoundaryKind.VAR_HANDLE
                    : FieldDynamicBoundaryKind.METHOD_HANDLE;
        }
        if (call.owner.equals("java/lang/invoke/VarHandle")) {
            return FieldDynamicBoundaryKind.VAR_HANDLE;
        }
        if (isUnsafeOwner(call.owner)
                && (call.name.equals("staticFieldBase")
                        || call.name.equals("staticFieldOffset")
                        || hasUnsafeFieldCoordinates(call.desc))) {
            return FieldDynamicBoundaryKind.UNSAFE;
        }
        if (isAgentOwner(call.owner)) {
            return FieldDynamicBoundaryKind.AGENT_INSTRUMENTATION;
        }
        if (isNativeLoadingCall(call)) {
            return FieldDynamicBoundaryKind.NATIVE_JNI;
        }
        return null;
    }

    private boolean hasUnsafeFieldCoordinates(String descriptor) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        for (int index = 0; index + 1 < arguments.length; index++) {
            if (referenceType(arguments[index]) && arguments[index + 1].getSort() == Type.LONG) {
                return true;
            }
        }
        return false;
    }

    private boolean referenceType(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    private MethodNode analysisClone(MethodNode original) {
        MethodNode clone = new MethodNode(
                ASM9,
                original.access,
                original.name,
                original.desc,
                original.signature,
                original.exceptions == null
                        ? null
                        : original.exceptions.toArray(String[]::new));
        original.accept(clone);
        int instructionCount = Math.max(1, clone.instructions.size());
        // Valid classfiles already carry the exact verifier max stack. Keep a
        // small bounded floor for synthetic/malformed fixtures instead of
        // multiplying frame width by method length.
        if (clone.maxStack <= 0) {
            clone.maxStack = Math.min(64, instructionCount + 16);
        }
        clone.maxLocals = Math.max(clone.maxLocals, conservativeMaxLocals(clone));
        return clone;
    }

    private int conservativeMaxLocals(MethodNode method) {
        int maximum = (method.access & ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            maximum += argument.getSize();
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof VarInsnNode variable) {
                int width = variable.getOpcode() == LLOAD
                                || variable.getOpcode() == DLOAD
                                || variable.getOpcode() == LSTORE
                                || variable.getOpcode() == DSTORE
                        ? 2
                        : 1;
                maximum = Math.max(maximum, variable.var + width);
            } else if (instruction instanceof IincInsnNode increment) {
                maximum = Math.max(maximum, increment.var + 1);
            }
        }
        return Math.max(maximum, 1);
    }

    private IdentityHashMap<AbstractInsnNode, Integer> instructionIndices(MethodNode method) {
        IdentityHashMap<AbstractInsnNode, Integer> result = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            result.put(instruction, index++);
        }
        return result;
    }

    private boolean isReflectionLookup(String name) {
        return name.equals("getField")
                || name.equals("getFields")
                || name.equals("getDeclaredField")
                || name.equals("getDeclaredFields");
    }

    private boolean isLookupFieldApi(String name) {
        return name.equals("findGetter")
                || name.equals("findSetter")
                || name.equals("findStaticGetter")
                || name.equals("findStaticSetter")
                || name.equals("findVarHandle")
                || name.equals("findStaticVarHandle")
                || name.equals("unreflectGetter")
                || name.equals("unreflectSetter")
                || name.equals("unreflectVarHandle");
    }

    private boolean isUnsafeOwner(String owner) {
        return owner.equals("sun/misc/Unsafe") || owner.equals("jdk/internal/misc/Unsafe");
    }

    private boolean isAgentOwner(String owner) {
        return owner.startsWith("java/lang/instrument/")
                || owner.startsWith("java/lang/management/Instrumentation");
    }

    private boolean isNativeLoadingCall(MethodInsnNode call) {
        return call.owner.equals("java/lang/System")
                        && (call.name.equals("load") || call.name.equals("loadLibrary"))
                || call.owner.equals("java/lang/Runtime")
                        && (call.name.equals("load") || call.name.equals("loadLibrary"));
    }
}
