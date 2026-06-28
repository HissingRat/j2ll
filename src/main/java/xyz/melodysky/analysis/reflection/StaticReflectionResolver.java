package xyz.melodysky.analysis.reflection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import xyz.melodysky.frontend.classfile.AsmInstructions;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.runtime.metadata.ClassMetadata;
import xyz.melodysky.runtime.metadata.FieldMetadata;
import xyz.melodysky.runtime.metadata.MethodMetadata;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndex;

public final class StaticReflectionResolver implements Opcodes {
    public ReflectionPlan resolve(ParsedProgram program, RuntimeMetadataIndex metadataIndex) {
        ReflectionPlanBuilder builder = new ReflectionPlanBuilder();
        for (ParsedClass parsedClass : program.classes()) {
            for (ParsedMethod method : parsedClass.methods()) {
                if (method.hasCode()) {
                    resolveMethod(method, metadataIndex, builder);
                }
            }
        }
        return builder.build();
    }

    public ReflectionPlan resolve(ParsedMethod method, RuntimeMetadataIndex metadataIndex) {
        ReflectionPlanBuilder builder = new ReflectionPlanBuilder();
        resolveMethod(method, metadataIndex, builder);
        return builder.build();
    }

    private void resolveMethod(
            ParsedMethod method,
            RuntimeMetadataIndex metadataIndex,
            ReflectionPlanBuilder builder) {
        ArrayDeque<ReflectionValue> stack = new ArrayDeque<>();
        int executableIndex = 0;
        for (AbstractInsnNode instruction = method.methodNode().instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!AsmInstructions.isExecutable(instruction)) {
                continue;
            }
            int opcode = instruction.getOpcode();
            if (instruction instanceof LdcInsnNode ldc) {
                handleLdc(ldc.cst, method, executableIndex, metadataIndex, builder, stack);
            } else if (opcode >= ICONST_M1 && opcode <= ICONST_5) {
                stack.push(ReflectionValue.intValue(opcode == ICONST_M1 ? -1 : opcode - ICONST_0));
            } else if (instruction instanceof IntInsnNode intInsn && (opcode == BIPUSH || opcode == SIPUSH)) {
                stack.push(ReflectionValue.intValue(intInsn.operand));
            } else if (opcode == ACONST_NULL) {
                stack.push(ReflectionValue.nullValue());
            } else if (instruction instanceof VarInsnNode varInsn && opcode == ALOAD) {
                stack.push(ReflectionValue.unknown("local:" + varInsn.var));
            } else if (instruction instanceof VarInsnNode && opcode == ASTORE) {
                popOrUnknown(stack);
            } else if (opcode == POP) {
                popOrUnknown(stack);
            } else if (opcode == DUP) {
                stack.push(peekOrUnknown(stack));
            } else if (instruction instanceof TypeInsnNode typeInsn && opcode == ANEWARRAY) {
                ReflectionValue count = popOrUnknown(stack);
                stack.push(ReflectionValue.arrayValue(typeInsn.desc, count.intValue().orElse(null)));
            } else if (opcode == AASTORE) {
                handleArrayStore(stack);
            } else if (instruction instanceof MethodInsnNode methodInsn) {
                handleMethodCall(method, executableIndex, methodInsn, stack, metadataIndex, builder);
            } else if (instruction instanceof TypeInsnNode && opcode == CHECKCAST) {
                // The static reflection value on the stack is unchanged by CHECKCAST.
            } else {
                handleUnknownInstruction(opcode, stack);
            }
            executableIndex++;
        }
    }

    private void handleLdc(
            Object constant,
            ParsedMethod method,
            int instructionIndex,
            RuntimeMetadataIndex metadataIndex,
            ReflectionPlanBuilder builder,
            ArrayDeque<ReflectionValue> stack) {
        if (constant instanceof String stringValue) {
            stack.push(ReflectionValue.stringValue(stringValue));
            return;
        }
        if (constant instanceof Type type) {
            String descriptor = descriptorForType(type);
            stack.push(ReflectionValue.classValue(descriptor));
            classInternalName(descriptor).ifPresent(internalName -> {
                if (metadataIndex.findClass(internalName).isPresent()) {
                    builder.classTargets.add(new ReflectionClassTarget(
                            internalName,
                            false,
                            sourceSite(method, instructionIndex, "classLiteral")));
                }
            });
            return;
        }
        stack.push(ReflectionValue.unknown("ldc:" + constant));
    }

    private void handleMethodCall(
            ParsedMethod currentMethod,
            int instructionIndex,
            MethodInsnNode methodInsn,
            ArrayDeque<ReflectionValue> stack,
            RuntimeMetadataIndex metadataIndex,
            ReflectionPlanBuilder builder) {
        ArrayList<ReflectionValue> operands = new ArrayList<>();
        int parameterCount = parameterDescriptors(methodInsn.desc).size();
        for (int index = 0; index < parameterCount; index++) {
            operands.add(0, popOrUnknown(stack));
        }
        if (methodInsn.getOpcode() == INVOKESPECIAL
                || methodInsn.getOpcode() == INVOKEVIRTUAL
                || methodInsn.getOpcode() == INVOKEINTERFACE) {
            operands.add(0, popOrUnknown(stack));
        }

        Optional<ReflectionValue> result = handleReflectionCall(
                currentMethod,
                instructionIndex,
                methodInsn,
                operands,
                metadataIndex,
                builder);
        if (returnDescriptor(methodInsn.desc).equals("V")) {
            return;
        }
        stack.push(result.orElseGet(() -> ReflectionValue.unknown("call:" + methodInsn.owner + "#" + methodInsn.name)));
    }

    private Optional<ReflectionValue> handleReflectionCall(
            ParsedMethod currentMethod,
            int instructionIndex,
            MethodInsnNode methodInsn,
            List<ReflectionValue> operands,
            RuntimeMetadataIndex metadataIndex,
            ReflectionPlanBuilder builder) {
        if (methodInsn.owner.equals("java/lang/Class")
                && methodInsn.name.equals("forName")
                && methodInsn.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
            return handleClassForName(currentMethod, instructionIndex, operands, metadataIndex, builder, true);
        }
        if (methodInsn.owner.equals("java/lang/Class")
                && methodInsn.name.equals("forName")
                && methodInsn.desc.equals("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;")) {
            boolean initialize = operands.size() > 1 && operands.get(1).intValue().orElse(1) != 0;
            return handleClassForName(currentMethod, instructionIndex, operands, metadataIndex, builder, initialize);
        }
        if (methodInsn.owner.equals("java/lang/Class")
                && methodInsn.name.equals("getDeclaredMethod")
                && methodInsn.desc.equals("(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;")) {
            return handleGetDeclaredMethod(currentMethod, instructionIndex, operands, metadataIndex, builder);
        }
        if (methodInsn.owner.equals("java/lang/Class")
                && methodInsn.name.equals("getDeclaredField")
                && methodInsn.desc.equals("(Ljava/lang/String;)Ljava/lang/reflect/Field;")) {
            return handleGetDeclaredField(currentMethod, instructionIndex, operands, metadataIndex, builder);
        }
        if (methodInsn.owner.equals("java/lang/Class")
                && methodInsn.name.equals("getDeclaredConstructor")
                && methodInsn.desc.equals("([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;")) {
            return handleGetDeclaredConstructor(currentMethod, instructionIndex, operands, metadataIndex, builder);
        }
        if (methodInsn.owner.equals("java/lang/Class")
                && (methodInsn.name.equals("getDeclaredMethods")
                        || methodInsn.name.equals("getMethods")
                        || methodInsn.name.equals("getDeclaredFields")
                        || methodInsn.name.equals("getFields")
                        || methodInsn.name.equals("getDeclaredConstructors")
                        || methodInsn.name.equals("getConstructors"))) {
            fallback(
                    currentMethod,
                    instructionIndex,
                    StaticReflectionDiagnostics.REFLECTION_UNSUPPORTED_SCAN,
                    "reflection member scan is not statically enumerated",
                    builder);
            return Optional.empty();
        }
        if (methodInsn.owner.equals("java/lang/reflect/Method")
                && methodInsn.name.equals("invoke")
                && methodInsn.desc.equals("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;")) {
            return handleReflectInvoke(currentMethod, instructionIndex, operands, builder);
        }
        if (methodInsn.owner.equals("java/lang/reflect/Constructor")
                && methodInsn.name.equals("newInstance")
                && methodInsn.desc.equals("([Ljava/lang/Object;)Ljava/lang/Object;")) {
            return handleReflectNewInstance(currentMethod, instructionIndex, operands, builder);
        }
        return Optional.empty();
    }

    private Optional<ReflectionValue> handleClassForName(
            ParsedMethod currentMethod,
            int instructionIndex,
            List<ReflectionValue> operands,
            RuntimeMetadataIndex metadataIndex,
            ReflectionPlanBuilder builder,
            boolean initialize) {
        Optional<String> binaryName = operands.isEmpty() ? Optional.empty() : operands.get(0).stringValue();
        if (binaryName.isEmpty()) {
            fallback(
                    currentMethod,
                    instructionIndex,
                    StaticReflectionDiagnostics.DYNAMIC_REFLECTION_STRING,
                    "Class.forName class name is not a constant string",
                    builder);
            return Optional.empty();
        }
        String internalName = binaryNameToInternal(binaryName.orElseThrow());
        if (metadataIndex.findClass(internalName).isEmpty()) {
            fallback(
                    currentMethod,
                    instructionIndex,
                    StaticReflectionDiagnostics.UNRESOLVED_REFLECTION_CLASS,
                    "Class.forName target is not present in runtime metadata: " + internalName,
                    builder);
        } else {
            builder.classTargets.add(new ReflectionClassTarget(
                    internalName,
                    initialize,
                    sourceSite(currentMethod, instructionIndex, "Class.forName")));
        }
        return Optional.of(ReflectionValue.classValue("L" + internalName + ";"));
    }

    private Optional<ReflectionValue> handleGetDeclaredMethod(
            ParsedMethod currentMethod,
            int instructionIndex,
            List<ReflectionValue> operands,
            RuntimeMetadataIndex metadataIndex,
            ReflectionPlanBuilder builder) {
        Optional<String> owner = receiverClassInternalName(operands);
        Optional<String> name = operands.size() > 1 ? operands.get(1).stringValue() : Optional.empty();
        Optional<List<String>> parameters = operands.size() > 2 ? operands.get(2).classArrayDescriptors() : Optional.empty();
        if (owner.isEmpty() || name.isEmpty()) {
            fallback(
                    currentMethod,
                    instructionIndex,
                    StaticReflectionDiagnostics.DYNAMIC_REFLECTION_STRING,
                    "getDeclaredMethod class or method name is not statically known",
                    builder);
            return Optional.empty();
        }
        if (parameters.isEmpty()) {
            fallback(
                    currentMethod,
                    instructionIndex,
                    StaticReflectionDiagnostics.DYNAMIC_REFLECTION_PARAMETERS,
                    "getDeclaredMethod parameter array is not statically known",
                    builder);
            Optional<MethodMetadata> unique = uniqueMethodByName(metadataIndex, owner.orElseThrow(), name.orElseThrow());
            if (unique.isEmpty()) {
                return Optional.empty();
            }
            MethodMetadata method = unique.orElseThrow();
            ReflectionMethodTarget methodTarget = new ReflectionMethodTarget(
                    method.owner(),
                    method.name(),
                    method.descriptor(),
                    ReflectionMethodKind.DECLARED_METHOD,
                    false,
                    sourceSite(currentMethod, instructionIndex, "getDeclaredMethod:uniqueOverApprox"));
            builder.methodTargets.add(methodTarget);
            return Optional.of(ReflectionValue.methodValue(methodTarget));
        }
        Optional<MethodMetadata> target = metadataIndex.findClass(owner.orElseThrow()).stream()
                .flatMap(clazz -> clazz.methods().stream())
                .filter(method -> method.name().equals(name.orElseThrow()))
                .filter(method -> parameterDescriptors(method.descriptor()).equals(parameters.orElseThrow()))
                .findFirst();
        if (target.isEmpty()) {
            fallback(
                    currentMethod,
                    instructionIndex,
                    StaticReflectionDiagnostics.UNRESOLVED_REFLECTION_METHOD,
                    "getDeclaredMethod target was not found: "
                            + owner.orElseThrow() + "#" + name.orElseThrow() + parameters.orElseThrow(),
                    builder);
            return Optional.empty();
        }
        MethodMetadata method = target.orElseThrow();
        ReflectionMethodTarget methodTarget = new ReflectionMethodTarget(
                method.owner(),
                method.name(),
                method.descriptor(),
                ReflectionMethodKind.DECLARED_METHOD,
                false,
                sourceSite(currentMethod, instructionIndex, "getDeclaredMethod"));
        builder.methodTargets.add(methodTarget);
        return Optional.of(ReflectionValue.methodValue(methodTarget));
    }

    private Optional<ReflectionValue> handleGetDeclaredField(
            ParsedMethod currentMethod,
            int instructionIndex,
            List<ReflectionValue> operands,
            RuntimeMetadataIndex metadataIndex,
            ReflectionPlanBuilder builder) {
        Optional<String> owner = receiverClassInternalName(operands);
        Optional<String> name = operands.size() > 1 ? operands.get(1).stringValue() : Optional.empty();
        if (owner.isEmpty() || name.isEmpty()) {
            fallback(
                    currentMethod,
                    instructionIndex,
                    StaticReflectionDiagnostics.DYNAMIC_REFLECTION_STRING,
                    "getDeclaredField class or field name is not statically known",
                    builder);
            return Optional.empty();
        }
        Optional<FieldMetadata> target = metadataIndex.findClass(owner.orElseThrow()).stream()
                .flatMap(clazz -> clazz.fields().stream())
                .filter(field -> field.name().equals(name.orElseThrow()))
                .findFirst();
        if (target.isEmpty()) {
            fallback(
                    currentMethod,
                    instructionIndex,
                    StaticReflectionDiagnostics.UNRESOLVED_REFLECTION_FIELD,
                    "getDeclaredField target was not found: " + owner.orElseThrow() + "#" + name.orElseThrow(),
                    builder);
            return Optional.empty();
        }
        FieldMetadata field = target.orElseThrow();
        ReflectionFieldTarget fieldTarget = new ReflectionFieldTarget(
                field.owner(),
                field.name(),
                field.descriptor(),
                sourceSite(currentMethod, instructionIndex, "getDeclaredField"));
        builder.fieldTargets.add(fieldTarget);
        return Optional.of(ReflectionValue.fieldValue(fieldTarget));
    }

    private Optional<ReflectionValue> handleGetDeclaredConstructor(
            ParsedMethod currentMethod,
            int instructionIndex,
            List<ReflectionValue> operands,
            RuntimeMetadataIndex metadataIndex,
            ReflectionPlanBuilder builder) {
        Optional<String> owner = receiverClassInternalName(operands);
        Optional<List<String>> parameters = operands.size() > 1 ? operands.get(1).classArrayDescriptors() : Optional.empty();
        if (owner.isEmpty()) {
            fallback(
                    currentMethod,
                    instructionIndex,
                    StaticReflectionDiagnostics.DYNAMIC_REFLECTION_STRING,
                    "getDeclaredConstructor receiver class is not statically known",
                    builder);
            return Optional.empty();
        }
        if (parameters.isEmpty()) {
            fallback(
                    currentMethod,
                    instructionIndex,
                    StaticReflectionDiagnostics.DYNAMIC_REFLECTION_PARAMETERS,
                    "getDeclaredConstructor parameter array is not statically known",
                    builder);
            Optional<MethodMetadata> unique = uniqueConstructor(metadataIndex, owner.orElseThrow());
            if (unique.isEmpty()) {
                return Optional.empty();
            }
            MethodMetadata constructor = unique.orElseThrow();
            ReflectionMethodTarget constructorTarget = new ReflectionMethodTarget(
                    constructor.owner(),
                    constructor.name(),
                    constructor.descriptor(),
                    ReflectionMethodKind.DECLARED_CONSTRUCTOR,
                    false,
                    sourceSite(currentMethod, instructionIndex, "getDeclaredConstructor:uniqueOverApprox"));
            builder.methodTargets.add(constructorTarget);
            return Optional.of(ReflectionValue.constructorValue(constructorTarget));
        }
        String descriptor = "(" + String.join("", parameters.orElseThrow()) + ")V";
        Optional<MethodMetadata> target = metadataIndex.findMethod(owner.orElseThrow(), "<init>", descriptor);
        if (target.isEmpty()) {
            fallback(
                    currentMethod,
                    instructionIndex,
                    StaticReflectionDiagnostics.UNRESOLVED_REFLECTION_CONSTRUCTOR,
                    "getDeclaredConstructor target was not found: " + owner.orElseThrow() + descriptor,
                    builder);
            return Optional.empty();
        }
        MethodMetadata constructor = target.orElseThrow();
        ReflectionMethodTarget constructorTarget = new ReflectionMethodTarget(
                constructor.owner(),
                constructor.name(),
                constructor.descriptor(),
                ReflectionMethodKind.DECLARED_CONSTRUCTOR,
                false,
                sourceSite(currentMethod, instructionIndex, "getDeclaredConstructor"));
        builder.methodTargets.add(constructorTarget);
        return Optional.of(ReflectionValue.constructorValue(constructorTarget));
    }

    private Optional<ReflectionValue> handleReflectInvoke(
            ParsedMethod currentMethod,
            int instructionIndex,
            List<ReflectionValue> operands,
            ReflectionPlanBuilder builder) {
        Optional<ReflectionMethodTarget> target = operands.isEmpty() ? Optional.empty() : operands.get(0).methodTarget();
        if (target.isEmpty()) {
            fallback(
                    currentMethod,
                    instructionIndex,
                    StaticReflectionDiagnostics.DYNAMIC_REFLECTIVE_METHOD,
                    "Method.invoke receiver Method is not statically resolved",
                    builder);
            return Optional.empty();
        }
        ReflectionMethodTarget declared = target.orElseThrow();
        builder.methodTargets.add(new ReflectionMethodTarget(
                declared.owner(),
                declared.name(),
                declared.descriptor(),
                ReflectionMethodKind.REFLECTIVE_INVOKE,
                declared.requiresClassInitialization(),
                sourceSite(currentMethod, instructionIndex, "Method.invoke")));
        return Optional.of(ReflectionValue.unknown("reflectInvokeResult"));
    }

    private Optional<ReflectionValue> handleReflectNewInstance(
            ParsedMethod currentMethod,
            int instructionIndex,
            List<ReflectionValue> operands,
            ReflectionPlanBuilder builder) {
        Optional<ReflectionMethodTarget> target = operands.isEmpty() ? Optional.empty() : operands.get(0).methodTarget();
        if (target.isEmpty()) {
            fallback(
                    currentMethod,
                    instructionIndex,
                    StaticReflectionDiagnostics.DYNAMIC_REFLECTIVE_CONSTRUCTOR,
                    "Constructor.newInstance receiver Constructor is not statically resolved",
                    builder);
            return Optional.empty();
        }
        ReflectionMethodTarget constructor = target.orElseThrow();
        builder.methodTargets.add(new ReflectionMethodTarget(
                constructor.owner(),
                constructor.name(),
                constructor.descriptor(),
                ReflectionMethodKind.REFLECTIVE_NEW_INSTANCE,
                true,
                sourceSite(currentMethod, instructionIndex, "Constructor.newInstance")));
        return Optional.of(ReflectionValue.classValue("L" + constructor.owner() + ";"));
    }

    private Optional<MethodMetadata> uniqueMethodByName(
            RuntimeMetadataIndex metadataIndex,
            String owner,
            String name) {
        List<MethodMetadata> candidates = metadataIndex.findClass(owner).stream()
                .flatMap(clazz -> clazz.methods().stream())
                .filter(method -> method.name().equals(name))
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    private Optional<MethodMetadata> uniqueConstructor(
            RuntimeMetadataIndex metadataIndex,
            String owner) {
        List<MethodMetadata> candidates = metadataIndex.findClass(owner).stream()
                .flatMap(clazz -> clazz.methods().stream())
                .filter(method -> method.name().equals("<init>"))
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    private Optional<String> receiverClassInternalName(List<ReflectionValue> operands) {
        if (operands.isEmpty()) {
            return Optional.empty();
        }
        return operands.get(0).classDescriptor().flatMap(this::classInternalName);
    }

    private void handleArrayStore(ArrayDeque<ReflectionValue> stack) {
        ReflectionValue value = popOrUnknown(stack);
        ReflectionValue index = popOrUnknown(stack);
        ReflectionValue array = popOrUnknown(stack);
        if (array.classArrayDescriptors().isPresent() && index.intValue().isPresent() && value.classDescriptor().isPresent()) {
            array.setClassArrayElement(index.intValue().orElseThrow(), value.classDescriptor().orElseThrow());
        }
    }

    private void handleUnknownInstruction(int opcode, ArrayDeque<ReflectionValue> stack) {
        if (opcode >= IRETURN && opcode <= RETURN) {
            stack.clear();
        }
    }

    private ReflectionValue popOrUnknown(ArrayDeque<ReflectionValue> stack) {
        return stack.isEmpty() ? ReflectionValue.unknown("stack-underflow") : stack.pop();
    }

    private ReflectionValue peekOrUnknown(ArrayDeque<ReflectionValue> stack) {
        return stack.isEmpty() ? ReflectionValue.unknown("stack-underflow") : stack.peek();
    }

    private void fallback(
            ParsedMethod method,
            int instructionIndex,
            String reasonCode,
            String reason,
            ReflectionPlanBuilder builder) {
        builder.fallbacks.add(new ReflectionFallbackSite(
                method.owner(),
                method.name(),
                method.descriptor(),
                instructionIndex,
                reasonCode,
                reason));
    }

    private String sourceSite(ParsedMethod method, int instructionIndex, String detail) {
        return method.methodKey() + "@" + instructionIndex + ":" + detail;
    }

    private String descriptorForType(Type type) {
        return switch (type.getSort()) {
            case Type.OBJECT -> "L" + type.getInternalName() + ";";
            default -> type.getDescriptor();
        };
    }

    private Optional<String> classInternalName(String descriptor) {
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return Optional.of(descriptor.substring(1, descriptor.length() - 1));
        }
        if (descriptor.startsWith("[")) {
            return Optional.of(descriptor);
        }
        return Optional.empty();
    }

    private String binaryNameToInternal(String binaryName) {
        if (binaryName.startsWith("[")) {
            return binaryName.replace('.', '/');
        }
        return binaryName.replace('.', '/');
    }

    private List<String> parameterDescriptors(String methodDescriptor) {
        int index = 1;
        ArrayList<String> descriptors = new ArrayList<>();
        while (methodDescriptor.charAt(index) != ')') {
            int start = index;
            while (methodDescriptor.charAt(index) == '[') {
                index++;
            }
            char kind = methodDescriptor.charAt(index);
            if (kind == 'L') {
                index = methodDescriptor.indexOf(';', index) + 1;
            } else {
                index++;
            }
            descriptors.add(methodDescriptor.substring(start, index));
        }
        return List.copyOf(descriptors);
    }

    private String returnDescriptor(String methodDescriptor) {
        return methodDescriptor.substring(methodDescriptor.indexOf(')') + 1);
    }

    private static final class ReflectionPlanBuilder {
        private final ArrayList<ReflectionClassTarget> classTargets = new ArrayList<>();
        private final ArrayList<ReflectionMethodTarget> methodTargets = new ArrayList<>();
        private final ArrayList<ReflectionFieldTarget> fieldTargets = new ArrayList<>();
        private final ArrayList<ReflectionFallbackSite> fallbacks = new ArrayList<>();

        private ReflectionPlan build() {
            return new ReflectionPlan(classTargets, methodTargets, fieldTargets, fallbacks);
        }
    }

    private static final class ReflectionValue {
        private final ReflectionValueKind kind;
        private final String stringValue;
        private final Integer intValue;
        private final String classDescriptor;
        private final String arrayElementType;
        private final List<String> classArrayDescriptors;
        private final ReflectionMethodTarget methodTarget;
        private final ReflectionFieldTarget fieldTarget;
        private final String description;

        private ReflectionValue(
                ReflectionValueKind kind,
                String stringValue,
                Integer intValue,
                String classDescriptor,
                String arrayElementType,
                List<String> classArrayDescriptors,
                ReflectionMethodTarget methodTarget,
                ReflectionFieldTarget fieldTarget,
                String description) {
            this.kind = kind;
            this.stringValue = stringValue;
            this.intValue = intValue;
            this.classDescriptor = classDescriptor;
            this.arrayElementType = arrayElementType;
            this.classArrayDescriptors = classArrayDescriptors;
            this.methodTarget = methodTarget;
            this.fieldTarget = fieldTarget;
            this.description = description;
        }

        static ReflectionValue stringValue(String value) {
            return new ReflectionValue(
                    ReflectionValueKind.STRING,
                    value,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    value);
        }

        static ReflectionValue intValue(int value) {
            return new ReflectionValue(
                    ReflectionValueKind.INT,
                    null,
                    value,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Integer.toString(value));
        }

        static ReflectionValue classValue(String descriptor) {
            return new ReflectionValue(
                    ReflectionValueKind.CLASS,
                    null,
                    null,
                    descriptor,
                    null,
                    null,
                    null,
                    null,
                    descriptor);
        }

        static ReflectionValue arrayValue(String elementType, Integer size) {
            List<String> descriptors = null;
            if (elementType.equals("java/lang/Class") && size != null && size >= 0) {
                descriptors = new ArrayList<>(Collections.nCopies(size, null));
            }
            return new ReflectionValue(
                    ReflectionValueKind.ARRAY,
                    null,
                    null,
                    null,
                    elementType,
                    descriptors,
                    null,
                    null,
                    elementType);
        }

        static ReflectionValue methodValue(ReflectionMethodTarget target) {
            return new ReflectionValue(
                    ReflectionValueKind.METHOD,
                    null,
                    null,
                    null,
                    null,
                    null,
                    target,
                    null,
                    target.methodKey());
        }

        static ReflectionValue constructorValue(ReflectionMethodTarget target) {
            return new ReflectionValue(
                    ReflectionValueKind.CONSTRUCTOR,
                    null,
                    null,
                    null,
                    null,
                    null,
                    target,
                    null,
                    target.methodKey());
        }

        static ReflectionValue fieldValue(ReflectionFieldTarget target) {
            return new ReflectionValue(
                    ReflectionValueKind.FIELD,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    target,
                    target.fieldKey());
        }

        static ReflectionValue nullValue() {
            return new ReflectionValue(
                    ReflectionValueKind.NULL,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "null");
        }

        static ReflectionValue unknown(String description) {
            return new ReflectionValue(
                    ReflectionValueKind.UNKNOWN,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    description);
        }

        Optional<String> stringValue() {
            return kind == ReflectionValueKind.STRING ? Optional.of(stringValue) : Optional.empty();
        }

        Optional<Integer> intValue() {
            return kind == ReflectionValueKind.INT ? Optional.of(intValue) : Optional.empty();
        }

        Optional<String> classDescriptor() {
            return kind == ReflectionValueKind.CLASS ? Optional.of(classDescriptor) : Optional.empty();
        }

        Optional<List<String>> classArrayDescriptors() {
            if (kind != ReflectionValueKind.ARRAY
                    || !arrayElementType.equals("java/lang/Class")
                    || classArrayDescriptors == null
                    || classArrayDescriptors.stream().anyMatch(value -> value == null)) {
                return Optional.empty();
            }
            return Optional.of(List.copyOf(classArrayDescriptors));
        }

        Optional<ReflectionMethodTarget> methodTarget() {
            if (kind == ReflectionValueKind.METHOD || kind == ReflectionValueKind.CONSTRUCTOR) {
                return Optional.of(methodTarget);
            }
            return Optional.empty();
        }

        void setClassArrayElement(int index, String descriptor) {
            if (classArrayDescriptors == null || index < 0 || index >= classArrayDescriptors.size()) {
                return;
            }
            classArrayDescriptors.set(index, descriptor);
        }

        @Override
        public String toString() {
            return kind + ":" + description;
        }
    }

    private enum ReflectionValueKind {
        STRING,
        INT,
        CLASS,
        ARRAY,
        METHOD,
        CONSTRUCTOR,
        FIELD,
        NULL,
        UNKNOWN
    }
}
