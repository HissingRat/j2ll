package xyz.melodysky.packaging;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.frontend.classfile.ParsedMethod;

public final class FallbackHelperClassFactory implements Opcodes {
    public static final String HELPER_METHOD_NAME = "invoke";

    public FallbackHelperClass create(String originalMethodId, String originalMethodKey, String ownerInternalName) {
        return create(
                originalMethodId,
                originalMethodKey,
                ownerInternalName,
                FallbackBlobInput.methodName(originalMethodKey),
                FallbackBlobInput.descriptor(originalMethodKey),
                true,
                null,
                List.of());
    }

    public FallbackHelperClass create(String originalMethodId, ParsedMethod method) {
        return create(originalMethodId, method, List.of());
    }

    public FallbackHelperClass create(
            String originalMethodId,
            ParsedMethod method,
            List<FallbackSidecarFieldAccess> sidecarFieldAccesses) {
        return create(
                originalMethodId,
                method.methodKey(),
                method.owner(),
                method.name(),
                method.descriptor(),
                method.accessFlags().isStatic(),
                method.methodNode(),
                sidecarFieldAccesses);
    }

    public FallbackHelperClass create(FallbackBlobInput input) {
        return create(
                input.originalMethodId(),
                input.originalMethodKey(),
                input.ownerInternalName(),
                input.methodName(),
                input.descriptor(),
                input.staticMethod(),
                input.methodNode(),
                input.sidecarFieldAccesses());
    }

    private FallbackHelperClass create(
            String originalMethodId,
            String originalMethodKey,
            String ownerInternalName,
            String methodName,
            String descriptor,
            boolean staticMethod,
            MethodNode methodNode,
            List<FallbackSidecarFieldAccess> sidecarFieldAccesses) {
        sidecarFieldAccesses = List.copyOf(sidecarFieldAccesses);
        String internalName = helperClassName(originalMethodId, ownerInternalName);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        if (methodNode != null) {
            emitOriginalBodyFallback(
                    writer,
                    methodNode,
                    ownerInternalName,
                    descriptor,
                    staticMethod,
                    sidecarFieldAccesses);
        } else if (originalMethodKey.contains("#substring!(Ljava/lang/String;)Ljava/lang/String;")) {
            if (!sidecarFieldAccesses.isEmpty()) {
                throw new IllegalArgumentException(
                        "legacy fallback cannot carry sidecar field accesses");
            }
            emitLegacySubstringFallback(writer);
        } else {
            throw new IllegalArgumentException("fallback helper requires original bytecode for " + originalMethodKey);
        }
        writer.visitEnd();
        return new FallbackHelperClass(internalName, writer.toByteArray());
    }

    public String helperClassName(String originalMethodId, String ownerInternalName) {
        int slash = ownerInternalName.lastIndexOf('/');
        String packagePrefix = slash < 0 ? "" : ownerInternalName.substring(0, slash + 1);
        return packagePrefix + "J2llFallback$" + safeSegment(originalMethodId);
    }

    public String helperDescriptor(String ownerInternalName, String descriptor, boolean staticMethod) {
        return helperDescriptor(ownerInternalName, descriptor, staticMethod, false);
    }

    public String helperDescriptor(
            String ownerInternalName,
            String descriptor,
            boolean staticMethod,
            boolean includeReferenceSidecar) {
        if (staticMethod) {
            return includeReferenceSidecar
                    ? appendReferenceSidecar(descriptor)
                    : descriptor;
        }
        Type[] arguments = Type.getArgumentTypes(descriptor);
        StringBuilder result = new StringBuilder();
        result.append("(L").append(ownerInternalName).append(';');
        for (Type argument : arguments) {
            result.append(argument.getDescriptor());
        }
        if (includeReferenceSidecar) {
            result.append("[Ljava/lang/Object;");
        }
        result.append(')').append(Type.getReturnType(descriptor).getDescriptor());
        return result.toString();
    }

    private void emitOriginalBodyFallback(
            ClassWriter writer,
            MethodNode original,
            String ownerInternalName,
            String descriptor,
            boolean staticMethod,
            List<FallbackSidecarFieldAccess> sidecarFieldAccesses) {
        boolean sidecarAware = !sidecarFieldAccesses.isEmpty();
        String helperDescriptor = helperDescriptor(
                ownerInternalName,
                descriptor,
                staticMethod,
                sidecarAware);
        MethodNode helper = new MethodNode(
                ASM9,
                ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                HELPER_METHOD_NAME,
                helperDescriptor,
                null,
                original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
        original.accept(helper);
        stripCopiedMetadata(helper);
        removeFrames(helper);
        if (sidecarAware) {
            rewriteSidecarFieldAccesses(
                    helper,
                    descriptor,
                    staticMethod,
                    sidecarFieldAccesses);
            verifySidecarFieldReferencesRemoved(
                    helper,
                    sidecarFieldAccesses);
        }
        helper.accept(writer);
    }

    private void rewriteSidecarFieldAccesses(
            MethodNode helper,
            String originalDescriptor,
            boolean originalStatic,
            List<FallbackSidecarFieldAccess> accesses) {
        Map<FieldId, FallbackSidecarFieldAccess> accessByField = new HashMap<>();
        for (FallbackSidecarFieldAccess access : accesses) {
            FallbackSidecarFieldAccess previous =
                    accessByField.putIfAbsent(access.field(), access);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate fallback sidecar field access: "
                                + access.field().fieldKey());
            }
        }

        int sidecarArgumentLocal = originalParameterSlots(
                originalDescriptor,
                originalStatic);
        int sidecarScratchLocal = Math.max(
                helper.maxLocals,
                sidecarArgumentLocal + 1);
        InsnList prologue = new InsnList();
        prologue.add(new VarInsnNode(ALOAD, sidecarArgumentLocal));
        prologue.add(new VarInsnNode(ASTORE, sidecarScratchLocal));
        helper.instructions.insert(prologue);
        helper.maxLocals = sidecarScratchLocal + 1;

        Map<FieldId, int[]> actualCounts = new HashMap<>();
        for (var instruction = helper.instructions.getFirst();
                instruction != null; ) {
            var next = instruction.getNext();
            if (instruction instanceof FieldInsnNode fieldInstruction) {
                FieldId field = new FieldId(
                        fieldInstruction.owner,
                        fieldInstruction.name,
                        fieldInstruction.desc);
                FallbackSidecarFieldAccess access = accessByField.get(field);
                if (access != null) {
                    InsnList replacement = new InsnList();
                    int[] counts = actualCounts.computeIfAbsent(
                            field,
                            ignored -> new int[2]);
                    if (fieldInstruction.getOpcode() == GETSTATIC) {
                        replacement.add(new VarInsnNode(
                                ALOAD,
                                sidecarScratchLocal));
                        pushInt(replacement, access.slot().referenceIndex());
                        replacement.add(new InsnNode(AALOAD));
                        replacement.add(new TypeInsnNode(
                                CHECKCAST,
                                Type.getType(field.descriptor()).getInternalName()));
                        counts[0]++;
                    } else if (fieldInstruction.getOpcode() == PUTSTATIC) {
                        replacement.add(new VarInsnNode(
                                ALOAD,
                                sidecarScratchLocal));
                        replacement.add(new InsnNode(SWAP));
                        pushInt(replacement, access.slot().referenceIndex());
                        replacement.add(new InsnNode(SWAP));
                        replacement.add(new InsnNode(AASTORE));
                        counts[1]++;
                    } else {
                        throw new IllegalArgumentException(
                                "fallback sidecar field is not accessed statically: "
                                        + field.fieldKey());
                    }
                    helper.instructions.insertBefore(
                            fieldInstruction,
                            replacement);
                    helper.instructions.remove(fieldInstruction);
                }
            }
            instruction = next;
        }
        for (FallbackSidecarFieldAccess access : accesses) {
            int[] counts = actualCounts.getOrDefault(
                    access.field(),
                    new int[2]);
            if (counts[0] != access.readCount()
                    || counts[1] != access.writeCount()) {
                throw new IllegalArgumentException(
                        "fallback sidecar access count mismatch for "
                                + access.field().fieldKey()
                                + ": expected reads="
                                + access.readCount()
                                + ", writes="
                                + access.writeCount()
                                + ", actual reads="
                                + counts[0]
                                + ", writes="
                                + counts[1]);
            }
        }
    }

    private void verifySidecarFieldReferencesRemoved(
            MethodNode helper,
            List<FallbackSidecarFieldAccess> accesses) {
        Map<FieldId, Boolean> approvedFields = new HashMap<>();
        for (FallbackSidecarFieldAccess access : accesses) {
            approvedFields.put(access.field(), Boolean.TRUE);
        }
        for (var instruction = helper.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode fieldInstruction
                    && approvedFields.containsKey(new FieldId(
                            fieldInstruction.owner,
                            fieldInstruction.name,
                            fieldInstruction.desc))) {
                throw residualFieldReference(
                        fieldInstruction.owner,
                        fieldInstruction.name,
                        fieldInstruction.desc);
            }
            if (instruction instanceof LdcInsnNode ldc
                    && referencesApprovedField(
                            ldc.cst,
                            approvedFields)) {
                throw new IllegalArgumentException(
                        "fallback sidecar helper retains a field reference in ldc metadata");
            }
            if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                if (referencesApprovedField(
                        dynamic.bsm,
                        approvedFields)) {
                    throw new IllegalArgumentException(
                            "fallback sidecar helper retains a field reference in invokedynamic bootstrap metadata");
                }
                for (Object argument : dynamic.bsmArgs) {
                    if (referencesApprovedField(
                            argument,
                            approvedFields)) {
                        throw new IllegalArgumentException(
                                "fallback sidecar helper retains a field reference in invokedynamic bootstrap metadata");
                    }
                }
            }
        }
    }

    private boolean referencesApprovedField(
            Object value,
            Map<FieldId, Boolean> approvedFields) {
        if (value instanceof Handle handle) {
            return approvedFields.containsKey(new FieldId(
                    handle.getOwner(),
                    handle.getName(),
                    handle.getDesc()));
        }
        if (value instanceof ConstantDynamic dynamic) {
            if (referencesApprovedField(
                    dynamic.getBootstrapMethod(),
                    approvedFields)) {
                return true;
            }
            for (int index = 0;
                    index < dynamic.getBootstrapMethodArgumentCount();
                    index++) {
                if (referencesApprovedField(
                        dynamic.getBootstrapMethodArgument(index),
                        approvedFields)) {
                    return true;
                }
            }
        }
        return false;
    }

    private IllegalArgumentException residualFieldReference(
            String owner,
            String name,
            String descriptor) {
        return new IllegalArgumentException(
                "fallback sidecar helper retains field reference "
                        + owner
                        + "#"
                        + name
                        + "!"
                        + descriptor);
    }

    private int originalParameterSlots(
            String descriptor,
            boolean staticMethod) {
        int slots = staticMethod ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            slots += argument.getSize();
        }
        return slots;
    }

    private String appendReferenceSidecar(String descriptor) {
        int end = descriptor.indexOf(')');
        if (end < 0) {
            throw new IllegalArgumentException(
                    "invalid method descriptor: " + descriptor);
        }
        return descriptor.substring(0, end)
                + "[Ljava/lang/Object;"
                + descriptor.substring(end);
    }

    private void removeFrames(MethodNode helper) {
        for (var instruction = helper.instructions.getFirst();
                instruction != null; ) {
            var next = instruction.getNext();
            if (instruction instanceof FrameNode) {
                helper.instructions.remove(instruction);
            }
            instruction = next;
        }
    }

    private void stripCopiedMetadata(MethodNode helper) {
        helper.signature = null;
        helper.parameters = null;
        helper.annotationDefault = null;
        helper.visibleAnnotations = null;
        helper.invisibleAnnotations = null;
        helper.visibleTypeAnnotations = null;
        helper.invisibleTypeAnnotations = null;
        helper.visibleParameterAnnotations = null;
        helper.invisibleParameterAnnotations = null;
        helper.visibleAnnotableParameterCount = 0;
        helper.invisibleAnnotableParameterCount = 0;
        helper.attrs = null;
        helper.localVariables = null;
        helper.visibleLocalVariableAnnotations = null;
        helper.invisibleLocalVariableAnnotations = null;
    }

    private void pushInt(InsnList instructions, int value) {
        if (value >= -1 && value <= 5) {
            instructions.add(new InsnNode(ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            instructions.add(new IntInsnNode(BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            instructions.add(new IntInsnNode(SIPUSH, value));
        } else {
            instructions.add(new LdcInsnNode(value));
        }
    }

    private void emitLegacySubstringFallback(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                HELPER_METHOD_NAME,
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ICONST_1);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private String safeSegment(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_'
                    || ch == '$') {
                result.append(ch);
            } else {
                result.append('_');
                if (ch > 127) {
                    result.append(Integer.toHexString(ch).toLowerCase(Locale.ROOT));
                    result.append('_');
                }
            }
        }
        return result.toString();
    }
}
