package xyz.melodysky.analysis.field;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

/** Recovers field-bearing value provenance from ASM source frames. */
final class FieldObserverValueResolver implements Opcodes {
    private final FieldObserverFrameView frameView;
    private final FieldObserverKnownValueResolver knownValues;
    private final FieldObserverDeclarationIndex declarations;
    private final FieldObserverSourceInterpreter interpreter;

    FieldObserverValueResolver(
            Frame<SourceValue>[] frames,
            Map<AbstractInsnNode, Integer> instructionIndices,
            FieldObserverDeclarationIndex declarations,
            FieldObserverSourceInterpreter interpreter) {
        this.frameView = new FieldObserverFrameView(frames, instructionIndices);
        this.knownValues = new FieldObserverKnownValueResolver(frameView, interpreter);
        this.declarations = declarations;
        this.interpreter = interpreter;
    }

    FieldObserverProvenance reflectionField(SourceValue value) {
        return resolveField(value, new ResolutionContext());
    }

    FieldObserverProvenance methodHandle(SourceValue value) {
        return resolveHandle(value, new ResolutionContext());
    }

    FieldObserverProvenance varHandle(SourceValue value) {
        return resolveVarHandle(value, new ResolutionContext());
    }

    FieldObserverProvenance unsafeBase(SourceValue value) {
        return resolveUnsafeMetadata(value, true, new ResolutionContext());
    }

    FieldObserverProvenance unsafeOffset(SourceValue value) {
        return resolveUnsafeMetadata(value, false, new ResolutionContext());
    }

    FieldObserverProvenance reflectionLookup(MethodInsnNode call) {
        return reflectionLookup(call, new ResolutionContext());
    }

    private FieldObserverProvenance reflectionLookup(
            MethodInsnNode call,
            ResolutionContext context) {
        FieldObserverFrameView.InvocationOperands operands = operands(call);
        if (operands == null) {
            return FieldObserverProvenance.global();
        }
        Set<String> owners = knownValues.classOwners(operands.receiver(), context.budget);
        boolean declaredOnly = call.name.startsWith("getDeclared");
        if (call.name.equals("getDeclaredFields") || call.name.equals("getFields")) {
            return owners == null
                    ? FieldObserverProvenance.global()
                    : owners(owners);
        }
        if (operands.arguments().length < 1) {
            return FieldObserverProvenance.global();
        }
        Set<String> names = knownValues.strings(operands.arguments()[0], context.budget);
        return lookupFields(owners, names, Optional.empty(), declaredOnly);
    }

    FieldObserverProvenance lookupHandle(MethodInsnNode call) {
        return lookupHandle(call, new ResolutionContext());
    }

    private FieldObserverProvenance lookupHandle(
            MethodInsnNode call,
            ResolutionContext context) {
        FieldObserverFrameView.InvocationOperands operands = operands(call);
        if (operands == null) {
            return FieldObserverProvenance.global();
        }
        if (call.name.equals("unreflectGetter")
                || call.name.equals("unreflectSetter")
                || call.name.equals("unreflectVarHandle")) {
            return operands.arguments().length == 1
                    ? resolveField(operands.arguments()[0], context)
                    : FieldObserverProvenance.global();
        }
        if (operands.arguments().length < 2) {
            return FieldObserverProvenance.global();
        }
        Set<String> owners = knownValues.classOwners(operands.arguments()[0], context.budget);
        Set<String> names = knownValues.strings(operands.arguments()[1], context.budget);
        Optional<Set<String>> descriptors = Optional.empty();
        if (operands.arguments().length >= 3) {
            Set<String> types = knownValues.classDescriptors(
                    operands.arguments()[2],
                    context.budget);
            if (types != null) {
                descriptors = Optional.of(types);
            }
        }
        return lookupFields(owners, names, descriptors, false);
    }

    private FieldObserverProvenance ordinaryLookupHandle(
            MethodInsnNode call,
            ResolutionContext context) {
        if (!call.name.equals("findStatic")) {
            // Virtual/special/constructor handles need dispatch and verifier
            // proofs not owned by this field-observer stage.
            return FieldObserverProvenance.global();
        }
        FieldObserverFrameView.InvocationOperands operands = operands(call);
        if (operands == null || operands.arguments().length < 3) {
            return FieldObserverProvenance.global();
        }
        Set<String> owners = knownValues.classOwners(operands.arguments()[0], context.budget);
        Set<String> names = knownValues.strings(operands.arguments()[1], context.budget);
        Set<String> descriptors = knownValues.classDescriptors(
                operands.arguments()[2],
                context.budget);
        if (owners == null
                || names == null
                || descriptors == null
                || owners.isEmpty()
                || names.isEmpty()
                || descriptors.isEmpty()) {
            return FieldObserverProvenance.global();
        }
        for (String owner : owners) {
            for (String name : names) {
                for (String descriptor : descriptors) {
                    if (!declarations.hasScannedStaticMethodBody(owner, name, descriptor)) {
                        return FieldObserverProvenance.global();
                    }
                }
            }
        }
        return FieldObserverProvenance.nonField();
    }

    FieldObserverFrameView.InvocationOperands operands(MethodInsnNode call) {
        return frameView.operands(call);
    }

    private FieldObserverProvenance resolveField(
            SourceValue value,
            ResolutionContext context) {
        if (!context.enter(value)) {
            return FieldObserverProvenance.global();
        }
        try {
            ArrayList<FieldObserverProvenance> resolved = new ArrayList<>();
            for (AbstractInsnNode source : value.insns) {
                resolved.add(resolveFieldSource(source, context));
            }
            return unionOrGlobal(resolved);
        } finally {
            context.exit(value);
        }
    }

    private FieldObserverProvenance resolveFieldSource(
            AbstractInsnNode source,
            ResolutionContext context) {
        if (interpreter.isUnknownSource(source)) {
            return FieldObserverProvenance.global();
        }
        if (source instanceof MethodInsnNode call
                && call.owner.equals("java/lang/Class")
                && isReflectionLookup(call.name)) {
            return reflectionLookup(call, context);
        }
        if (source.getOpcode() == AALOAD) {
            SourceValue array = frameView.stackOperand(source, 2);
            return array == null
                    ? FieldObserverProvenance.global()
                    : resolveFieldArray(array, context);
        }
        FieldObserverProvenance copied = resolveCopySource(source, context, this::resolveField);
        return copied != null ? copied : FieldObserverProvenance.global();
    }

    private FieldObserverProvenance resolveFieldArray(
            SourceValue value,
            ResolutionContext context) {
        if (!context.enter(value)) {
            return FieldObserverProvenance.global();
        }
        try {
            ArrayList<FieldObserverProvenance> resolved = new ArrayList<>();
            for (AbstractInsnNode source : value.insns) {
                if (interpreter.isUnknownSource(source)) {
                    resolved.add(FieldObserverProvenance.global());
                    continue;
                }
                if (source instanceof MethodInsnNode call
                        && call.owner.equals("java/lang/Class")
                        && (call.name.equals("getFields") || call.name.equals("getDeclaredFields"))) {
                    resolved.add(reflectionLookup(call, context));
                    continue;
                }
                FieldObserverProvenance copied = resolveCopySource(
                        source,
                        context,
                        this::resolveFieldArray);
                resolved.add(copied != null ? copied : FieldObserverProvenance.global());
            }
            return unionOrGlobal(resolved);
        } finally {
            context.exit(value);
        }
    }

    private FieldObserverProvenance resolveHandle(
            SourceValue value,
            ResolutionContext context) {
        if (!context.enter(value)) {
            return FieldObserverProvenance.global();
        }
        try {
            ArrayList<FieldObserverProvenance> resolved = new ArrayList<>();
            for (AbstractInsnNode source : value.insns) {
                if (interpreter.isUnknownSource(source)) {
                    resolved.add(FieldObserverProvenance.global());
                    continue;
                }
                if (source instanceof LdcInsnNode ldc && ldc.cst instanceof Handle handle) {
                    resolved.add(fieldHandle(handle));
                } else if (source instanceof MethodInsnNode call
                        && call.owner.equals("java/lang/invoke/MethodHandles$Lookup")
                        && isLookupFieldApi(call.name)) {
                    resolved.add(lookupHandle(call, context));
                } else if (source instanceof MethodInsnNode call
                        && call.owner.equals("java/lang/invoke/MethodHandles$Lookup")
                        && isKnownNonFieldLookup(call.name)) {
                    resolved.add(ordinaryLookupHandle(call, context));
                } else if (source instanceof MethodInsnNode call
                        && call.owner.equals("java/lang/invoke/VarHandle")
                        && call.name.equals("toMethodHandle")) {
                    FieldObserverFrameView.InvocationOperands operands = operands(call);
                    resolved.add(operands == null
                            ? FieldObserverProvenance.global()
                            : resolveVarHandle(operands.receiver(), context));
                } else if (source instanceof MethodInsnNode call
                        && call.owner.equals("java/lang/invoke/MethodHandle")
                        && preservesHandleTarget(call.name)) {
                    FieldObserverFrameView.InvocationOperands operands = operands(call);
                    resolved.add(operands == null
                            ? FieldObserverProvenance.global()
                            : resolveHandle(operands.receiver(), context));
                } else {
                    FieldObserverProvenance copied = resolveCopySource(
                            source,
                            context,
                            this::resolveHandle);
                    resolved.add(copied != null ? copied : FieldObserverProvenance.global());
                }
            }
            return unionOrGlobal(resolved);
        } finally {
            context.exit(value);
        }
    }

    private FieldObserverProvenance resolveVarHandle(
            SourceValue value,
            ResolutionContext context) {
        if (!context.enter(value)) {
            return FieldObserverProvenance.global();
        }
        try {
            ArrayList<FieldObserverProvenance> resolved = new ArrayList<>();
            for (AbstractInsnNode source : value.insns) {
                if (interpreter.isUnknownSource(source)) {
                    resolved.add(FieldObserverProvenance.global());
                    continue;
                }
                if (source instanceof MethodInsnNode call
                        && call.owner.equals("java/lang/invoke/MethodHandles$Lookup")
                        && (call.name.equals("findVarHandle")
                                || call.name.equals("findStaticVarHandle")
                                || call.name.equals("unreflectVarHandle"))) {
                    resolved.add(lookupHandle(call, context));
                } else if (source instanceof MethodInsnNode call
                        && call.owner.equals("java/lang/invoke/MethodHandles")
                        && (call.name.equals("arrayElementVarHandle")
                                || call.name.equals("byteArrayViewVarHandle"))) {
                    resolved.add(FieldObserverProvenance.nonField());
                } else {
                    FieldObserverProvenance copied = resolveCopySource(
                            source,
                            context,
                            this::resolveVarHandle);
                    resolved.add(copied != null ? copied : FieldObserverProvenance.global());
                }
            }
            return unionOrGlobal(resolved);
        } finally {
            context.exit(value);
        }
    }

    private FieldObserverProvenance resolveUnsafeMetadata(
            SourceValue value,
            boolean base,
            ResolutionContext context) {
        if (!context.enter(value)) {
            return FieldObserverProvenance.global();
        }
        try {
            ArrayList<FieldObserverProvenance> resolved = new ArrayList<>();
            for (AbstractInsnNode source : value.insns) {
                if (source instanceof MethodInsnNode call
                        && isUnsafeOwner(call.owner)
                        && call.name.equals(base ? "staticFieldBase" : "staticFieldOffset")) {
                    FieldObserverFrameView.InvocationOperands operands = operands(call);
                    FieldObserverProvenance field = operands == null || operands.arguments().length != 1
                            ? FieldObserverProvenance.global()
                            : resolveField(operands.arguments()[0], context);
                    resolved.add(base ? ownerConstraint(field) : field);
                } else {
                    FieldObserverProvenance copied = resolveCopySource(
                            source,
                            context,
                            (nested, nestedContext) -> resolveUnsafeMetadata(
                                    nested,
                                    base,
                                    nestedContext));
                    resolved.add(copied != null ? copied : FieldObserverProvenance.global());
                }
            }
            return unionOrGlobal(resolved);
        } finally {
            context.exit(value);
        }
    }

    private FieldObserverProvenance resolveCopySource(
            AbstractInsnNode source,
            ResolutionContext context,
            NestedResolver nestedResolver) {
        int opcode = source.getOpcode();
        if (source instanceof VarInsnNode variable && isLoad(opcode)) {
            SourceValue local = frameView.localBefore(source, variable.var);
            return local == null
                    ? FieldObserverProvenance.global()
                    : nestedResolver.resolve(local, context);
        }
        if (source instanceof VarInsnNode && isStore(opcode)) {
            SourceValue stack = frameView.stackOperand(source, 1);
            return stack == null
                    ? FieldObserverProvenance.global()
                    : nestedResolver.resolve(stack, context);
        }
        if (opcode == CHECKCAST || isStackCopy(opcode)) {
            SourceValue stack = frameView.stackOperand(source, 1);
            return stack == null
                    ? FieldObserverProvenance.global()
                    : nestedResolver.resolve(stack, context);
        }
        return null;
    }

    private FieldObserverProvenance lookupFields(
            Set<String> owners,
            Set<String> names,
            Optional<Set<String>> descriptors,
            boolean declaredOnly) {
        if (owners == null) {
            return FieldObserverProvenance.global();
        }
        if (names == null) {
            return unionOwners(owners);
        }
        ArrayList<FieldObserverProvenance> results = new ArrayList<>();
        for (String owner : owners) {
            if (!declarations.containsOwner(owner)) {
                results.add(FieldObserverProvenance.owner(owner));
                continue;
            }
            LinkedHashSet<FieldId> matches = new LinkedHashSet<>();
            for (String name : names) {
                if (descriptors.isPresent()) {
                    for (String descriptor : descriptors.orElseThrow()) {
                        declarations.resolve(owner, name, descriptor).ifPresent(matches::add);
                    }
                } else {
                    matches.addAll(declaredOnly
                            ? declarations.declaredByName(owner, name)
                            : declarations.visibleByName(owner, name));
                }
            }
            results.add(matches.isEmpty()
                    ? FieldObserverProvenance.owner(owner)
                    : FieldObserverProvenance.exact(matches));
        }
        return unionOrGlobal(results);
    }

    private FieldObserverProvenance fieldHandle(Handle handle) {
        if (!Set.of(H_GETFIELD, H_GETSTATIC, H_PUTFIELD, H_PUTSTATIC).contains(handle.getTag())) {
            return FieldObserverProvenance.nonField();
        }
        return declarations.resolve(handle.getOwner(), handle.getName(), handle.getDesc())
                .map(FieldObserverProvenance::exact)
                .orElseGet(() -> FieldObserverProvenance.owner(handle.getOwner()));
    }

    private FieldObserverProvenance ownerConstraint(FieldObserverProvenance provenance) {
        if (provenance.globalScope()) {
            return provenance;
        }
        LinkedHashSet<String> owners = new LinkedHashSet<>(provenance.owners());
        provenance.exactFields().stream().map(FieldId::owner).forEach(owners::add);
        return owners.isEmpty() && provenance.nonFieldValue()
                ? FieldObserverProvenance.nonField()
                : unionOwners(owners);
    }

    private FieldObserverProvenance owners(Collection<String> owners) {
        return unionOwners(owners);
    }

    private FieldObserverProvenance unionOwners(Collection<String> owners) {
        return FieldObserverProvenance.union(owners.stream()
                .map(FieldObserverProvenance::owner)
                .toList());
    }

    private FieldObserverProvenance unionOrGlobal(List<FieldObserverProvenance> values) {
        return values.isEmpty()
                ? FieldObserverProvenance.global()
                : FieldObserverProvenance.union(values);
    }

    private boolean isLoad(int opcode) {
        return opcode >= ILOAD && opcode <= ALOAD;
    }

    private boolean isStore(int opcode) {
        return opcode >= ISTORE && opcode <= ASTORE;
    }

    private boolean isStackCopy(int opcode) {
        // Only plain DUP has an unambiguous single source at stack top.
        // The X/2 forms can source different input positions; unsupported
        // permutations deliberately fall back to GLOBAL.
        return opcode == DUP;
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

    private boolean preservesHandleTarget(String name) {
        return name.equals("asType")
                || name.equals("asSpreader")
                || name.equals("asCollector")
                || name.equals("asVarargsCollector")
                || name.equals("asFixedArity")
                || name.equals("bindTo");
    }

    private boolean isKnownNonFieldLookup(String name) {
        return name.equals("findStatic")
                || name.equals("findVirtual")
                || name.equals("findSpecial")
                || name.equals("findConstructor");
    }

    private boolean isUnsafeOwner(String owner) {
        return owner.equals("sun/misc/Unsafe") || owner.equals("jdk/internal/misc/Unsafe");
    }

    private static final class ResolutionContext {
        private static final int MAX_DEPTH = 128;

        private final java.util.IdentityHashMap<SourceValue, Boolean> visiting =
                new java.util.IdentityHashMap<>();
        private final FieldObserverResolutionBudget budget =
                new FieldObserverResolutionBudget();

        boolean enter(SourceValue value) {
            if (value == null
                    || visiting.size() >= MAX_DEPTH
                    || visiting.containsKey(value)
                    || !budget.tryConsume()) {
                return false;
            }
            visiting.put(value, Boolean.TRUE);
            return true;
        }

        void exit(SourceValue value) {
            visiting.remove(value);
        }
    }

    @FunctionalInterface
    private interface NestedResolver {
        FieldObserverProvenance resolve(
                SourceValue value,
                ResolutionContext context);
    }
}
