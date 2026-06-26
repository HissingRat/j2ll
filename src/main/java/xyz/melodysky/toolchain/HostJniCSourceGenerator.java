package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.packaging.EncodedFallbackBlob;
import xyz.melodysky.packaging.FallbackBlobCodec;
import xyz.melodysky.packaging.FallbackHelperClass;
import xyz.melodysky.packaging.FallbackHelperClassFactory;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.RegisterNativesTableBuilder;
import xyz.melodysky.runtime.ClassIdentityToken;
import xyz.melodysky.runtime.FieldIdentityToken;
import xyz.melodysky.runtime.MethodIdentityToken;
import xyz.melodysky.runtime.jni.JniMethodDescriptor;
import xyz.melodysky.runtime.jni.JniTypeMapper;
import xyz.melodysky.toolchain.ClassArtifactPath;

public final class HostJniCSourceGenerator implements Opcodes {
    private final JniTypeMapper typeMapper = new JniTypeMapper();
    private final ClassArtifactPath artifactPath = new ClassArtifactPath();
    private final FallbackHelperClassFactory fallbackHelperClassFactory = new FallbackHelperClassFactory();
    private final FallbackBlobCodec fallbackBlobCodec = new FallbackBlobCodec();

    public String generate(NativeImplementationPlan implementationPlan) {
        List<Binding> bindings = bindings(implementationPlan);
        NativeRegistrationPlan supportedPlan = implementationPlan.registrationPlan();
        StringBuilder builder = new StringBuilder();
        builder.append("""
                #include <jni.h>
                #include <stdarg.h>
                #include <stdint.h>
                #include <stdlib.h>
                #include <string.h>

                """);
        builder.append(helperSource());
        if (bindings.stream().anyMatch(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)) {
            appendAllocationHelperSource(builder, bindings);
            builder.append(classInitHelperSource());
            builder.append(arithmeticExceptionHelperSource());
            builder.append(exceptionHelperSource());
            builder.append(mathHelperSource());
            builder.append(jdkObjectHelperSource());
            builder.append(monitorHelperSource());
            builder.append(arrayHelperSource());
            builder.append(typeHelperSource());
            builder.append(stringHelperSource());
            appendStringConstantHelperSource(builder, bindings);
            appendLambdaHelperSource(builder, bindings);
            builder.append(varHandleHelperSource());
            appendReflectionHelperSource(builder, bindings);
            appendDispatchHelperSource(builder, bindings);
        }
        appendFieldHelperSource(builder, bindings);
        for (Binding binding : bindings) {
            if (binding.path() == NativeImplementationPath.TEMPLATE_JNI_PATH
                    && binding.decision().method().name().equals("substring")) {
                builder.append(fallbackSubstringClass(binding).extraSource());
            }
        }
        for (Binding binding : bindings) {
            if (binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH) {
                appendLlvmForwardDeclaration(builder, binding);
            }
        }
        if (bindings.stream().anyMatch(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)) {
            builder.append('\n');
        }
        for (Binding binding : bindings) {
            appendFunction(builder, binding);
        }
        builder.append(new RegisterNativesTableBuilder().emit(supportedPlan));
        appendOwnerRegistration(builder, supportedPlan);
        return builder.toString();
    }

    public String generate(NativeRegistrationPlan registrationPlan, List<MethodRewriteDecision> decisions) {
        return generate(new NativeImplementationPlanner().plan(registrationPlan, decisions, Map.of()));
    }

    public NativeRegistrationPlan supportedHostPlan(
            NativeRegistrationPlan registrationPlan,
            List<MethodRewriteDecision> decisions) {
        return new NativeRegistrationPlan(supportedBindings(registrationPlan, decisions).stream()
                .map(Binding::entry)
                .toList());
    }

    private List<Binding> supportedBindings(
            NativeRegistrationPlan registrationPlan,
            List<MethodRewriteDecision> decisions) {
        ArrayList<Binding> bindings = new ArrayList<>();
        for (NativeRegistrationEntry entry : registrationPlan.entries()) {
            Optional<MethodRewriteDecision> maybeDecision = decisionFor(entry, decisions);
            if (maybeDecision.isEmpty()) {
                continue;
            }
            MethodRewriteDecision decision = maybeDecision.orElseThrow();
            if ((decision.strategy() == MethodRewriteStrategy.NOT_APPLICABLE
                            || decision.strategy() == MethodRewriteStrategy.INTERFACE_METHOD_STUB)
                    || !supportsTemplate(decision)) {
                continue;
            }
            bindings.add(new Binding(
                    entry,
                    decision,
                    NativeImplementationPath.TEMPLATE_JNI_PATH,
                    Optional.empty(),
                    false,
                    false,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    Optional.empty(),
                    bindingDescriptor(entry, decision)));
        }
        return bindings.stream().sorted(Comparator.comparing(Binding::entry)).toList();
    }

    private List<Binding> bindings(NativeImplementationPlan implementationPlan) {
        return implementationPlan.implementations().stream()
                .map(implementation -> new Binding(
                        implementation.entry(),
                        implementation.decision(),
                        implementation.path(),
                        implementation.llvmFunctionSymbol(),
                        implementation.passesJniEnv(),
                        implementation.passesOwnerClass(),
                        implementation.fieldKeys(),
                    implementation.directCallTargets(),
                    implementation.allocationKeys(),
                    implementation.typeCheckKeys(),
                    implementation.classObjectKeys(),
                    implementation.runtimeMetadataKeys(),
                    implementation.constructorCallKeys(),
                    implementation.dispatchKeys(),
                        implementation.stringHelperSymbols(),
                        implementation.templateIrMethod(),
                        bindingDescriptor(implementation.entry(), implementation.decision())))
                .sorted(Comparator.comparing(Binding::entry))
                .toList();
    }

    private Optional<MethodRewriteDecision> decisionFor(
            NativeRegistrationEntry entry,
            List<MethodRewriteDecision> decisions) {
        return decisions.stream()
                .filter(decision -> decision.registrationOwner().equals(entry.registrationOwner()))
                .filter(decision -> decision.generatedHelperName().orElse(decision.method().name()).equals(entry.methodName()))
                .filter(decision -> registeredDescriptor(decision).equals(entry.descriptor()))
                .findFirst();
    }

    private JniMethodDescriptor bindingDescriptor(NativeRegistrationEntry entry, MethodRewriteDecision decision) {
        if (decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB
                || decision.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB
                || decision.strategy() == MethodRewriteStrategy.INTERFACE_METHOD_STUB) {
            return typeMapper.methodDescriptor(
                    entry.registrationOwner(),
                    entry.methodName(),
                    entry.descriptor(),
                    true);
        }
        return typeMapper.methodDescriptor(
                decision.method().owner(),
                decision.method().name(),
                decision.method().descriptor(),
                decision.method().accessFlags().isStatic());
    }

    private String registeredDescriptor(MethodRewriteDecision decision) {
        if (decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB) {
            String descriptor = decision.method().descriptor();
            int close = descriptor.indexOf(')');
            return "(L" + decision.method().owner() + ";" + descriptor.substring(1, close) + ")V";
        }
        if (decision.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            return "()V";
        }
        return decision.method().descriptor();
    }

    public boolean supportsTemplate(MethodRewriteDecision decision) {
        String name = decision.method().name();
        String descriptor = decision.method().descriptor();
        if (decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB) {
            return false;
        }
        if (decision.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            return false;
        }
        return switch (name) {
            case "add" -> descriptor.equals("(II)I");
            case "mul" -> descriptor.equals("(II)I");
            case "inc" -> descriptor.equals("(I)I");
            case "addLong" -> descriptor.equals("(JJ)J");
            case "addFloat" -> descriptor.equals("(FF)F");
            case "addDouble" -> descriptor.equals("(DD)D");
            case "truth" -> descriptor.equals("(Z)Z");
            case "mix" -> descriptor.equals("(ZIJFD)D");
            case "setLast" -> descriptor.equals("(I)V") && decision.method().accessFlags().isStatic();
            case "addBase" -> descriptor.equals("(I)I") && !decision.method().accessFlags().isStatic();
            case "bump" -> descriptor.equals("(I)V") && !decision.method().accessFlags().isStatic();
            case "value" -> descriptor.equals("()I") && !decision.method().accessFlags().isStatic();
            case "echo" -> descriptor.equals("(Ljava/lang/String;)Ljava/lang/String;");
            case "length" -> descriptor.equals("(Ljava/lang/String;)I");
            case "label" -> descriptor.equals("()Ljava/lang/String;") && !decision.method().accessFlags().isStatic();
            case "sum" -> descriptor.equals("([I)I");
            case "copyPlusOne" -> descriptor.equals("([I)[I");
            case "failIfNegative" -> descriptor.equals("(I)I");
            case "substring" -> descriptor.equals("(Ljava/lang/String;)Ljava/lang/String;");
            default -> false;
        };
    }

    private void appendLlvmForwardDeclaration(StringBuilder builder, Binding binding) {
        builder.append("extern ")
                .append(internalReturnType(binding.descriptor()))
                .append(' ')
                .append(binding.llvmFunctionSymbol().orElseThrow())
                .append('(')
                .append(internalParameters(binding))
                .append(");\n");
    }

    private void appendFunction(StringBuilder builder, Binding binding) {
        builder.append("static ")
                .append(binding.descriptor().jniReturnType())
                .append(' ')
                .append(binding.entry().nativeSymbol())
                .append('(')
                .append(parameters(binding.descriptor()))
                .append(") {\n");
        appendBody(builder, binding);
        builder.append("}\n\n");
    }

    private String parameters(JniMethodDescriptor descriptor) {
        ArrayList<String> parameters = new ArrayList<>();
        parameters.add("JNIEnv* env");
        parameters.add(descriptor.staticMethod() ? "jclass owner" : "jobject self");
        for (int index = 0; index < descriptor.jniParameterTypes().size(); index++) {
            parameters.add(descriptor.jniParameterTypes().get(index) + " arg" + index);
        }
        return String.join(", ", parameters);
    }

    private String internalParameters(JniMethodDescriptor descriptor) {
        ArrayList<String> parameters = new ArrayList<>();
        for (int index = 0; index < descriptor.javaParameterDescriptors().size(); index++) {
            parameters.add(internalType(descriptor.javaParameterDescriptors().get(index)) + " arg" + index);
        }
        return String.join(", ", parameters);
    }

    private String internalParameters(Binding binding) {
        ArrayList<String> parameters = new ArrayList<>();
        if (binding.passesJniEnv()) {
            parameters.add("JNIEnv* env");
        }
        if (binding.passesOwnerClass()) {
            parameters.add("jclass owner");
        }
        if (!binding.descriptor().staticMethod()) {
            parameters.add("jobject self");
        }
        for (int index = 0; index < binding.descriptor().javaParameterDescriptors().size(); index++) {
            parameters.add(internalType(binding.descriptor().javaParameterDescriptors().get(index)) + " arg" + index);
        }
        return String.join(", ", parameters);
    }

    private String internalArguments(Binding binding) {
        ArrayList<String> arguments = new ArrayList<>();
        if (binding.passesJniEnv()) {
            arguments.add("env");
        }
        if (binding.passesOwnerClass()) {
            arguments.add("owner");
        }
        if (!binding.descriptor().staticMethod()) {
            arguments.add("self");
        }
        for (int index = 0; index < binding.descriptor().javaParameterDescriptors().size(); index++) {
            String parameterDescriptor = binding.descriptor().javaParameterDescriptors().get(index);
            if (parameterDescriptor.equals("Z")) {
                arguments.add("(jint)arg" + index);
            } else {
                arguments.add("arg" + index);
            }
        }
        return String.join(", ", arguments);
    }

    private String internalReturnType(JniMethodDescriptor descriptor) {
        return internalType(descriptor.javaReturnDescriptor());
    }

    private String internalType(String descriptor) {
        if (descriptor.equals("Ljava/lang/String;")) {
            return "jstring";
        }
        if (descriptor.startsWith("L") || descriptor.startsWith("[")) {
            return "jobject";
        }
        return switch (descriptor) {
            case "V" -> "void";
            case "Z", "I" -> "jint";
            case "J" -> "jlong";
            case "F" -> "jfloat";
            case "D" -> "jdouble";
            default -> throw new IllegalArgumentException("unsupported LLVM native scalar descriptor: " + descriptor);
        };
    }

    private void appendBody(StringBuilder builder, Binding binding) {
        if (binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH) {
            appendLlvmWrapperBody(builder, binding);
            return;
        }
        String name = binding.decision().method().name();
        if (binding.decision().strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB) {
            if (binding.templateIrMethod().isPresent()) {
                appendGenericBodyHelper(builder, binding);
            } else {
                appendConstructorBodyHelper(builder, binding);
            }
            return;
        }
        if (binding.decision().strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            if (binding.templateIrMethod().isPresent()) {
                appendGenericBodyHelper(builder, binding);
            } else {
                appendClassInitializerBodyHelper(builder);
            }
            return;
        }
        switch (name) {
            case "add" -> builder.append("    (void)env;\n")
                    .append(binding.descriptor().staticMethod() ? "    (void)owner;\n" : "    (void)self;\n")
                    .append("    return arg0 + arg1;\n");
            case "mul" -> builder.append("    (void)env;\n")
                    .append(binding.descriptor().staticMethod() ? "    (void)owner;\n" : "    (void)self;\n")
                    .append("    return arg0 * arg1;\n");
            case "inc" -> builder.append("    (void)env;\n")
                    .append("    (void)owner;\n")
                    .append("    return arg0 + 1;\n");
            case "addLong" -> builder.append("    (void)env;\n")
                    .append("    (void)owner;\n")
                    .append("    return arg0 + arg1;\n");
            case "addFloat" -> builder.append("    (void)env;\n")
                    .append("    (void)owner;\n")
                    .append("    return arg0 + arg1;\n");
            case "addDouble" -> builder.append("    (void)env;\n")
                    .append("    (void)owner;\n")
                    .append("    return arg0 + arg1;\n");
            case "truth" -> builder.append("    (void)env;\n")
                    .append("    (void)owner;\n")
                    .append("    return arg0 ? JNI_TRUE : JNI_FALSE;\n");
            case "mix" -> builder.append("    (void)env;\n")
                    .append("    (void)owner;\n")
                    .append("    return (arg0 ? 1.0 : 0.0) + (jdouble)arg1 + (jdouble)arg2 + (jdouble)arg3 + arg4;\n");
            case "setLast" -> appendSetStaticIntField(builder, binding, "last", "arg0");
            case "addBase" -> appendGetIntField(builder, binding, "base", "    return value + arg0;\n");
            case "bump" -> appendBumpField(builder, binding, "value");
            case "value" -> appendGetIntField(builder, binding, "value", "    return value;\n");
            case "echo" -> appendEchoString(builder);
            case "length" -> appendStringLength(builder);
            case "label" -> appendGetStringField(builder, binding, "label");
            case "sum" -> appendIntArraySum(builder);
            case "copyPlusOne" -> appendCopyPlusOne(builder);
            case "failIfNegative" -> appendFailIfNegative(builder);
            case "substring" -> appendFallbackSubstring(builder, binding);
            default -> throw new IllegalArgumentException("unsupported host JNI method: " + name);
        }
    }

    private void appendLlvmWrapperBody(StringBuilder builder, Binding binding) {
        if (!binding.passesJniEnv()) {
            builder.append("    (void)env;\n");
        }
        if (binding.descriptor().staticMethod() && !binding.passesOwnerClass()) {
            builder.append("    (void)owner;\n");
        }
        String call = binding.llvmFunctionSymbol().orElseThrow() + "(" + internalArguments(binding) + ")";
        String returnDescriptor = binding.descriptor().javaReturnDescriptor();
        if (returnDescriptor.equals("V")) {
            builder.append("    ").append(call).append(";\n")
                    .append("    return;\n");
        } else if (returnDescriptor.equals("Z")) {
            builder.append("    return ").append(call).append(" != 0 ? JNI_TRUE : JNI_FALSE;\n");
        } else {
            builder.append("    return (")
                    .append(binding.descriptor().jniReturnType())
                    .append(")")
                    .append(call)
                    .append(";\n");
        }
    }

    private void appendSetStaticIntField(
            StringBuilder builder,
            Binding binding,
            String fieldName,
            String valueExpression) {
        builder.append("    jfieldID field = (*env)->GetStaticFieldID(env, owner, \"")
                .append(fieldName)
                .append("\", \"I\");\n")
                .append("    if (field == NULL) {\n")
                .append("        return;\n")
                .append("    }\n")
                .append("    (*env)->SetStaticIntField(env, owner, field, ")
                .append(valueExpression)
                .append(");\n");
    }

    private void appendGetIntField(
            StringBuilder builder,
            Binding binding,
            String fieldName,
            String returnStatement) {
        builder.append("    jclass cls = (*env)->GetObjectClass(env, self);\n")
                .append("    if (cls == NULL) {\n")
                .append("        return 0;\n")
                .append("    }\n")
                .append("    jfieldID field = (*env)->GetFieldID(env, cls, \"")
                .append(fieldName)
                .append("\", \"I\");\n")
                .append("    (*env)->DeleteLocalRef(env, cls);\n")
                .append("    if (field == NULL) {\n")
                .append("        return 0;\n")
                .append("    }\n")
                .append("    jint value = (*env)->GetIntField(env, self, field);\n")
                .append(returnStatement);
    }

    private void appendBumpField(StringBuilder builder, Binding binding, String fieldName) {
        builder.append("    jclass cls = (*env)->GetObjectClass(env, self);\n")
                .append("    if (cls == NULL) {\n")
                .append("        return;\n")
                .append("    }\n")
                .append("    jfieldID field = (*env)->GetFieldID(env, cls, \"")
                .append(fieldName)
                .append("\", \"I\");\n")
                .append("    (*env)->DeleteLocalRef(env, cls);\n")
                .append("    if (field == NULL) {\n")
                .append("        return;\n")
                .append("    }\n")
                .append("    jint value = (*env)->GetIntField(env, self, field);\n")
                .append("    (*env)->SetIntField(env, self, field, value + arg0);\n");
    }

    private void appendEchoString(StringBuilder builder) {
        builder.append("    (void)owner;\n")
                .append("    if (arg0 == NULL) {\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    const char* chars = (*env)->GetStringUTFChars(env, arg0, NULL);\n")
                .append("    if (chars == NULL) {\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    jstring copy = (*env)->NewStringUTF(env, chars);\n")
                .append("    (*env)->ReleaseStringUTFChars(env, arg0, chars);\n")
                .append("    return copy;\n");
    }

    private void appendStringLength(StringBuilder builder) {
        builder.append("    (void)owner;\n")
                .append("    if (arg0 == NULL) {\n")
                .append("        j2ll_throw_new(env, \"java/lang/NullPointerException\", \"string is null\");\n")
                .append("        return 0;\n")
                .append("    }\n")
                .append("    return (*env)->GetStringLength(env, arg0);\n");
    }

    private void appendGetStringField(StringBuilder builder, Binding binding, String fieldName) {
        builder.append("    jclass cls = (*env)->GetObjectClass(env, self);\n")
                .append("    if (cls == NULL) {\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    jfieldID field = (*env)->GetFieldID(env, cls, \"")
                .append(fieldName)
                .append("\", \"Ljava/lang/String;\");\n")
                .append("    (*env)->DeleteLocalRef(env, cls);\n")
                .append("    if (field == NULL) {\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    return (jstring)(*env)->GetObjectField(env, self, field);\n");
    }

    private void appendIntArraySum(StringBuilder builder) {
        builder.append("    (void)owner;\n")
                .append("    if (arg0 == NULL) {\n")
                .append("        j2ll_throw_new(env, \"java/lang/NullPointerException\", \"array is null\");\n")
                .append("        return 0;\n")
                .append("    }\n")
                .append("    jsize length = (*env)->GetArrayLength(env, arg0);\n")
                .append("    jint* elements = (*env)->GetIntArrayElements(env, arg0, NULL);\n")
                .append("    if (elements == NULL) {\n")
                .append("        return 0;\n")
                .append("    }\n")
                .append("    jint sum = 0;\n")
                .append("    for (jsize index = 0; index < length; index++) {\n")
                .append("        sum += elements[index];\n")
                .append("    }\n")
                .append("    (*env)->ReleaseIntArrayElements(env, arg0, elements, JNI_ABORT);\n")
                .append("    return sum;\n");
    }

    private void appendCopyPlusOne(StringBuilder builder) {
        builder.append("    (void)owner;\n")
                .append("    if (arg0 == NULL) {\n")
                .append("        j2ll_throw_new(env, \"java/lang/NullPointerException\", \"array is null\");\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    jsize length = (*env)->GetArrayLength(env, arg0);\n")
                .append("    jintArray output = (*env)->NewIntArray(env, length);\n")
                .append("    if (output == NULL) {\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    if (length == 0) {\n")
                .append("        return output;\n")
                .append("    }\n")
                .append("    jint* elements = (*env)->GetIntArrayElements(env, arg0, NULL);\n")
                .append("    if (elements == NULL) {\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    jint* copy = (jint*)malloc(sizeof(jint) * (size_t)length);\n")
                .append("    if (copy == NULL) {\n")
                .append("        (*env)->ReleaseIntArrayElements(env, arg0, elements, JNI_ABORT);\n")
                .append("        j2ll_throw_new(env, \"java/lang/OutOfMemoryError\", \"native temporary array allocation failed\");\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    for (jsize index = 0; index < length; index++) {\n")
                .append("        copy[index] = elements[index] + 1;\n")
                .append("    }\n")
                .append("    (*env)->ReleaseIntArrayElements(env, arg0, elements, JNI_ABORT);\n")
                .append("    (*env)->SetIntArrayRegion(env, output, 0, length, copy);\n")
                .append("    free(copy);\n")
                .append("    return output;\n");
    }

    private void appendFailIfNegative(StringBuilder builder) {
        builder.append("    (void)owner;\n")
                .append("    if (arg0 < 0) {\n")
                .append("        j2ll_throw_new(env, \"java/lang/IllegalArgumentException\", \"negative\");\n")
                .append("        return 0;\n")
                .append("    }\n")
                .append("    return arg0;\n");
    }

    private void appendGenericBodyHelper(StringBuilder builder, Binding binding) {
        IrMethod method = binding.templateIrMethod().orElseThrow();
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < method.parameters().size(); index++) {
            values.put(method.parameters().get(index).name(), "arg" + index);
        }
        List<IrBlock> blocks = method.blocks().stream()
                .filter(block -> !block.name().equals("$class_init_failed"))
                .toList();
        boolean needsLabels = blocks.size() > 1
                || blocks.stream().anyMatch(block -> block.terminator().kind() != IrTerminatorKind.RETURN);
        builder.append("    /* generic JVM-hosted body helper lowered from SSA IR */\n");
        for (var block : blocks) {
            if (needsLabels) {
                builder.append(genericBlockLabel(block.name())).append(": ;\n");
            }
            for (IrInstruction instruction : block.instructions()) {
                appendGenericInstruction(builder, binding, values, instruction);
            }
            appendGenericTerminator(builder, values, block.terminator());
        }
        if (needsLabels) {
            builder.append("    return;\n");
        }
    }

    private void appendGenericTerminator(
            StringBuilder builder,
            Map<String, String> values,
            IrTerminator terminator) {
        switch (terminator.kind()) {
            case RETURN -> builder.append("    return;\n");
            case GOTO -> builder.append("    goto ")
                    .append(genericBlockLabel(terminator.target().orElseThrow()))
                    .append(";\n");
            case BRANCH -> builder.append("    if (")
                    .append(genericValue(values, terminator.condition().orElseThrow()))
                    .append(" != 0) {\n")
                    .append("        goto ")
                    .append(genericBlockLabel(terminator.trueTarget().orElseThrow()))
                    .append(";\n")
                    .append("    }\n")
                    .append("    goto ")
                    .append(genericBlockLabel(terminator.falseTarget().orElseThrow()))
                    .append(";\n");
            default -> throw new IllegalArgumentException(
                    "unsupported generic body helper terminator: " + terminator.kind());
        }
    }

    private void appendGenericInstruction(
            StringBuilder builder,
            Binding binding,
            Map<String, String> values,
            IrInstruction instruction) {
        switch (instruction.opcode()) {
            case CONST_INT -> declareGenericLocal(
                    builder,
                    values,
                    instruction.result().orElseThrow(),
                    "jint",
                    instruction.intLiteral().orElseThrow().toString());
            case CONST_LONG -> declareGenericLocal(
                    builder,
                    values,
                    instruction.result().orElseThrow(),
                    "jlong",
                    instruction.longLiteral().orElseThrow() + "LL");
            case CONST_NULL -> declareGenericLocal(
                    builder,
                    values,
                    instruction.result().orElseThrow(),
                    "jobject",
                    "NULL");
            case CONST_STRING -> appendGenericConstString(builder, values, instruction);
            case ADD_I32 -> declareGenericLocal(
                    builder,
                    values,
                    instruction.result().orElseThrow(),
                    "jint",
                    genericValue(values, instruction.operands().get(0))
                            + " + "
                            + genericValue(values, instruction.operands().get(1)));
            case SUB_I32 -> declareGenericLocal(
                    builder,
                    values,
                    instruction.result().orElseThrow(),
                    "jint",
                    genericValue(values, instruction.operands().get(0))
                            + " - "
                            + genericValue(values, instruction.operands().get(1)));
            case MUL_I32 -> declareGenericLocal(
                    builder,
                    values,
                    instruction.result().orElseThrow(),
                    "jint",
                    genericValue(values, instruction.operands().get(0))
                            + " * "
                            + genericValue(values, instruction.operands().get(1)));
            case ADD_I64 -> declareGenericLocal(
                    builder,
                    values,
                    instruction.result().orElseThrow(),
                    "jlong",
                    genericValue(values, instruction.operands().get(0))
                            + " + "
                            + genericValue(values, instruction.operands().get(1)));
            case SUB_I64 -> declareGenericLocal(
                    builder,
                    values,
                    instruction.result().orElseThrow(),
                    "jlong",
                    genericValue(values, instruction.operands().get(0))
                            + " - "
                            + genericValue(values, instruction.operands().get(1)));
            case MUL_I64 -> declareGenericLocal(
                    builder,
                    values,
                    instruction.result().orElseThrow(),
                    "jlong",
                    genericValue(values, instruction.operands().get(0))
                            + " * "
                            + genericValue(values, instruction.operands().get(1)));
            case CMP_EQ_I32, CMP_NE_I32, CMP_LT_I32, CMP_LE_I32, CMP_GT_I32, CMP_GE_I32,
                    CMP_EQ_REF, CMP_NE_REF -> appendGenericCompare(builder, values, instruction);
            case NEW_ARRAY -> appendGenericNewArray(builder, values, instruction);
            case PUT_FIELD -> appendGenericPutField(builder, binding, values, instruction);
            case PUT_STATIC -> appendGenericPutStatic(builder, binding, values, instruction);
            case CALL_SPECIAL, CLASS_OBJECT, CLASS_INIT_BEGIN, CLASS_INIT_END, CLASS_INIT_HAPPENS_BEFORE,
                    FINAL_FIELD_PUBLICATION -> {
                builder.append("    /* ").append(instruction.opcode()).append(" is preserved by the Java stub/JVM helper boundary. */\n");
            }
            default -> throw new IllegalArgumentException(
                    "unsupported generic body helper instruction: " + instruction.opcode());
        }
    }

    private void appendGenericCompare(
            StringBuilder builder,
            Map<String, String> values,
            IrInstruction instruction) {
        declareGenericLocal(
                builder,
                values,
                instruction.result().orElseThrow(),
                "jint",
                "("
                        + genericValue(values, instruction.operands().get(0))
                        + " "
                        + genericCompareOperator(instruction.opcode())
                        + " "
                        + genericValue(values, instruction.operands().get(1))
                        + ") ? 1 : 0");
    }

    private String genericCompareOperator(IrOpcode opcode) {
        return switch (opcode) {
            case CMP_EQ_I32, CMP_EQ_REF -> "==";
            case CMP_NE_I32, CMP_NE_REF -> "!=";
            case CMP_LT_I32 -> "<";
            case CMP_LE_I32 -> "<=";
            case CMP_GT_I32 -> ">";
            case CMP_GE_I32 -> ">=";
            default -> throw new IllegalArgumentException("not a generic compare opcode: " + opcode);
        };
    }

    private void appendGenericConstString(
            StringBuilder builder,
            Map<String, String> values,
            IrInstruction instruction) {
        IrValue result = instruction.result().orElseThrow();
        String local = genericLocalName(result);
        String symbol = instruction.symbol().orElseThrow();
        String value = symbol.startsWith("string:") ? symbol.substring("string:".length()) : symbol;
        values.put(result.name(), local);
        builder.append("    jstring ")
                .append(local)
                .append(" = (*env)->NewStringUTF(env, \"")
                .append(escapeCString(value))
                .append("\");\n")
                .append("    if (")
                .append(local)
                .append(" == NULL) {\n")
                .append("        return;\n")
                .append("    }\n");
    }

    private void appendGenericNewArray(
            StringBuilder builder,
            Map<String, String> values,
            IrInstruction instruction) {
        if (!instruction.symbol().orElse("").equals("primitiveArray:int")) {
            throw new IllegalArgumentException("unsupported generic body array allocation: " + instruction.symbol());
        }
        IrValue result = instruction.result().orElseThrow();
        String local = genericLocalName(result);
        values.put(result.name(), local);
        builder.append("    jintArray ")
                .append(local)
                .append(" = (*env)->NewIntArray(env, (jsize)")
                .append(genericValue(values, instruction.operands().get(0)))
                .append(");\n")
                .append("    if (")
                .append(local)
                .append(" == NULL) {\n")
                .append("        return;\n")
                .append("    }\n");
    }

    private void appendGenericPutField(
            StringBuilder builder,
            Binding binding,
            Map<String, String> values,
            IrInstruction instruction) {
        FieldParts field = parseFieldKey(instruction.symbol().orElseThrow());
        String receiver = genericValue(values, instruction.operands().get(0));
        String value = genericValue(values, instruction.operands().get(1));
        String prefix = "j2ll_field_" + safeSymbol(field.owner() + "_" + field.name() + "_" + field.descriptor())
                + "_" + Math.abs(instruction.hashCode());
        builder.append("    if (")
                .append(receiver)
                .append(" == NULL) {\n")
                .append("        j2ll_throw_new(env, \"java/lang/NullPointerException\", \"field receiver is null\");\n")
                .append("        return;\n")
                .append("    }\n");
        appendFieldClassLookup(builder, binding, field.owner(), prefix);
        builder.append("    jfieldID ")
                .append(prefix)
                .append("_id = (*env)->GetFieldID(env, ")
                .append(prefix)
                .append("_class, \"")
                .append(escapeCString(field.name()))
                .append("\", \"")
                .append(escapeCString(field.descriptor()))
                .append("\");\n");
        appendFieldClassCleanup(builder, binding, field.owner(), prefix);
        builder.append("    if (")
                .append(prefix)
                .append("_id == NULL) {\n")
                .append("        return;\n")
                .append("    }\n");
        appendSetFieldCall(builder, field.descriptor(), receiver, prefix + "_id", value);
    }

    private void appendGenericPutStatic(
            StringBuilder builder,
            Binding binding,
            Map<String, String> values,
            IrInstruction instruction) {
        FieldParts field = parseFieldKey(instruction.symbol().orElseThrow());
        String value = genericValue(values, instruction.operands().get(0));
        String prefix = "j2ll_static_" + safeSymbol(field.owner() + "_" + field.name() + "_" + field.descriptor())
                + "_" + Math.abs(instruction.hashCode());
        appendFieldClassLookup(builder, binding, field.owner(), prefix);
        builder.append("    jfieldID ")
                .append(prefix)
                .append("_id = (*env)->GetStaticFieldID(env, ")
                .append(prefix)
                .append("_class, \"")
                .append(escapeCString(field.name()))
                .append("\", \"")
                .append(escapeCString(field.descriptor()))
                .append("\");\n");
        builder.append("    if (")
                .append(prefix)
                .append("_id == NULL) {\n")
                .append(field.owner().equals(binding.decision().method().owner())
                        ? ""
                        : "        (*env)->DeleteLocalRef(env, " + prefix + "_class);\n")
                .append("        return;\n")
                .append("    }\n");
        appendSetStaticFieldCall(builder, field.descriptor(), prefix + "_class", prefix + "_id", value);
        appendFieldClassCleanup(builder, binding, field.owner(), prefix);
    }

    private void appendFieldClassLookup(
            StringBuilder builder,
            Binding binding,
            String fieldOwner,
            String prefix) {
        if (fieldOwner.equals(binding.decision().method().owner())) {
            builder.append("    jclass ")
                    .append(prefix)
                    .append("_class = owner;\n");
            return;
        }
        builder.append("    jclass ")
                .append(prefix)
                .append("_class = (*env)->FindClass(env, \"")
                .append(escapeCString(fieldOwner))
                .append("\");\n")
                .append("    if (")
                .append(prefix)
                .append("_class == NULL) {\n")
                .append("        return;\n")
                .append("    }\n");
    }

    private void appendFieldClassCleanup(
            StringBuilder builder,
            Binding binding,
            String fieldOwner,
            String prefix) {
        if (!fieldOwner.equals(binding.decision().method().owner())) {
            builder.append("    (*env)->DeleteLocalRef(env, ")
                    .append(prefix)
                    .append("_class);\n");
        }
    }

    private void appendSetFieldCall(
            StringBuilder builder,
            String descriptor,
            String receiver,
            String fieldId,
            String value) {
        switch (descriptor) {
            case "Z", "B", "C", "S", "I" -> builder.append("    (*env)->SetIntField(env, ")
                    .append(receiver)
                    .append(", ")
                    .append(fieldId)
                    .append(", ")
                    .append(value)
                    .append(");\n");
            case "J" -> builder.append("    (*env)->SetLongField(env, ")
                    .append(receiver)
                    .append(", ")
                    .append(fieldId)
                    .append(", ")
                    .append(value)
                    .append(");\n");
            default -> {
                if (!isReferenceDescriptor(descriptor)) {
                    throw new IllegalArgumentException("unsupported generic field descriptor: " + descriptor);
                }
                builder.append("    (*env)->SetObjectField(env, ")
                        .append(receiver)
                        .append(", ")
                        .append(fieldId)
                        .append(", (jobject)")
                        .append(value)
                        .append(");\n");
            }
        }
    }

    private void appendSetStaticFieldCall(
            StringBuilder builder,
            String descriptor,
            String ownerClass,
            String fieldId,
            String value) {
        switch (descriptor) {
            case "Z", "B", "C", "S", "I" -> builder.append("    (*env)->SetStaticIntField(env, ")
                    .append(ownerClass)
                    .append(", ")
                    .append(fieldId)
                    .append(", ")
                    .append(value)
                    .append(");\n");
            case "J" -> builder.append("    (*env)->SetStaticLongField(env, ")
                    .append(ownerClass)
                    .append(", ")
                    .append(fieldId)
                    .append(", ")
                    .append(value)
                    .append(");\n");
            default -> {
                if (!isReferenceDescriptor(descriptor)) {
                    throw new IllegalArgumentException("unsupported generic static field descriptor: " + descriptor);
                }
                builder.append("    (*env)->SetStaticObjectField(env, ")
                        .append(ownerClass)
                        .append(", ")
                        .append(fieldId)
                        .append(", (jobject)")
                        .append(value)
                        .append(");\n");
            }
        }
    }

    private void declareGenericLocal(
            StringBuilder builder,
            Map<String, String> values,
            IrValue result,
            String cType,
            String expression) {
        String local = genericLocalName(result);
        values.put(result.name(), local);
        builder.append("    ")
                .append(cType)
                .append(' ')
                .append(local)
                .append(" = ")
                .append(expression)
                .append(";\n");
    }

    private String genericValue(Map<String, String> values, IrValue value) {
        String expression = values.get(value.name());
        if (expression == null) {
            throw new IllegalArgumentException("generic body references unknown value: " + value.name());
        }
        return expression;
    }

    private String genericLocalName(IrValue value) {
        return "j2ll_v_" + safeSymbol(value.name());
    }

    private String genericBlockLabel(String blockName) {
        return "j2ll_block_" + safeSymbol(blockName);
    }

    private boolean isReferenceDescriptor(String descriptor) {
        return descriptor.startsWith("L") || descriptor.startsWith("[");
    }

    private void appendConstructorBodyHelper(StringBuilder builder, Binding binding) {
        builder.append("    if (arg0 == NULL) {\n")
                .append("        j2ll_throw_new(env, \"java/lang/NullPointerException\", \"constructor receiver is null\");\n")
                .append("        return;\n")
                .append("    }\n")
                .append("    jfieldID x_field = (*env)->GetFieldID(env, owner, \"x\", \"I\");\n")
                .append("    if (x_field == NULL) {\n")
                .append("        return;\n")
                .append("    }\n")
                .append("    jfieldID y_field = (*env)->GetFieldID(env, owner, \"y\", \"I\");\n")
                .append("    if (y_field == NULL) {\n")
                .append("        return;\n")
                .append("    }\n")
                .append("    (*env)->SetIntField(env, arg0, x_field, arg1);\n")
                .append("    (*env)->SetIntField(env, arg0, y_field, arg2);\n");
    }

    private void appendClassInitializerBodyHelper(StringBuilder builder) {
        builder.append("    jfieldID value_field = (*env)->GetStaticFieldID(env, owner, \"value\", \"I\");\n")
                .append("    if (value_field != NULL) {\n")
                .append("        (*env)->SetStaticIntField(env, owner, value_field, 17);\n")
                .append("    }\n")
                .append("    if ((*env)->ExceptionCheck(env)) {\n")
                .append("        return;\n")
                .append("    }\n")
                .append("    jfieldID label_field = (*env)->GetStaticFieldID(env, owner, \"label\", \"Ljava/lang/String;\");\n")
                .append("    if (label_field != NULL) {\n")
                .append("        jstring label = (*env)->NewStringUTF(env, \"ready\");\n")
                .append("        if (label != NULL) {\n")
                .append("            (*env)->SetStaticObjectField(env, owner, label_field, label);\n")
                .append("            (*env)->DeleteLocalRef(env, label);\n")
                .append("        }\n")
                .append("    }\n")
                .append("    if ((*env)->ExceptionCheck(env)) {\n")
                .append("        (*env)->ExceptionClear(env);\n")
                .append("    }\n");
    }

    private void appendFallbackSubstring(StringBuilder builder, Binding binding) {
        builder.append("    (void)owner;\n")
                .append("    jclass helper = j2ll_define_fallback_")
                .append(safeSymbol(binding.entry().nativeSymbol()))
                .append("(env, owner);\n")
                .append("    if (helper == NULL) {\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    jmethodID method = (*env)->GetStaticMethodID(env, helper, \"substring\", \"(Ljava/lang/String;)Ljava/lang/String;\");\n")
                .append("    if (method == NULL) {\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    return (jstring)(*env)->CallStaticObjectMethod(env, helper, method, arg0);\n");
    }

    private String helperSource() {
        return """
                static void j2ll_throw_new(JNIEnv* env, const char* class_name, const char* message) {
                    jclass exception_class = (*env)->FindClass(env, class_name);
                    if (exception_class == NULL) {
                        return;
                    }
                    (*env)->ThrowNew(env, exception_class, message);
                    (*env)->DeleteLocalRef(env, exception_class);
                }

                static int j2ll_verify_sha256_hex(JNIEnv* env, const unsigned char* bytes, size_t length, const char* expected_hex) {
                    jclass digest_class = (*env)->FindClass(env, "java/security/MessageDigest");
                    if (digest_class == NULL) {
                        return 0;
                    }
                    jmethodID get_instance = (*env)->GetStaticMethodID(
                            env,
                            digest_class,
                            "getInstance",
                            "(Ljava/lang/String;)Ljava/security/MessageDigest;");
                    jmethodID digest_method = (*env)->GetMethodID(env, digest_class, "digest", "([B)[B");
                    if (get_instance == NULL || digest_method == NULL) {
                        (*env)->DeleteLocalRef(env, digest_class);
                        return 0;
                    }
                    jstring algorithm = (*env)->NewStringUTF(env, "SHA-256");
                    if (algorithm == NULL) {
                        (*env)->DeleteLocalRef(env, digest_class);
                        return 0;
                    }
                    jobject digest = (*env)->CallStaticObjectMethod(env, digest_class, get_instance, algorithm);
                    (*env)->DeleteLocalRef(env, algorithm);
                    (*env)->DeleteLocalRef(env, digest_class);
                    if (digest == NULL) {
                        return 0;
                    }
                    if (length > 2147483647u) {
                        (*env)->DeleteLocalRef(env, digest);
                        j2ll_throw_new(env, "java/lang/SecurityException", "fallback blob too large to hash");
                        return 0;
                    }
                    jbyteArray input = (*env)->NewByteArray(env, (jsize)length);
                    if (input == NULL) {
                        (*env)->DeleteLocalRef(env, digest);
                        return 0;
                    }
                    if (length > 0) {
                        (*env)->SetByteArrayRegion(env, input, 0, (jsize)length, (const jbyte*)bytes);
                        if ((*env)->ExceptionCheck(env)) {
                            (*env)->DeleteLocalRef(env, input);
                            (*env)->DeleteLocalRef(env, digest);
                            return 0;
                        }
                    }
                    jbyteArray hash = (jbyteArray)(*env)->CallObjectMethod(env, digest, digest_method, input);
                    (*env)->DeleteLocalRef(env, input);
                    (*env)->DeleteLocalRef(env, digest);
                    if (hash == NULL) {
                        return 0;
                    }
                    jsize hash_length = (*env)->GetArrayLength(env, hash);
                    if (hash_length != 32) {
                        (*env)->DeleteLocalRef(env, hash);
                        j2ll_throw_new(env, "java/lang/SecurityException", "fallback SHA-256 digest length mismatch");
                        return 0;
                    }
                    jbyte hash_bytes[32];
                    (*env)->GetByteArrayRegion(env, hash, 0, 32, hash_bytes);
                    (*env)->DeleteLocalRef(env, hash);
                    if ((*env)->ExceptionCheck(env)) {
                        return 0;
                    }
                    static const char hex[] = "0123456789abcdef";
                    char actual[65];
                    for (int index = 0; index < 32; index++) {
                        unsigned char value = (unsigned char)hash_bytes[index];
                        actual[index * 2] = hex[value >> 4];
                        actual[index * 2 + 1] = hex[value & 0x0f];
                    }
                    actual[64] = '\\0';
                    return strcmp(actual, expected_hex) == 0;
                }

                static jobject j2ll_owner_class_loader(JNIEnv* env, jclass owner) {
                    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
                    if (class_class == NULL) {
                        return NULL;
                    }
                    jmethodID get_class_loader = (*env)->GetMethodID(env, class_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
                    (*env)->DeleteLocalRef(env, class_class);
                    if (get_class_loader == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, owner, get_class_loader);
                }

                static char* j2ll_dotted_class_name(const char* internal_name) {
                    size_t length = strlen(internal_name);
                    char* dotted = (char*)malloc(length + 1);
                    if (dotted == NULL) {
                        return NULL;
                    }
                    for (size_t index = 0; index < length; index++) {
                        dotted[index] = internal_name[index] == '/' ? '.' : internal_name[index];
                    }
                    dotted[length] = '\\0';
                    return dotted;
                }

                static jobject j2ll_context_class_loader(JNIEnv* env) {
                    jclass thread_class = (*env)->FindClass(env, "java/lang/Thread");
                    if (thread_class == NULL) {
                        return NULL;
                    }
                    jmethodID current_thread = (*env)->GetStaticMethodID(env, thread_class, "currentThread", "()Ljava/lang/Thread;");
                    jmethodID get_context_class_loader = (*env)->GetMethodID(env, thread_class, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
                    if (current_thread == NULL || get_context_class_loader == NULL) {
                        (*env)->DeleteLocalRef(env, thread_class);
                        return NULL;
                    }
                    jobject thread = (*env)->CallStaticObjectMethod(env, thread_class, current_thread);
                    (*env)->DeleteLocalRef(env, thread_class);
                    if (thread == NULL) {
                        return NULL;
                    }
                    jobject loader = (*env)->CallObjectMethod(env, thread, get_context_class_loader);
                    (*env)->DeleteLocalRef(env, thread);
                    return loader;
                }

                static jclass j2ll_class_for_registration(JNIEnv* env, const char* internal_name) {
                    char* dotted = j2ll_dotted_class_name(internal_name);
                    if (dotted == NULL) {
                        j2ll_throw_new(env, "java/lang/OutOfMemoryError", "failed to allocate class name");
                        return NULL;
                    }
                    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
                    if (class_class == NULL) {
                        free(dotted);
                        return NULL;
                    }
                    jmethodID for_name = (*env)->GetStaticMethodID(
                            env,
                            class_class,
                            "forName",
                            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
                    if (for_name == NULL) {
                        (*env)->DeleteLocalRef(env, class_class);
                        free(dotted);
                        return NULL;
                    }
                    jstring name = (*env)->NewStringUTF(env, dotted);
                    free(dotted);
                    if (name == NULL) {
                        (*env)->DeleteLocalRef(env, class_class);
                        return NULL;
                    }
                    jobject loader = j2ll_context_class_loader(env);
                    if ((*env)->ExceptionCheck(env)) {
                        (*env)->DeleteLocalRef(env, class_class);
                        (*env)->DeleteLocalRef(env, name);
                        if (loader != NULL) {
                            (*env)->DeleteLocalRef(env, loader);
                        }
                        return NULL;
                    }
                    jclass owner = (jclass)(*env)->CallStaticObjectMethod(env, class_class, for_name, name, JNI_FALSE, loader);
                    (*env)->DeleteLocalRef(env, class_class);
                    (*env)->DeleteLocalRef(env, name);
                    if (loader != NULL) {
                        (*env)->DeleteLocalRef(env, loader);
                    }
                    return owner;
                }

                """;
    }

    private String classInitHelperSource() {
        return """
                jclass j2ll_rt_class_object(JNIEnv* env, int64_t class_token) {
                    const char* class_name = j2ll_find_class_object_name(class_token);
                    if (class_name == NULL) {
                        j2ll_throw_new(env, "java/lang/NoClassDefFoundError", "unknown j2ll class-init token");
                        return NULL;
                    }
                    return (*env)->FindClass(env, class_name);
                }

                void j2ll_rt_class_init_guard(JNIEnv* env, jclass class_object) {
                    (void)env;
                    (void)class_object;
                }

                void j2ll_rt_class_init_begin(JNIEnv* env, jclass class_object) {
                    (void)env;
                    (void)class_object;
                }

                void j2ll_rt_class_init_end(JNIEnv* env, jclass class_object) {
                    (void)env;
                    (void)class_object;
                }

                void j2ll_rt_class_init_failed(JNIEnv* env, jclass class_object, jthrowable throwable) {
                    (void)env;
                    (void)class_object;
                    (void)throwable;
                }

                """;
    }

    private String exceptionHelperSource() {
        return """
                void j2ll_rt_throw(JNIEnv* env, jobject throwable) {
                    if (throwable == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "throwable is null");
                        return;
                    }
                    (*env)->Throw(env, (jthrowable)throwable);
                }

                """;
    }

    private String arithmeticExceptionHelperSource() {
        return """
                int32_t j2ll_rt_div_i32(JNIEnv* env, int32_t left, int32_t right) {
                    if (right == 0) {
                        j2ll_throw_new(env, "java/lang/ArithmeticException", "/ by zero");
                        return 0;
                    }
                    if (left == INT32_MIN && right == -1) {
                        return left;
                    }
                    return left / right;
                }

                int32_t j2ll_rt_rem_i32(JNIEnv* env, int32_t left, int32_t right) {
                    if (right == 0) {
                        j2ll_throw_new(env, "java/lang/ArithmeticException", "/ by zero");
                        return 0;
                    }
                    if (left == INT32_MIN && right == -1) {
                        return 0;
                    }
                    return left % right;
                }

                int64_t j2ll_rt_div_i64(JNIEnv* env, int64_t left, int64_t right) {
                    if (right == 0) {
                        j2ll_throw_new(env, "java/lang/ArithmeticException", "/ by zero");
                        return 0;
                    }
                    if (left == INT64_MIN && right == -1) {
                        return left;
                    }
                    return left / right;
                }

                int64_t j2ll_rt_rem_i64(JNIEnv* env, int64_t left, int64_t right) {
                    if (right == 0) {
                        j2ll_throw_new(env, "java/lang/ArithmeticException", "/ by zero");
                        return 0;
                    }
                    if (left == INT64_MIN && right == -1) {
                        return 0;
                    }
                    return left % right;
                }

                """;
    }

    private String mathHelperSource() {
        return """
                int32_t j2ll_rt_math_abs_i32(int32_t value) {
                    if (value == INT32_MIN) {
                        return value;
                    }
                    return value < 0 ? -value : value;
                }

                int64_t j2ll_rt_math_abs_i64(int64_t value) {
                    if (value == INT64_MIN) {
                        return value;
                    }
                    return value < 0 ? -value : value;
                }

                float j2ll_rt_math_abs_f32(float value) {
                    return value < 0.0f ? -value : value;
                }

                double j2ll_rt_math_abs_f64(double value) {
                    return value < 0.0 ? -value : value;
                }

                int32_t j2ll_rt_math_min_i32(int32_t left, int32_t right) {
                    return left <= right ? left : right;
                }

                int64_t j2ll_rt_math_min_i64(int64_t left, int64_t right) {
                    return left <= right ? left : right;
                }

                float j2ll_rt_math_min_f32(float left, float right) {
                    return left <= right ? left : right;
                }

                double j2ll_rt_math_min_f64(double left, double right) {
                    return left <= right ? left : right;
                }

                int32_t j2ll_rt_math_max_i32(int32_t left, int32_t right) {
                    return left >= right ? left : right;
                }

                int64_t j2ll_rt_math_max_i64(int64_t left, int64_t right) {
                    return left >= right ? left : right;
                }

                float j2ll_rt_math_max_f32(float left, float right) {
                    return left >= right ? left : right;
                }

                double j2ll_rt_math_max_f64(double left, double right) {
                    return left >= right ? left : right;
                }

                """;
    }

    private String monitorHelperSource() {
        return """
                void j2ll_rt_monitor_enter(JNIEnv* env, jobject monitor) {
                    if (monitor == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "monitor is null");
                        return;
                    }
                    if ((*env)->MonitorEnter(env, monitor) != JNI_OK && !(*env)->ExceptionCheck(env)) {
                        j2ll_throw_new(env, "java/lang/IllegalMonitorStateException", "MonitorEnter failed");
                    }
                }

                void j2ll_rt_monitor_exit(JNIEnv* env, jobject monitor) {
                    if (monitor == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "monitor is null");
                        return;
                    }
                    if ((*env)->MonitorExit(env, monitor) != JNI_OK && !(*env)->ExceptionCheck(env)) {
                        j2ll_throw_new(env, "java/lang/IllegalMonitorStateException", "MonitorExit failed");
                    }
                }

                void j2ll_rt_monitor_exit_on_exception(JNIEnv* env, jobject monitor) {
                    if (monitor == NULL) {
                        if (!(*env)->ExceptionCheck(env)) {
                            j2ll_throw_new(env, "java/lang/NullPointerException", "monitor is null");
                        }
                        return;
                    }
                    if ((*env)->MonitorExit(env, monitor) != JNI_OK && !(*env)->ExceptionCheck(env)) {
                        j2ll_throw_new(env, "java/lang/IllegalMonitorStateException", "MonitorExit failed while unwinding");
                    }
                }

                """;
    }

    private String jdkObjectHelperSource() {
        return """
                static jobject j2ll_call_static_box(JNIEnv* env, const char* class_name, const char* descriptor, ...) {
                    jclass cls = (*env)->FindClass(env, class_name);
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID method = (*env)->GetStaticMethodID(env, cls, "valueOf", descriptor);
                    if (method == NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                        return NULL;
                    }
                    va_list args;
                    va_start(args, descriptor);
                    jobject result = (*env)->CallStaticObjectMethodV(env, cls, method, args);
                    va_end(args);
                    (*env)->DeleteLocalRef(env, cls);
                    return result;
                }

                jobject j2ll_rt_integer_value_of(JNIEnv* env, int32_t value) {
                    return j2ll_call_static_box(env, "java/lang/Integer", "(I)Ljava/lang/Integer;", (jint)value);
                }

                int32_t j2ll_rt_integer_int_value(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Integer receiver is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/Integer");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "intValue", "()I");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0;
                    }
                    return (*env)->CallIntMethod(env, value, method);
                }

                jobject j2ll_rt_long_value_of(JNIEnv* env, int64_t value) {
                    return j2ll_call_static_box(env, "java/lang/Long", "(J)Ljava/lang/Long;", (jlong)value);
                }

                int64_t j2ll_rt_long_long_value(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Long receiver is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/Long");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "longValue", "()J");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0;
                    }
                    return (*env)->CallLongMethod(env, value, method);
                }

                jobject j2ll_rt_boolean_value_of(JNIEnv* env, int32_t value) {
                    return j2ll_call_static_box(env, "java/lang/Boolean", "(Z)Ljava/lang/Boolean;", value != 0 ? JNI_TRUE : JNI_FALSE);
                }

                int32_t j2ll_rt_boolean_boolean_value(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Boolean receiver is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/Boolean");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "booleanValue", "()Z");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0;
                    }
                    return (*env)->CallBooleanMethod(env, value, method) == JNI_TRUE ? 1 : 0;
                }

                jobject j2ll_rt_double_value_of(JNIEnv* env, double value) {
                    return j2ll_call_static_box(env, "java/lang/Double", "(D)Ljava/lang/Double;", (jdouble)value);
                }

                double j2ll_rt_double_double_value(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Double receiver is null");
                        return 0.0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/Double");
                    if (cls == NULL) {
                        return 0.0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "doubleValue", "()D");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0.0;
                    }
                    return (*env)->CallDoubleMethod(env, value, method);
                }

                jobject j2ll_rt_objects_require_non_null(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "required object is null");
                    }
                    return value;
                }

                int32_t j2ll_rt_objects_equals(JNIEnv* env, jobject left, jobject right) {
                    if ((*env)->IsSameObject(env, left, right)) {
                        return 1;
                    }
                    if (left == NULL || right == NULL) {
                        return 0;
                    }
                    jclass cls = (*env)->GetObjectClass(env, left);
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "equals", "(Ljava/lang/Object;)Z");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0;
                    }
                    return (*env)->CallBooleanMethod(env, left, method, right) == JNI_TRUE ? 1 : 0;
                }

                """;
    }

    private String arrayHelperSource() {
        return """
                int32_t j2ll_rt_array_length_i32(JNIEnv* env, jarray array) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0;
                    }
                    return (*env)->GetArrayLength(env, array);
                }

                int32_t j2ll_rt_array_load_i8(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "byte array index out of bounds");
                        return 0;
                    }
                    jbyte value = 0;
                    (*env)->GetByteArrayRegion(env, (jbyteArray)array, index, 1, &value);
                    return (int32_t)value;
                }

                void j2ll_rt_array_store_i8(JNIEnv* env, jarray array, int32_t index, int32_t value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "byte array index out of bounds");
                        return;
                    }
                    jbyte copy = (jbyte)value;
                    (*env)->SetByteArrayRegion(env, (jbyteArray)array, index, 1, &copy);
                }

                int32_t j2ll_rt_array_load_i16(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "short array index out of bounds");
                        return 0;
                    }
                    jshort value = 0;
                    (*env)->GetShortArrayRegion(env, (jshortArray)array, index, 1, &value);
                    return (int32_t)value;
                }

                void j2ll_rt_array_store_i16(JNIEnv* env, jarray array, int32_t index, int32_t value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "short array index out of bounds");
                        return;
                    }
                    jshort copy = (jshort)value;
                    (*env)->SetShortArrayRegion(env, (jshortArray)array, index, 1, &copy);
                }

                int32_t j2ll_rt_array_load_u16(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "char array index out of bounds");
                        return 0;
                    }
                    jchar value = 0;
                    (*env)->GetCharArrayRegion(env, (jcharArray)array, index, 1, &value);
                    return (int32_t)value;
                }

                void j2ll_rt_array_store_u16(JNIEnv* env, jarray array, int32_t index, int32_t value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "char array index out of bounds");
                        return;
                    }
                    jchar copy = (jchar)value;
                    (*env)->SetCharArrayRegion(env, (jcharArray)array, index, 1, &copy);
                }

                int32_t j2ll_rt_array_load_i32(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "int array index out of bounds");
                        return 0;
                    }
                    jint value = 0;
                    (*env)->GetIntArrayRegion(env, (jintArray)array, index, 1, &value);
                    return value;
                }

                void j2ll_rt_array_store_i32(JNIEnv* env, jarray array, int32_t index, int32_t value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "int array index out of bounds");
                        return;
                    }
                    jint copy = value;
                    (*env)->SetIntArrayRegion(env, (jintArray)array, index, 1, &copy);
                }

                int64_t j2ll_rt_array_load_i64(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "long array index out of bounds");
                        return 0;
                    }
                    jlong value = 0;
                    (*env)->GetLongArrayRegion(env, (jlongArray)array, index, 1, &value);
                    return (int64_t)value;
                }

                void j2ll_rt_array_store_i64(JNIEnv* env, jarray array, int32_t index, int64_t value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "long array index out of bounds");
                        return;
                    }
                    jlong copy = (jlong)value;
                    (*env)->SetLongArrayRegion(env, (jlongArray)array, index, 1, &copy);
                }

                float j2ll_rt_array_load_f32(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0.0f;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0.0f;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "float array index out of bounds");
                        return 0.0f;
                    }
                    jfloat value = 0.0f;
                    (*env)->GetFloatArrayRegion(env, (jfloatArray)array, index, 1, &value);
                    return value;
                }

                void j2ll_rt_array_store_f32(JNIEnv* env, jarray array, int32_t index, float value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "float array index out of bounds");
                        return;
                    }
                    jfloat copy = (jfloat)value;
                    (*env)->SetFloatArrayRegion(env, (jfloatArray)array, index, 1, &copy);
                }

                double j2ll_rt_array_load_f64(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0.0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0.0;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "double array index out of bounds");
                        return 0.0;
                    }
                    jdouble value = 0.0;
                    (*env)->GetDoubleArrayRegion(env, (jdoubleArray)array, index, 1, &value);
                    return value;
                }

                void j2ll_rt_array_store_f64(JNIEnv* env, jarray array, int32_t index, double value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "double array index out of bounds");
                        return;
                    }
                    jdouble copy = (jdouble)value;
                    (*env)->SetDoubleArrayRegion(env, (jdoubleArray)array, index, 1, &copy);
                }

                jobject j2ll_rt_array_load_ref(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return NULL;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return NULL;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "object array index out of bounds");
                        return NULL;
                    }
                    return (*env)->GetObjectArrayElement(env, (jobjectArray)array, index);
                }

                void j2ll_rt_array_store_ref(JNIEnv* env, jarray array, int32_t index, jobject value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "object array index out of bounds");
                        return;
                    }
                    (*env)->SetObjectArrayElement(env, (jobjectArray)array, index, value);
                }

                """;
    }

    private void appendAllocationHelperSource(StringBuilder builder, List<Binding> bindings) {
        List<String> allocationKeys = bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.allocationKeys().stream())
                .distinct()
                .sorted()
                .toList();
        List<String> typeCheckKeys = bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.typeCheckKeys().stream())
                .distinct()
                .sorted()
                .toList();
        TreeMap<String, ClassParts> classEntries = new TreeMap<>();
        builder.append("""
                typedef struct {
                    int64_t token;
                    int64_t class_init_token;
                    const char* class_name;
                } j2ll_class_entry;

                static const j2ll_class_entry j2ll_class_table[] = {
                """);
        bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .map(binding -> new ClassParts("L" + binding.decision().method().owner() + ";", binding.decision().method().owner()))
                .forEach(parts -> classEntries.putIfAbsent(parts.identity(), parts));
        bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.classObjectKeys().stream())
                .map(this::parseClassObjectKey)
                .forEach(parts -> classEntries.putIfAbsent(parts.identity(), parts));
        bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.runtimeMetadataKeys().stream())
                .filter(key -> key.startsWith("class:"))
                .map(this::parseClassObjectKey)
                .forEach(parts -> classEntries.putIfAbsent(parts.identity(), parts));
        for (String allocationKey : allocationKeys) {
            ClassParts parts = parseAllocationKey(allocationKey);
            classEntries.putIfAbsent(parts.identity(), parts);
        }
        for (String typeCheckKey : typeCheckKeys) {
            ClassParts parts = parseTypeCheckKey(typeCheckKey);
            classEntries.putIfAbsent(parts.identity(), parts);
        }
        for (ClassParts parts : classEntries.values()) {
            builder.append("    { ")
                    .append(ClassIdentityToken.token(parts.identity()))
                    .append("LL, ")
                    .append(stableClassObjectToken(parts.identity()))
                    .append("LL, \"")
                    .append(escapeCString(parts.jniName()))
                    .append("\" },\n");
        }
        builder.append("    { 0LL, 0LL, NULL },\n");
        builder.append("""
                };

                static const char* j2ll_find_class_name(int64_t token) {
                    for (size_t index = 0; index < sizeof(j2ll_class_table) / sizeof(j2ll_class_table[0]); index++) {
                        if (j2ll_class_table[index].class_name != NULL && j2ll_class_table[index].token == token) {
                            return j2ll_class_table[index].class_name;
                        }
                    }
                    return NULL;
                }

                static const char* j2ll_find_class_object_name(int64_t token) {
                    for (size_t index = 0; index < sizeof(j2ll_class_table) / sizeof(j2ll_class_table[0]); index++) {
                        if (j2ll_class_table[index].class_name != NULL && j2ll_class_table[index].class_init_token == token) {
                            return j2ll_class_table[index].class_name;
                        }
                    }
                    return NULL;
                }

                jobject j2ll_rt_alloc_object(JNIEnv* env, int64_t class_token) {
                    const char* class_name = j2ll_find_class_name(class_token);
                    if (class_name == NULL) {
                        j2ll_throw_new(env, "java/lang/NoClassDefFoundError", "unknown j2ll class token");
                        return NULL;
                    }
                    jclass cls = (*env)->FindClass(env, class_name);
                    if (cls == NULL) {
                        return NULL;
                    }
                    jobject object = (*env)->AllocObject(env, cls);
                    (*env)->DeleteLocalRef(env, cls);
                    return object;
                }

                jarray j2ll_rt_new_int_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative int array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewIntArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_byte_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative byte array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewByteArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_short_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative short array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewShortArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_char_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative char array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewCharArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_long_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative long array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewLongArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_float_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative float array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewFloatArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_double_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative double array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewDoubleArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_object_array(JNIEnv* env, int64_t component_token, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative object array length");
                        return NULL;
                    }
                    const char* class_name = j2ll_find_class_name(component_token);
                    if (class_name == NULL) {
                        j2ll_throw_new(env, "java/lang/NoClassDefFoundError", "unknown j2ll array component token");
                        return NULL;
                    }
                    jclass component = (*env)->FindClass(env, class_name);
                    if (component == NULL) {
                        return NULL;
                    }
                    jobjectArray array = (*env)->NewObjectArray(env, (jsize)length, component, NULL);
                    (*env)->DeleteLocalRef(env, component);
                    return (jarray)array;
                }

                """);
    }

    private String typeHelperSource() {
        return """
                jobject j2ll_rt_checkcast(JNIEnv* env, jobject value, int64_t class_token) {
                    if (value == NULL) {
                        return NULL;
                    }
                    const char* class_name = j2ll_find_class_name(class_token);
                    if (class_name == NULL) {
                        j2ll_throw_new(env, "java/lang/NoClassDefFoundError", "unknown j2ll type token");
                        return NULL;
                    }
                    jclass target = (*env)->FindClass(env, class_name);
                    if (target == NULL) {
                        return NULL;
                    }
                    jboolean matched = (*env)->IsInstanceOf(env, value, target);
                    (*env)->DeleteLocalRef(env, target);
                    if (matched != JNI_TRUE) {
                        j2ll_throw_new(env, "java/lang/ClassCastException", "j2ll checkcast failed");
                        return NULL;
                    }
                    return value;
                }

                int32_t j2ll_rt_instanceof(JNIEnv* env, jobject value, int64_t class_token) {
                    if (value == NULL) {
                        return 0;
                    }
                    const char* class_name = j2ll_find_class_name(class_token);
                    if (class_name == NULL) {
                        j2ll_throw_new(env, "java/lang/NoClassDefFoundError", "unknown j2ll type token");
                        return 0;
                    }
                    jclass target = (*env)->FindClass(env, class_name);
                    if (target == NULL) {
                        return 0;
                    }
                    jboolean matched = (*env)->IsInstanceOf(env, value, target);
                    (*env)->DeleteLocalRef(env, target);
                    return matched == JNI_TRUE ? 1 : 0;
                }

                """;
    }

    private String stringHelperSource() {
        return """
                int32_t j2ll_rt_string_length(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return 0;
                    }
                    return (*env)->GetStringLength(env, (jstring)value);
                }

                int32_t j2ll_rt_string_is_empty(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return 0;
                    }
                    return (*env)->GetStringLength(env, (jstring)value) == 0 ? 1 : 0;
                }

                int32_t j2ll_rt_string_char_at(JNIEnv* env, jobject value, int32_t index) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/String");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "charAt", "(I)C");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0;
                    }
                    return (int32_t)(*env)->CallCharMethod(env, value, method, (jint)index);
                }

                int32_t j2ll_rt_string_equals(JNIEnv* env, jobject receiver, jobject other) {
                    if (receiver == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return 0;
                    }
                    if (other == NULL) {
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/String");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID equals = (*env)->GetMethodID(env, cls, "equals", "(Ljava/lang/Object;)Z");
                    (*env)->DeleteLocalRef(env, cls);
                    if (equals == NULL) {
                        return 0;
                    }
                    return (*env)->CallBooleanMethod(env, receiver, equals, other) == JNI_TRUE ? 1 : 0;
                }

                static int32_t j2ll_call_string_boolean_method(JNIEnv* env, jobject receiver, jobject argument, const char* name) {
                    if (receiver == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/String");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, name, "(Ljava/lang/String;)Z");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0;
                    }
                    return (*env)->CallBooleanMethod(env, receiver, method, argument) == JNI_TRUE ? 1 : 0;
                }

                int32_t j2ll_rt_string_starts_with(JNIEnv* env, jobject receiver, jobject prefix) {
                    return j2ll_call_string_boolean_method(env, receiver, prefix, "startsWith");
                }

                int32_t j2ll_rt_string_ends_with(JNIEnv* env, jobject receiver, jobject suffix) {
                    return j2ll_call_string_boolean_method(env, receiver, suffix, "endsWith");
                }

                jobject j2ll_rt_string_substring(JNIEnv* env, jobject receiver, int32_t begin_index) {
                    if (receiver == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return NULL;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/String");
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "substring", "(I)Ljava/lang/String;");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, receiver, method, (jint)begin_index);
                }

                jobject j2ll_rt_string_substring_range(JNIEnv* env, jobject receiver, int32_t begin_index, int32_t end_index) {
                    if (receiver == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return NULL;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/String");
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "substring", "(II)Ljava/lang/String;");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, receiver, method, (jint)begin_index, (jint)end_index);
                }

                static jclass j2ll_string_builder_class(JNIEnv* env) {
                    return (*env)->FindClass(env, "java/lang/StringBuilder");
                }

                jobject j2ll_rt_string_builder_new(JNIEnv* env) {
                    jclass cls = j2ll_string_builder_class(env);
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID init = (*env)->GetMethodID(env, cls, "<init>", "()V");
                    if (init == NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                        return NULL;
                    }
                    jobject builder = (*env)->NewObject(env, cls, init);
                    (*env)->DeleteLocalRef(env, cls);
                    return builder;
                }

                void j2ll_rt_string_builder_init(JNIEnv* env, jobject builder) {
                    if (builder == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "StringBuilder receiver is null");
                        return;
                    }
                    jclass cls = j2ll_string_builder_class(env);
                    if (cls == NULL) {
                        return;
                    }
                    jmethodID init = (*env)->GetMethodID(env, cls, "<init>", "()V");
                    if (init == NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                        return;
                    }
                    (*env)->CallNonvirtualVoidMethod(env, builder, cls, init);
                    (*env)->DeleteLocalRef(env, cls);
                }

                static jobject j2ll_call_string_builder_append(JNIEnv* env, jobject builder, const char* descriptor, ...) {
                    if (builder == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "StringBuilder receiver is null");
                        return NULL;
                    }
                    jclass cls = j2ll_string_builder_class(env);
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "append", descriptor);
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return NULL;
                    }
                    va_list args;
                    va_start(args, descriptor);
                    jobject result = (*env)->CallObjectMethodV(env, builder, method, args);
                    va_end(args);
                    return result;
                }

                jobject j2ll_rt_string_builder_append_ref(JNIEnv* env, jobject builder, jobject value) {
                    return j2ll_call_string_builder_append(env, builder, "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", value);
                }

                jobject j2ll_rt_string_builder_append_i32(JNIEnv* env, jobject builder, int32_t value) {
                    return j2ll_call_string_builder_append(env, builder, "(I)Ljava/lang/StringBuilder;", (jint)value);
                }

                jobject j2ll_rt_string_builder_append_i64(JNIEnv* env, jobject builder, int64_t value) {
                    return j2ll_call_string_builder_append(env, builder, "(J)Ljava/lang/StringBuilder;", (jlong)value);
                }

                jobject j2ll_rt_string_builder_append_f32(JNIEnv* env, jobject builder, float value) {
                    return j2ll_call_string_builder_append(env, builder, "(F)Ljava/lang/StringBuilder;", (jfloat)value);
                }

                jobject j2ll_rt_string_builder_append_f64(JNIEnv* env, jobject builder, double value) {
                    return j2ll_call_string_builder_append(env, builder, "(D)Ljava/lang/StringBuilder;", (jdouble)value);
                }

                jobject j2ll_rt_string_builder_to_string(JNIEnv* env, jobject builder) {
                    if (builder == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "StringBuilder receiver is null");
                        return NULL;
                    }
                    jclass cls = j2ll_string_builder_class(env);
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "toString", "()Ljava/lang/String;");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, builder, method);
                }

                void j2ll_rt_system_arraycopy(JNIEnv* env, jobject src, int32_t src_pos, jobject dst, int32_t dst_pos, int32_t length) {
                    jclass system_class = (*env)->FindClass(env, "java/lang/System");
                    if (system_class == NULL) {
                        return;
                    }
                    jmethodID method = (*env)->GetStaticMethodID(
                            env,
                            system_class,
                            "arraycopy",
                            "(Ljava/lang/Object;ILjava/lang/Object;II)V");
                    if (method == NULL) {
                        (*env)->DeleteLocalRef(env, system_class);
                        return;
                    }
                    (*env)->CallStaticVoidMethod(env, system_class, method, src, (jint)src_pos, dst, (jint)dst_pos, (jint)length);
                    (*env)->DeleteLocalRef(env, system_class);
                }

                """;
    }

    private void appendStringConstantHelperSource(StringBuilder builder, List<Binding> bindings) {
        Map<Long, String> constants = new TreeMap<>();
        Map<Long, EncryptedStringConstant> encryptedConstants = new TreeMap<>();
        for (Binding binding : bindings) {
            if (binding.path() != NativeImplementationPath.LLVM_NATIVE_PATH || binding.templateIrMethod().isEmpty()) {
                continue;
            }
            binding.templateIrMethod().orElseThrow().blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .filter(instruction -> instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER)
                    .flatMap(instruction -> instruction.symbol().stream())
                    .filter(symbol -> symbol.startsWith("j2ll_rt_string_constant|string:"))
                    .forEach(symbol -> {
                        String value = symbol.substring("j2ll_rt_string_constant|string:".length());
                        constants.putIfAbsent(javaStringHashUnsigned("string:" + value), value);
                    });
            binding.templateIrMethod().orElseThrow().blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .filter(instruction -> instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER)
                    .flatMap(instruction -> instruction.symbol().stream())
                    .filter(symbol -> symbol.startsWith("j2ll_rt_string_constant|enc:v1:"))
                    .forEach(symbol -> {
                        String[] parts = symbol.split(":", 5);
                        if (parts.length == 5) {
                            long token = Long.parseLong(parts[2]);
                            encryptedConstants.putIfAbsent(token, new EncryptedStringConstant(
                                    token,
                                    parts[3],
                                    parts[4],
                                    parts[4].length() / 2));
                        }
                    });
        }
        if (constants.isEmpty() && encryptedConstants.isEmpty()) {
            builder.append("""
                    jobject j2ll_rt_string_constant(JNIEnv* env, int64_t token) {
                        (void)token;
                        j2ll_throw_new(env, "java/lang/IllegalArgumentException", "unknown string constant token");
                        return NULL;
                    }

                    """);
            return;
        }
        if (!constants.isEmpty()) {
            builder.append("typedef struct { int64_t token; const char* value; } j2ll_string_constant_entry;\n")
                    .append("static const j2ll_string_constant_entry j2ll_string_constant_table[] = {\n");
            for (Map.Entry<Long, String> entry : constants.entrySet()) {
                builder.append("    { ")
                        .append(entry.getKey())
                        .append("LL, \"")
                        .append(escapeCString(entry.getValue()))
                        .append("\" },\n");
            }
            builder.append("};\n");
        }
        if (!encryptedConstants.isEmpty()) {
            int index = 0;
            for (EncryptedStringConstant entry : encryptedConstants.values()) {
                builder.append("static const unsigned char j2ll_str_key_")
                        .append(index)
                        .append("[] = { ")
                        .append(cByteArray(entry.keyHex()))
                        .append(" };\n")
                        .append("static const unsigned char j2ll_str_cipher_")
                        .append(index)
                        .append("[] = { ")
                        .append(cByteArray(entry.cipherHex()))
                        .append(" };\n");
                index++;
            }
            builder.append("""
                    typedef struct {
                        int64_t token;
                        const unsigned char* key;
                        size_t key_len;
                        const unsigned char* cipher;
                        size_t cipher_len;
                    } j2ll_encrypted_string_constant_entry;
                    """);
            builder.append("static const j2ll_encrypted_string_constant_entry j2ll_encrypted_string_constant_table[] = {\n");
            index = 0;
            for (EncryptedStringConstant entry : encryptedConstants.values()) {
                builder.append("    { ")
                        .append(entry.token())
                        .append("LL, j2ll_str_key_")
                        .append(index)
                        .append(", sizeof(j2ll_str_key_")
                        .append(index)
                        .append("), j2ll_str_cipher_")
                        .append(index)
                        .append(", ")
                        .append(entry.length())
                        .append(" },\n");
                index++;
            }
            builder.append("};\n");
        }
        builder.append("jobject j2ll_rt_string_constant(JNIEnv* env, int64_t token) {\n");
        if (!constants.isEmpty()) {
            builder.append("    for (size_t index = 0; index < sizeof(j2ll_string_constant_table) / sizeof(j2ll_string_constant_table[0]); index++) {\n")
                    .append("        if (j2ll_string_constant_table[index].token == token) {\n")
                    .append("            return (*env)->NewStringUTF(env, j2ll_string_constant_table[index].value);\n")
                    .append("        }\n")
                    .append("    }\n");
        }
        if (!encryptedConstants.isEmpty()) {
            builder.append("""
                    for (size_t index = 0; index < sizeof(j2ll_encrypted_string_constant_table) / sizeof(j2ll_encrypted_string_constant_table[0]); index++) {
                        const j2ll_encrypted_string_constant_entry* entry = &j2ll_encrypted_string_constant_table[index];
                        if (entry->token == token) {
                            char* plain = (char*)malloc(entry->cipher_len + 1);
                            if (plain == NULL) {
                                j2ll_throw_new(env, "java/lang/OutOfMemoryError", "string decrypt allocation failed");
                                return NULL;
                            }
                            for (size_t byte_index = 0; byte_index < entry->cipher_len; byte_index++) {
                                plain[byte_index] = (char)(entry->cipher[byte_index] ^ entry->key[byte_index % entry->key_len]);
                            }
                            plain[entry->cipher_len] = 0;
                            jstring result = (*env)->NewStringUTF(env, plain);
                            free(plain);
                            return result;
                        }
                    }
                    """);
        }
        builder.append("    j2ll_throw_new(env, \"java/lang/IllegalArgumentException\", \"unknown string constant token\");\n")
                .append("    return NULL;\n")
                .append("}\n\n");
    }

    private String cByteArray(String hex) {
        ArrayList<String> bytes = new ArrayList<>();
        for (int index = 0; index < hex.length(); index += 2) {
            bytes.add("0x" + hex.substring(index, index + 2));
        }
        return String.join(", ", bytes);
    }

    private record EncryptedStringConstant(long token, String keyHex, String cipherHex, int length) {
    }

    private void appendLambdaHelperSource(StringBuilder builder, List<Binding> bindings) {
        Map<Long, String[]> lambdaSpecs = new TreeMap<>();
        for (Binding binding : bindings) {
            if (binding.path() != NativeImplementationPath.LLVM_NATIVE_PATH || binding.templateIrMethod().isEmpty()) {
                continue;
            }
            binding.templateIrMethod().orElseThrow().blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .filter(instruction -> instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER)
                    .flatMap(instruction -> instruction.symbol().stream())
                    .filter(symbol -> symbol.startsWith("j2ll_rt_lambda_new|lambda:"))
                    .forEach(symbol -> {
                        String encoded = symbol.substring("j2ll_rt_lambda_new|lambda:".length());
                        String spec = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                        String[] fields = spec.split("\n", -1);
                        if (fields.length == 9) {
                            lambdaSpecs.putIfAbsent(javaStringHashUnsigned("lambda:" + spec), fields);
                        }
                    });
        }
        if (lambdaSpecs.isEmpty()) {
            builder.append("""
                    jobject j2ll_rt_lambda_new(JNIEnv* env, int64_t token, jobject capture) {
                        (void)token;
                        (void)capture;
                        j2ll_throw_new(env, "java/lang/IllegalArgumentException", "unknown lambda token");
                        return NULL;
                    }

                    """);
            return;
        }
        builder.append("""
                typedef struct {
                    int64_t token;
                    const char* caller_owner;
                    const char* invoked_name;
                    const char* invoked_desc;
                    const char* sam_desc;
                    int ref_kind;
                    const char* impl_owner;
                    const char* impl_name;
                    const char* impl_desc;
                    const char* instantiated_desc;
                } j2ll_lambda_entry;
                """);
        builder.append("static const j2ll_lambda_entry j2ll_lambda_table[] = {\n");
        for (Map.Entry<Long, String[]> entry : lambdaSpecs.entrySet()) {
            String[] fields = entry.getValue();
            builder.append("    { ")
                    .append(entry.getKey())
                    .append("LL, \"")
                    .append(escapeCString(fields[0]))
                    .append("\", \"")
                    .append(escapeCString(fields[1]))
                    .append("\", \"")
                    .append(escapeCString(fields[2]))
                    .append("\", \"")
                    .append(escapeCString(fields[3]))
                    .append("\", ")
                    .append(Integer.parseInt(fields[4]))
                    .append(", \"")
                    .append(escapeCString(fields[5]))
                    .append("\", \"")
                    .append(escapeCString(fields[6]))
                    .append("\", \"")
                    .append(escapeCString(fields[7]))
                    .append("\", \"")
                    .append(escapeCString(fields[8]))
                    .append("\" },\n");
        }
        builder.append("""
                };

                static const j2ll_lambda_entry* j2ll_find_lambda_entry(int64_t token) {
                    for (size_t index = 0; index < sizeof(j2ll_lambda_table) / sizeof(j2ll_lambda_table[0]); index++) {
                        if (j2ll_lambda_table[index].token == token) {
                            return &j2ll_lambda_table[index];
                        }
                    }
                    return NULL;
                }

                static jobject j2ll_method_type_from_descriptor(JNIEnv* env, const char* descriptor, jobject loader) {
                    jclass method_type_class = (*env)->FindClass(env, "java/lang/invoke/MethodType");
                    if (method_type_class == NULL) {
                        return NULL;
                    }
                    jmethodID from_descriptor = (*env)->GetStaticMethodID(
                            env,
                            method_type_class,
                            "fromMethodDescriptorString",
                            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
                    if (from_descriptor == NULL) {
                        return NULL;
                    }
                    jstring descriptor_string = (*env)->NewStringUTF(env, descriptor);
                    if (descriptor_string == NULL) {
                        return NULL;
                    }
                    return (*env)->CallStaticObjectMethod(env, method_type_class, from_descriptor, descriptor_string, loader);
                }

                static jobject j2ll_lambda_impl_handle(
                        JNIEnv* env,
                        jobject lookup,
                        jclass owner_class,
                        const j2ll_lambda_entry* entry,
                        jobject impl_type) {
                    jclass lookup_class = (*env)->FindClass(env, "java/lang/invoke/MethodHandles$Lookup");
                    if (lookup_class == NULL) {
                        return NULL;
                    }
                    jstring name = (*env)->NewStringUTF(env, entry->impl_name);
                    if (name == NULL) {
                        return NULL;
                    }
                    if (entry->ref_kind == 6) {
                        jmethodID find_static = (*env)->GetMethodID(
                                env,
                                lookup_class,
                                "findStatic",
                                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
                        if (find_static == NULL) {
                            return NULL;
                        }
                        return (*env)->CallObjectMethod(env, lookup, find_static, owner_class, name, impl_type);
                    }
                    if (entry->ref_kind == 5 || entry->ref_kind == 9) {
                        jmethodID find_virtual = (*env)->GetMethodID(
                                env,
                                lookup_class,
                                "findVirtual",
                                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
                        if (find_virtual == NULL) {
                            return NULL;
                        }
                        return (*env)->CallObjectMethod(env, lookup, find_virtual, owner_class, name, impl_type);
                    }
                    if (entry->ref_kind == 8) {
                        jmethodID find_constructor = (*env)->GetMethodID(
                                env,
                                lookup_class,
                                "findConstructor",
                                "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
                        if (find_constructor == NULL) {
                            return NULL;
                        }
                        return (*env)->CallObjectMethod(env, lookup, find_constructor, owner_class, impl_type);
                    }
                    j2ll_throw_new(env, "java/lang/UnsupportedOperationException", "unsupported lambda implementation handle kind");
                    return NULL;
                }

                jobject j2ll_rt_lambda_new(JNIEnv* env, int64_t token, jobject capture) {
                    const j2ll_lambda_entry* entry = j2ll_find_lambda_entry(token);
                    if (entry == NULL) {
                        j2ll_throw_new(env, "java/lang/IllegalArgumentException", "unknown lambda token");
                        return NULL;
                    }
                    jclass method_handles_class = (*env)->FindClass(env, "java/lang/invoke/MethodHandles");
                    jclass lambda_metafactory_class = (*env)->FindClass(env, "java/lang/invoke/LambdaMetafactory");
                    jclass call_site_class = (*env)->FindClass(env, "java/lang/invoke/CallSite");
                    jclass method_handle_class = (*env)->FindClass(env, "java/lang/invoke/MethodHandle");
                    jclass object_class = (*env)->FindClass(env, "java/lang/Object");
                    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
                    jclass caller_class = (*env)->FindClass(env, entry->caller_owner);
                    jclass owner_class = (*env)->FindClass(env, entry->impl_owner);
                    if (method_handles_class == NULL || lambda_metafactory_class == NULL || call_site_class == NULL
                            || method_handle_class == NULL || object_class == NULL || class_class == NULL
                            || caller_class == NULL || owner_class == NULL) {
                        return NULL;
                    }
                    jmethodID lookup_method = (*env)->GetStaticMethodID(
                            env,
                            method_handles_class,
                            "lookup",
                            "()Ljava/lang/invoke/MethodHandles$Lookup;");
                    jmethodID public_lookup_method = (*env)->GetStaticMethodID(
                            env,
                            method_handles_class,
                            "publicLookup",
                            "()Ljava/lang/invoke/MethodHandles$Lookup;");
                    jmethodID private_lookup_in = (*env)->GetStaticMethodID(
                            env,
                            method_handles_class,
                            "privateLookupIn",
                            "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;");
                    jmethodID class_loader_method = (*env)->GetMethodID(
                            env,
                            class_class,
                            "getClassLoader",
                            "()Ljava/lang/ClassLoader;");
                    if (lookup_method == NULL
                            || public_lookup_method == NULL
                            || private_lookup_in == NULL
                            || class_loader_method == NULL) {
                        return NULL;
                    }
                    jobject base_lookup = (*env)->CallStaticObjectMethod(env, method_handles_class, lookup_method);
                    if ((*env)->ExceptionCheck(env) || base_lookup == NULL) {
                        return NULL;
                    }
                    jobject caller_lookup = (*env)->CallStaticObjectMethod(
                            env,
                            method_handles_class,
                            private_lookup_in,
                            caller_class,
                            base_lookup);
                    if ((*env)->ExceptionCheck(env) || caller_lookup == NULL) {
                        return NULL;
                    }
                    jobject impl_lookup;
                    if (strncmp(entry->impl_owner, "java/", 5) == 0 || strncmp(entry->impl_owner, "javax/", 6) == 0) {
                        impl_lookup = (*env)->CallStaticObjectMethod(env, method_handles_class, public_lookup_method);
                    } else {
                        impl_lookup = (*env)->CallStaticObjectMethod(
                                env,
                                method_handles_class,
                                private_lookup_in,
                                owner_class,
                                base_lookup);
                    }
                    if ((*env)->ExceptionCheck(env) || impl_lookup == NULL) {
                        return NULL;
                    }
                    jobject loader = (*env)->CallObjectMethod(env, caller_class, class_loader_method);
                    if ((*env)->ExceptionCheck(env)) {
                        return NULL;
                    }
                    jobject invoked_type = j2ll_method_type_from_descriptor(env, entry->invoked_desc, loader);
                    jobject sam_type = j2ll_method_type_from_descriptor(env, entry->sam_desc, loader);
                    jobject impl_type = j2ll_method_type_from_descriptor(env, entry->impl_desc, loader);
                    jobject instantiated_type = j2ll_method_type_from_descriptor(env, entry->instantiated_desc, loader);
                    if ((*env)->ExceptionCheck(env)
                            || invoked_type == NULL
                            || sam_type == NULL
                            || impl_type == NULL
                            || instantiated_type == NULL) {
                        return NULL;
                    }
                    jobject impl_handle = j2ll_lambda_impl_handle(env, impl_lookup, owner_class, entry, impl_type);
                    if ((*env)->ExceptionCheck(env) || impl_handle == NULL) {
                        return NULL;
                    }
                    jmethodID metafactory = (*env)->GetStaticMethodID(
                            env,
                            lambda_metafactory_class,
                            "metafactory",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;");
                    if (metafactory == NULL) {
                        return NULL;
                    }
                    jstring invoked_name = (*env)->NewStringUTF(env, entry->invoked_name);
                    if (invoked_name == NULL) {
                        return NULL;
                    }
                    jobject call_site = (*env)->CallStaticObjectMethod(
                            env,
                            lambda_metafactory_class,
                            metafactory,
                            caller_lookup,
                            invoked_name,
                            invoked_type,
                            sam_type,
                            impl_handle,
                            instantiated_type);
                    if ((*env)->ExceptionCheck(env) || call_site == NULL) {
                        return NULL;
                    }
                    jmethodID get_target = (*env)->GetMethodID(
                            env,
                            call_site_class,
                            "getTarget",
                            "()Ljava/lang/invoke/MethodHandle;");
                    jmethodID invoke_with_arguments = (*env)->GetMethodID(
                            env,
                            method_handle_class,
                            "invokeWithArguments",
                            "([Ljava/lang/Object;)Ljava/lang/Object;");
                    if (get_target == NULL || invoke_with_arguments == NULL) {
                        return NULL;
                    }
                    jobject factory = (*env)->CallObjectMethod(env, call_site, get_target);
                    if ((*env)->ExceptionCheck(env) || factory == NULL) {
                        return NULL;
                    }
                    jobjectArray arguments = (*env)->NewObjectArray(env, capture == NULL ? 0 : 1, object_class, NULL);
                    if (arguments == NULL) {
                        return NULL;
                    }
                    if (capture != NULL) {
                        (*env)->SetObjectArrayElement(env, arguments, 0, capture);
                        if ((*env)->ExceptionCheck(env)) {
                            return NULL;
                        }
                    }
                    return (*env)->CallObjectMethod(env, factory, invoke_with_arguments, arguments);
                }

                """);
    }

    private String varHandleHelperSource() {
        return """
                static jobject j2ll_box_int(JNIEnv* env, int32_t value) {
                    jclass cls = (*env)->FindClass(env, "java/lang/Integer");
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID value_of = (*env)->GetStaticMethodID(env, cls, "valueOf", "(I)Ljava/lang/Integer;");
                    if (value_of == NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                        return NULL;
                    }
                    jobject boxed = (*env)->CallStaticObjectMethod(env, cls, value_of, (jint)value);
                    (*env)->DeleteLocalRef(env, cls);
                    return boxed;
                }

                static int32_t j2ll_unbox_int(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "VarHandle int result is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/Integer");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID int_value = (*env)->GetMethodID(env, cls, "intValue", "()I");
                    (*env)->DeleteLocalRef(env, cls);
                    if (int_value == NULL) {
                        return 0;
                    }
                    return (int32_t)(*env)->CallIntMethod(env, value, int_value);
                }

                static int32_t j2ll_unbox_boolean(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "VarHandle boolean result is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/Boolean");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID boolean_value = (*env)->GetMethodID(env, cls, "booleanValue", "()Z");
                    (*env)->DeleteLocalRef(env, cls);
                    if (boolean_value == NULL) {
                        return 0;
                    }
                    return (*env)->CallBooleanMethod(env, value, boolean_value) == JNI_TRUE ? 1 : 0;
                }

                static jobjectArray j2ll_var_handle_args(JNIEnv* env, jobject target, int extra_count) {
                    jclass object_class = (*env)->FindClass(env, "java/lang/Object");
                    if (object_class == NULL) {
                        return NULL;
                    }
                    jobjectArray args = (*env)->NewObjectArray(env, 1 + extra_count, object_class, NULL);
                    (*env)->DeleteLocalRef(env, object_class);
                    if (args == NULL) {
                        return NULL;
                    }
                    (*env)->SetObjectArrayElement(env, args, 0, target);
                    return args;
                }

                static jobject j2ll_var_handle_access_mode(JNIEnv* env, const char* name) {
                    jclass cls = (*env)->FindClass(env, "java/lang/invoke/VarHandle$AccessMode");
                    if (cls == NULL) {
                        return NULL;
                    }
                    jfieldID field = (*env)->GetStaticFieldID(env, cls, name, "Ljava/lang/invoke/VarHandle$AccessMode;");
                    if (field == NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                        return NULL;
                    }
                    jobject mode = (*env)->GetStaticObjectField(env, cls, field);
                    (*env)->DeleteLocalRef(env, cls);
                    return mode;
                }

                static jobject j2ll_var_handle_method_handle(JNIEnv* env, jobject handle, const char* mode_name) {
                    jobject mode = j2ll_var_handle_access_mode(env, mode_name);
                    if (mode == NULL) {
                        return NULL;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/invoke/VarHandle");
                    if (cls == NULL) {
                        (*env)->DeleteLocalRef(env, mode);
                        return NULL;
                    }
                    jmethodID method = (*env)->GetMethodID(
                            env,
                            cls,
                            "toMethodHandle",
                            "(Ljava/lang/invoke/VarHandle$AccessMode;)Ljava/lang/invoke/MethodHandle;");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        (*env)->DeleteLocalRef(env, mode);
                        return NULL;
                    }
                    jobject method_handle = (*env)->CallObjectMethod(env, handle, method, mode);
                    (*env)->DeleteLocalRef(env, mode);
                    return method_handle;
                }

                static jobject j2ll_invoke_method_handle_with_args(JNIEnv* env, jobject method_handle, jobjectArray args) {
                    if (method_handle == NULL) {
                        return NULL;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/invoke/MethodHandle");
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID invoke = (*env)->GetMethodID(env, cls, "invokeWithArguments", "([Ljava/lang/Object;)Ljava/lang/Object;");
                    (*env)->DeleteLocalRef(env, cls);
                    if (invoke == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, method_handle, invoke, args);
                }

                static int32_t j2ll_var_handle_get_int(JNIEnv* env, jobject handle, jobject target, const char* mode_name) {
                    if (handle == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "VarHandle receiver is null");
                        return 0;
                    }
                    jobjectArray args = j2ll_var_handle_args(env, target, 0);
                    if (args == NULL) {
                        return 0;
                    }
                    jobject method_handle = j2ll_var_handle_method_handle(env, handle, mode_name);
                    if (method_handle == NULL) {
                        (*env)->DeleteLocalRef(env, args);
                        return 0;
                    }
                    jobject boxed = j2ll_invoke_method_handle_with_args(env, method_handle, args);
                    (*env)->DeleteLocalRef(env, method_handle);
                    (*env)->DeleteLocalRef(env, args);
                    if ((*env)->ExceptionCheck(env)) {
                        return 0;
                    }
                    int32_t result = j2ll_unbox_int(env, boxed);
                    if (boxed != NULL) {
                        (*env)->DeleteLocalRef(env, boxed);
                    }
                    return result;
                }

                static void j2ll_var_handle_set_int(JNIEnv* env, jobject handle, jobject target, int32_t value, const char* mode_name) {
                    if (handle == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "VarHandle receiver is null");
                        return;
                    }
                    jobjectArray args = j2ll_var_handle_args(env, target, 1);
                    if (args == NULL) {
                        return;
                    }
                    jobject boxed = j2ll_box_int(env, value);
                    if (boxed == NULL) {
                        (*env)->DeleteLocalRef(env, args);
                        return;
                    }
                    (*env)->SetObjectArrayElement(env, args, 1, boxed);
                    (*env)->DeleteLocalRef(env, boxed);
                    jobject method_handle = j2ll_var_handle_method_handle(env, handle, mode_name);
                    if (method_handle == NULL) {
                        (*env)->DeleteLocalRef(env, args);
                        return;
                    }
                    jobject ignored = j2ll_invoke_method_handle_with_args(env, method_handle, args);
                    if (ignored != NULL) {
                        (*env)->DeleteLocalRef(env, ignored);
                    }
                    (*env)->DeleteLocalRef(env, method_handle);
                    (*env)->DeleteLocalRef(env, args);
                }

                int32_t j2ll_rt_var_handle_get_int(JNIEnv* env, jobject handle, jobject target) {
                    return j2ll_var_handle_get_int(env, handle, target, "GET");
                }

                void j2ll_rt_var_handle_set_int(JNIEnv* env, jobject handle, jobject target, int32_t value) {
                    j2ll_var_handle_set_int(env, handle, target, value, "SET");
                }

                int32_t j2ll_rt_var_handle_get_volatile_int(JNIEnv* env, jobject handle, jobject target) {
                    return j2ll_var_handle_get_int(env, handle, target, "GET_VOLATILE");
                }

                void j2ll_rt_var_handle_set_volatile_int(JNIEnv* env, jobject handle, jobject target, int32_t value) {
                    j2ll_var_handle_set_int(env, handle, target, value, "SET_VOLATILE");
                }

                int32_t j2ll_rt_var_handle_compare_and_set_int(JNIEnv* env, jobject handle, jobject target, int32_t expected, int32_t update) {
                    if (handle == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "VarHandle receiver is null");
                        return 0;
                    }
                    jobjectArray args = j2ll_var_handle_args(env, target, 2);
                    if (args == NULL) {
                        return 0;
                    }
                    jobject boxed_expected = j2ll_box_int(env, expected);
                    jobject boxed_update = j2ll_box_int(env, update);
                    if (boxed_expected == NULL || boxed_update == NULL) {
                        (*env)->DeleteLocalRef(env, args);
                        return 0;
                    }
                    (*env)->SetObjectArrayElement(env, args, 1, boxed_expected);
                    (*env)->SetObjectArrayElement(env, args, 2, boxed_update);
                    (*env)->DeleteLocalRef(env, boxed_expected);
                    (*env)->DeleteLocalRef(env, boxed_update);
                    jobject method_handle = j2ll_var_handle_method_handle(env, handle, "COMPARE_AND_SET");
                    if (method_handle == NULL) {
                        (*env)->DeleteLocalRef(env, args);
                        return 0;
                    }
                    jobject boxed = j2ll_invoke_method_handle_with_args(env, method_handle, args);
                    (*env)->DeleteLocalRef(env, method_handle);
                    (*env)->DeleteLocalRef(env, args);
                    if ((*env)->ExceptionCheck(env)) {
                        return 0;
                    }
                    int32_t success = j2ll_unbox_boolean(env, boxed);
                    if (boxed != NULL) {
                        (*env)->DeleteLocalRef(env, boxed);
                    }
                    return success;
                }

                """;
    }

    private void appendReflectionHelperSource(StringBuilder builder, List<Binding> bindings) {
        List<String> reflectionKeys = bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.runtimeMetadataKeys().stream())
                .filter(key -> key.startsWith("method:") || key.startsWith("constructor:"))
                .distinct()
                .sorted()
                .toList();
        List<String> reflectionFieldKeys = bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.runtimeMetadataKeys().stream())
                .filter(key -> key.startsWith("field:"))
                .distinct()
                .sorted()
                .toList();
        builder.append("""
                typedef struct {
                    int64_t token;
                    int constructor;
                    const char* owner;
                    const char* name;
                    const char* descriptor;
                } j2ll_reflection_method_entry;

                static const j2ll_reflection_method_entry j2ll_reflection_method_table[] = {
                """);
        for (String key : reflectionKeys) {
            MethodParts parts = parseReflectionMethodKey(key);
            builder.append("    { ")
                    .append(stableClassObjectToken(key))
                    .append("LL, ")
                    .append(key.startsWith("constructor:") ? "1" : "0")
                    .append(", \"")
                    .append(escapeCString(parts.owner()))
                    .append("\", \"")
                    .append(escapeCString(parts.name()))
                    .append("\", \"")
                    .append(escapeCString(parts.descriptor()))
                    .append("\" },\n");
        }
        builder.append("""
                    { 0LL, 0, NULL, NULL, NULL },
                };

                static const j2ll_reflection_method_entry* j2ll_find_reflection_method(int64_t token) {
                    for (size_t index = 0; index < sizeof(j2ll_reflection_method_table) / sizeof(j2ll_reflection_method_table[0]); index++) {
                        if (j2ll_reflection_method_table[index].owner != NULL
                                && j2ll_reflection_method_table[index].token == token) {
                            return &j2ll_reflection_method_table[index];
                        }
                    }
                    return NULL;
                }

                typedef struct {
                    int64_t token;
                    const char* owner;
                    const char* name;
                } j2ll_reflection_field_entry;

                static const j2ll_reflection_field_entry j2ll_reflection_field_table[] = {
                """);
        for (String key : reflectionFieldKeys) {
            FieldParts parts = parseReflectionFieldKey(key);
            builder.append("    { ")
                    .append(stableClassObjectToken(key))
                    .append("LL, \"")
                    .append(escapeCString(parts.owner()))
                    .append("\", \"")
                    .append(escapeCString(parts.name()))
                    .append("\" },\n");
        }
        builder.append("""
                    { 0LL, NULL, NULL },
                };

                static const j2ll_reflection_field_entry* j2ll_find_reflection_field(int64_t token) {
                    for (size_t index = 0; index < sizeof(j2ll_reflection_field_table) / sizeof(j2ll_reflection_field_table[0]); index++) {
                        if (j2ll_reflection_field_table[index].owner != NULL
                                && j2ll_reflection_field_table[index].token == token) {
                            return &j2ll_reflection_field_table[index];
                        }
                    }
                    return NULL;
                }

                static jclass j2ll_class_for_name_with_init(JNIEnv* env, const char* internal_name, int initialize) {
                    char* dotted = j2ll_dotted_class_name(internal_name);
                    if (dotted == NULL) {
                        j2ll_throw_new(env, "java/lang/OutOfMemoryError", "failed to allocate class name");
                        return NULL;
                    }
                    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
                    if (class_class == NULL) {
                        free(dotted);
                        return NULL;
                    }
                    jmethodID for_name = (*env)->GetStaticMethodID(
                            env,
                            class_class,
                            "forName",
                            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
                    if (for_name == NULL) {
                        (*env)->DeleteLocalRef(env, class_class);
                        free(dotted);
                        return NULL;
                    }
                    jstring name = (*env)->NewStringUTF(env, dotted);
                    free(dotted);
                    if (name == NULL) {
                        (*env)->DeleteLocalRef(env, class_class);
                        return NULL;
                    }
                    jobject loader = j2ll_context_class_loader(env);
                    if ((*env)->ExceptionCheck(env)) {
                        (*env)->DeleteLocalRef(env, class_class);
                        (*env)->DeleteLocalRef(env, name);
                        if (loader != NULL) {
                            (*env)->DeleteLocalRef(env, loader);
                        }
                        return NULL;
                    }
                    jclass result = (jclass)(*env)->CallStaticObjectMethod(
                            env,
                            class_class,
                            for_name,
                            name,
                            initialize ? JNI_TRUE : JNI_FALSE,
                            loader);
                    (*env)->DeleteLocalRef(env, class_class);
                    (*env)->DeleteLocalRef(env, name);
                    if (loader != NULL) {
                        (*env)->DeleteLocalRef(env, loader);
                    }
                    return result;
                }

                static jobjectArray j2ll_empty_class_array(JNIEnv* env) {
                    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
                    if (class_class == NULL) {
                        return NULL;
                    }
                    jobjectArray array = (*env)->NewObjectArray(env, 0, class_class, NULL);
                    (*env)->DeleteLocalRef(env, class_class);
                    return array;
                }

                jclass j2ll_rt_class_for_name_static(JNIEnv* env, int64_t class_token, int32_t initialize) {
                    const char* class_name = j2ll_find_class_object_name(class_token);
                    if (class_name == NULL) {
                        j2ll_throw_new(env, "java/lang/ClassNotFoundException", "unknown j2ll class metadata token");
                        return NULL;
                    }
                    return j2ll_class_for_name_with_init(env, class_name, initialize != 0);
                }

                jobject j2ll_rt_get_declared_method(JNIEnv* env, int64_t method_token) {
                    const j2ll_reflection_method_entry* entry = j2ll_find_reflection_method(method_token);
                    if (entry == NULL || entry->constructor) {
                        j2ll_throw_new(env, "java/lang/NoSuchMethodException", "unknown j2ll reflection method token");
                        return NULL;
                    }
                    jclass owner = j2ll_class_for_name_with_init(env, entry->owner, 0);
                    if (owner == NULL) {
                        return NULL;
                    }
                    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
                    if (class_class == NULL) {
                        (*env)->DeleteLocalRef(env, owner);
                        return NULL;
                    }
                    jmethodID get_declared_method = (*env)->GetMethodID(
                            env,
                            class_class,
                            "getDeclaredMethod",
                            "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
                    (*env)->DeleteLocalRef(env, class_class);
                    if (get_declared_method == NULL) {
                        (*env)->DeleteLocalRef(env, owner);
                        return NULL;
                    }
                    jstring name = (*env)->NewStringUTF(env, entry->name);
                    jobjectArray parameters = j2ll_empty_class_array(env);
                    if (name == NULL || parameters == NULL) {
                        (*env)->DeleteLocalRef(env, owner);
                        if (name != NULL) {
                            (*env)->DeleteLocalRef(env, name);
                        }
                        return NULL;
                    }
                    jobject method = (*env)->CallObjectMethod(env, owner, get_declared_method, name, parameters);
                    (*env)->DeleteLocalRef(env, owner);
                    (*env)->DeleteLocalRef(env, name);
                    (*env)->DeleteLocalRef(env, parameters);
                    return method;
                }

                jobject j2ll_rt_get_declared_field(JNIEnv* env, int64_t field_token) {
                    const j2ll_reflection_field_entry* entry = j2ll_find_reflection_field(field_token);
                    if (entry == NULL) {
                        j2ll_throw_new(env, "java/lang/NoSuchFieldException", "unknown j2ll reflection field token");
                        return NULL;
                    }
                    jclass owner = j2ll_class_for_name_with_init(env, entry->owner, 0);
                    if (owner == NULL) {
                        return NULL;
                    }
                    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
                    if (class_class == NULL) {
                        (*env)->DeleteLocalRef(env, owner);
                        return NULL;
                    }
                    jmethodID get_declared_field = (*env)->GetMethodID(
                            env,
                            class_class,
                            "getDeclaredField",
                            "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
                    (*env)->DeleteLocalRef(env, class_class);
                    if (get_declared_field == NULL) {
                        (*env)->DeleteLocalRef(env, owner);
                        return NULL;
                    }
                    jstring name = (*env)->NewStringUTF(env, entry->name);
                    if (name == NULL) {
                        (*env)->DeleteLocalRef(env, owner);
                        return NULL;
                    }
                    jobject field = (*env)->CallObjectMethod(env, owner, get_declared_field, name);
                    (*env)->DeleteLocalRef(env, owner);
                    (*env)->DeleteLocalRef(env, name);
                    return field;
                }

                jobject j2ll_rt_get_declared_constructor(JNIEnv* env, int64_t method_token) {
                    const j2ll_reflection_method_entry* entry = j2ll_find_reflection_method(method_token);
                    if (entry == NULL || !entry->constructor) {
                        j2ll_throw_new(env, "java/lang/NoSuchMethodException", "unknown j2ll reflection constructor token");
                        return NULL;
                    }
                    jclass owner = j2ll_class_for_name_with_init(env, entry->owner, 0);
                    if (owner == NULL) {
                        return NULL;
                    }
                    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
                    if (class_class == NULL) {
                        (*env)->DeleteLocalRef(env, owner);
                        return NULL;
                    }
                    jmethodID get_declared_constructor = (*env)->GetMethodID(
                            env,
                            class_class,
                            "getDeclaredConstructor",
                            "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;");
                    (*env)->DeleteLocalRef(env, class_class);
                    if (get_declared_constructor == NULL) {
                        (*env)->DeleteLocalRef(env, owner);
                        return NULL;
                    }
                    jobjectArray parameters = j2ll_empty_class_array(env);
                    if (parameters == NULL) {
                        (*env)->DeleteLocalRef(env, owner);
                        return NULL;
                    }
                    jobject constructor = (*env)->CallObjectMethod(env, owner, get_declared_constructor, parameters);
                    (*env)->DeleteLocalRef(env, owner);
                    (*env)->DeleteLocalRef(env, parameters);
                    return constructor;
                }

                jobject j2ll_rt_reflect_invoke(JNIEnv* env, jobject method, jobject target, jobject args) {
                    if (method == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Method.invoke receiver is null");
                        return NULL;
                    }
                    jclass method_class = (*env)->FindClass(env, "java/lang/reflect/Method");
                    if (method_class == NULL) {
                        return NULL;
                    }
                    jmethodID invoke = (*env)->GetMethodID(
                            env,
                            method_class,
                            "invoke",
                            "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
                    (*env)->DeleteLocalRef(env, method_class);
                    if (invoke == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, method, invoke, target, args);
                }

                jobject j2ll_rt_reflect_new_instance(JNIEnv* env, jobject constructor, jobject args) {
                    if (constructor == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Constructor.newInstance receiver is null");
                        return NULL;
                    }
                    jclass constructor_class = (*env)->FindClass(env, "java/lang/reflect/Constructor");
                    if (constructor_class == NULL) {
                        return NULL;
                    }
                    jmethodID new_instance = (*env)->GetMethodID(
                            env,
                            constructor_class,
                            "newInstance",
                            "([Ljava/lang/Object;)Ljava/lang/Object;");
                    (*env)->DeleteLocalRef(env, constructor_class);
                    if (new_instance == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, constructor, new_instance, args);
                }

                static jclass j2ll_reflection_field_class(JNIEnv* env) {
                    return (*env)->FindClass(env, "java/lang/reflect/Field");
                }

                jobject j2ll_rt_reflect_field_get(JNIEnv* env, jobject field, jobject target) {
                    if (field == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Field.get receiver is null");
                        return NULL;
                    }
                    jclass field_class = j2ll_reflection_field_class(env);
                    if (field_class == NULL) {
                        return NULL;
                    }
                    jmethodID get = (*env)->GetMethodID(
                            env,
                            field_class,
                            "get",
                            "(Ljava/lang/Object;)Ljava/lang/Object;");
                    (*env)->DeleteLocalRef(env, field_class);
                    if (get == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, field, get, target);
                }

                void j2ll_rt_reflect_field_set(JNIEnv* env, jobject field, jobject target, jobject value) {
                    if (field == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Field.set receiver is null");
                        return;
                    }
                    jclass field_class = j2ll_reflection_field_class(env);
                    if (field_class == NULL) {
                        return;
                    }
                    jmethodID set = (*env)->GetMethodID(
                            env,
                            field_class,
                            "set",
                            "(Ljava/lang/Object;Ljava/lang/Object;)V");
                    (*env)->DeleteLocalRef(env, field_class);
                    if (set == NULL) {
                        return;
                    }
                    (*env)->CallVoidMethod(env, field, set, target, value);
                }

                int32_t j2ll_rt_reflect_field_get_int(JNIEnv* env, jobject field, jobject target) {
                    if (field == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Field.getInt receiver is null");
                        return 0;
                    }
                    jclass field_class = j2ll_reflection_field_class(env);
                    if (field_class == NULL) {
                        return 0;
                    }
                    jmethodID get_int = (*env)->GetMethodID(
                            env,
                            field_class,
                            "getInt",
                            "(Ljava/lang/Object;)I");
                    (*env)->DeleteLocalRef(env, field_class);
                    if (get_int == NULL) {
                        return 0;
                    }
                    return (int32_t)(*env)->CallIntMethod(env, field, get_int, target);
                }

                void j2ll_rt_reflect_field_set_int(JNIEnv* env, jobject field, jobject target, int32_t value) {
                    if (field == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Field.setInt receiver is null");
                        return;
                    }
                    jclass field_class = j2ll_reflection_field_class(env);
                    if (field_class == NULL) {
                        return;
                    }
                    jmethodID set_int = (*env)->GetMethodID(
                            env,
                            field_class,
                            "setInt",
                            "(Ljava/lang/Object;I)V");
                    (*env)->DeleteLocalRef(env, field_class);
                    if (set_int == NULL) {
                        return;
                    }
                    (*env)->CallVoidMethod(env, field, set_int, target, (jint)value);
                }

                static int j2ll_jstring_equals_utf(JNIEnv* env, jstring value, const char* expected) {
                    if (value == NULL || expected == NULL) {
                        return 0;
                    }
                    const char* chars = (*env)->GetStringUTFChars(env, value, NULL);
                    if (chars == NULL) {
                        return 0;
                    }
                    int matches = strcmp(chars, expected) == 0;
                    (*env)->ReleaseStringUTFChars(env, value, chars);
                    return matches;
                }

                static int j2ll_field_matches_entry(JNIEnv* env, jobject field, const j2ll_reflection_field_entry* entry) {
                    jclass field_class = j2ll_reflection_field_class(env);
                    if (field_class == NULL) {
                        return 0;
                    }
                    jmethodID get_declaring_class = (*env)->GetMethodID(
                            env,
                            field_class,
                            "getDeclaringClass",
                            "()Ljava/lang/Class;");
                    jmethodID get_name = (*env)->GetMethodID(
                            env,
                            field_class,
                            "getName",
                            "()Ljava/lang/String;");
                    (*env)->DeleteLocalRef(env, field_class);
                    if (get_declaring_class == NULL || get_name == NULL) {
                        return 0;
                    }
                    jobject declaring_class = (*env)->CallObjectMethod(env, field, get_declaring_class);
                    if (declaring_class == NULL || (*env)->ExceptionCheck(env)) {
                        return 0;
                    }
                    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
                    if (class_class == NULL) {
                        (*env)->DeleteLocalRef(env, declaring_class);
                        return 0;
                    }
                    jmethodID get_class_name = (*env)->GetMethodID(env, class_class, "getName", "()Ljava/lang/String;");
                    (*env)->DeleteLocalRef(env, class_class);
                    if (get_class_name == NULL) {
                        (*env)->DeleteLocalRef(env, declaring_class);
                        return 0;
                    }
                    jstring owner_name = (jstring)(*env)->CallObjectMethod(env, declaring_class, get_class_name);
                    (*env)->DeleteLocalRef(env, declaring_class);
                    if (owner_name == NULL || (*env)->ExceptionCheck(env)) {
                        return 0;
                    }
                    jstring field_name = (jstring)(*env)->CallObjectMethod(env, field, get_name);
                    if (field_name == NULL || (*env)->ExceptionCheck(env)) {
                        (*env)->DeleteLocalRef(env, owner_name);
                        return 0;
                    }
                    char* dotted_owner = j2ll_dotted_class_name(entry->owner);
                    if (dotted_owner == NULL) {
                        (*env)->DeleteLocalRef(env, owner_name);
                        (*env)->DeleteLocalRef(env, field_name);
                        j2ll_throw_new(env, "java/lang/OutOfMemoryError", "failed to allocate unsafe field owner name");
                        return 0;
                    }
                    int matches = j2ll_jstring_equals_utf(env, owner_name, dotted_owner)
                            && j2ll_jstring_equals_utf(env, field_name, entry->name);
                    free(dotted_owner);
                    (*env)->DeleteLocalRef(env, owner_name);
                    (*env)->DeleteLocalRef(env, field_name);
                    return matches;
                }

                int64_t j2ll_rt_unsafe_object_field_offset(JNIEnv* env, jobject field) {
                    if (field == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Unsafe.objectFieldOffset field is null");
                        return 0;
                    }
                    for (size_t index = 0; index < sizeof(j2ll_reflection_field_table) / sizeof(j2ll_reflection_field_table[0]); index++) {
                        const j2ll_reflection_field_entry* entry = &j2ll_reflection_field_table[index];
                        if (entry->owner == NULL) {
                            continue;
                        }
                        if (j2ll_field_matches_entry(env, field, entry)) {
                            return entry->token;
                        }
                        if ((*env)->ExceptionCheck(env)) {
                            return 0;
                        }
                    }
                    j2ll_throw_new(env, "java/lang/IllegalArgumentException", "unsupported Unsafe field offset token");
                    return 0;
                }

                int64_t j2ll_rt_unsafe_static_field_offset(JNIEnv* env, jobject field) {
                    return j2ll_rt_unsafe_object_field_offset(env, field);
                }

                static const j2ll_reflection_field_entry* j2ll_find_unsafe_field(int64_t token) {
                    const j2ll_reflection_field_entry* entry = j2ll_find_reflection_field(token);
                    return entry != NULL && entry->owner != NULL ? entry : NULL;
                }

                static jfieldID j2ll_unsafe_int_field_id(JNIEnv* env, jobject target, int64_t token, jclass* target_class) {
                    if (target == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Unsafe field target is null");
                        return NULL;
                    }
                    const j2ll_reflection_field_entry* entry = j2ll_find_unsafe_field(token);
                    if (entry == NULL) {
                        j2ll_throw_new(env, "java/lang/IllegalArgumentException", "unknown Unsafe field token");
                        return NULL;
                    }
                    *target_class = (*env)->GetObjectClass(env, target);
                    if (*target_class == NULL) {
                        return NULL;
                    }
                    jfieldID field = (*env)->GetFieldID(env, *target_class, entry->name, "I");
                    if (field == NULL) {
                        (*env)->DeleteLocalRef(env, *target_class);
                        *target_class = NULL;
                    }
                    return field;
                }

                int32_t j2ll_rt_unsafe_get_int(JNIEnv* env, jobject target, int64_t token) {
                    jclass target_class = NULL;
                    jfieldID field = j2ll_unsafe_int_field_id(env, target, token, &target_class);
                    if (field == NULL) {
                        return 0;
                    }
                    jint value = (*env)->GetIntField(env, target, field);
                    (*env)->DeleteLocalRef(env, target_class);
                    return (int32_t)value;
                }

                void j2ll_rt_unsafe_put_int(JNIEnv* env, jobject target, int64_t token, int32_t value) {
                    jclass target_class = NULL;
                    jfieldID field = j2ll_unsafe_int_field_id(env, target, token, &target_class);
                    if (field == NULL) {
                        return;
                    }
                    (*env)->SetIntField(env, target, field, (jint)value);
                    (*env)->DeleteLocalRef(env, target_class);
                }

                int32_t j2ll_rt_unsafe_compare_and_swap_int(JNIEnv* env, jobject target, int64_t token, int32_t expected, int32_t update) {
                    jclass target_class = NULL;
                    jfieldID field = j2ll_unsafe_int_field_id(env, target, token, &target_class);
                    if (field == NULL) {
                        return 0;
                    }
                    if ((*env)->MonitorEnter(env, target) != JNI_OK) {
                        (*env)->DeleteLocalRef(env, target_class);
                        j2ll_throw_new(env, "java/lang/IllegalMonitorStateException", "Unsafe CAS monitor enter failed");
                        return 0;
                    }
                    jint current = (*env)->GetIntField(env, target, field);
                    int32_t success = 0;
                    if (!(*env)->ExceptionCheck(env) && current == (jint)expected) {
                        (*env)->SetIntField(env, target, field, (jint)update);
                        success = !(*env)->ExceptionCheck(env);
                    }
                    if ((*env)->MonitorExit(env, target) != JNI_OK && !(*env)->ExceptionCheck(env)) {
                        j2ll_throw_new(env, "java/lang/IllegalMonitorStateException", "Unsafe CAS monitor exit failed");
                        success = 0;
                    }
                    (*env)->DeleteLocalRef(env, target_class);
                    return success;
                }

                jobject j2ll_rt_unsafe_allocate_instance(JNIEnv* env, jclass cls) {
                    if (cls == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Unsafe.allocateInstance class is null");
                        return NULL;
                    }
                    return (*env)->AllocObject(env, cls);
                }

                """);
    }

    private void appendDispatchHelperSource(StringBuilder builder, List<Binding> bindings) {
        List<String> dispatchKeys = bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> java.util.stream.Stream.concat(
                        binding.dispatchKeys().stream(),
                        binding.constructorCallKeys().stream()))
                .distinct()
                .sorted()
                .toList();
        builder.append("""
                typedef struct {
                    int64_t token;
                    const char* owner;
                    const char* name;
                    const char* descriptor;
                } j2ll_method_entry;

                static const j2ll_method_entry j2ll_method_table[] = {
                """);
        for (String dispatchKey : dispatchKeys) {
            MethodParts parts = parseMethodKey(dispatchKey);
            builder.append("    { ")
                    .append(MethodIdentityToken.token(dispatchKey))
                    .append("LL, \"")
                    .append(escapeCString(parts.owner()))
                    .append("\", \"")
                    .append(escapeCString(parts.name()))
                    .append("\", \"")
                    .append(escapeCString(parts.descriptor()))
                    .append("\" },\n");
        }
        builder.append("    { 0LL, NULL, NULL, NULL },\n");
        builder.append("""
                };

                static const j2ll_method_entry* j2ll_find_method(int64_t token) {
                    for (size_t index = 0; index < sizeof(j2ll_method_table) / sizeof(j2ll_method_table[0]); index++) {
                        if (j2ll_method_table[index].name != NULL && j2ll_method_table[index].token == token) {
                            return &j2ll_method_table[index];
                        }
                    }
                    return NULL;
                }

                static int32_t j2ll_call_no_arg_i32(JNIEnv* env, jobject receiver, int64_t token) {
                    if (receiver == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "call receiver is null");
                        return 0;
                    }
                    const j2ll_method_entry* entry = j2ll_find_method(token);
                    if (entry == NULL) {
                        j2ll_throw_new(env, "java/lang/NoSuchMethodError", "unknown j2ll method token");
                        return 0;
                    }
                    jclass cls = (*env)->GetObjectClass(env, receiver);
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, entry->name, entry->descriptor);
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0;
                    }
                    return (*env)->CallIntMethod(env, receiver, method);
                }

                int32_t j2ll_rt_call_virtual_i32(JNIEnv* env, jobject receiver, int64_t token, jobject args) {
                    (void)args;
                    return j2ll_call_no_arg_i32(env, receiver, token);
                }

                int32_t j2ll_rt_call_interface_i32(JNIEnv* env, jobject receiver, int64_t token, jobject args) {
                    (void)args;
                    return j2ll_call_no_arg_i32(env, receiver, token);
                }

                void j2ll_rt_call_constructor_void(JNIEnv* env, jobject receiver, int64_t token) {
                    if (receiver == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "constructor receiver is null");
                        return;
                    }
                    const j2ll_method_entry* entry = j2ll_find_method(token);
                    if (entry == NULL) {
                        j2ll_throw_new(env, "java/lang/NoSuchMethodError", "unknown j2ll constructor token");
                        return;
                    }
                    jclass cls = (*env)->FindClass(env, entry->owner);
                    if (cls == NULL) {
                        return;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, entry->name, entry->descriptor);
                    if (method == NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                        return;
                    }
                    (*env)->CallNonvirtualVoidMethod(env, receiver, cls, method);
                    (*env)->DeleteLocalRef(env, cls);
                }

                void j2ll_rt_call_constructor_void_i32_i32(JNIEnv* env, jobject receiver, int64_t token, int32_t arg0, int32_t arg1) {
                    if (receiver == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "constructor receiver is null");
                        return;
                    }
                    const j2ll_method_entry* entry = j2ll_find_method(token);
                    if (entry == NULL) {
                        j2ll_throw_new(env, "java/lang/NoSuchMethodError", "unknown j2ll constructor token");
                        return;
                    }
                    jclass cls = (*env)->FindClass(env, entry->owner);
                    if (cls == NULL) {
                        return;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, entry->name, entry->descriptor);
                    if (method == NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                        return;
                    }
                    (*env)->CallNonvirtualVoidMethod(env, receiver, cls, method, (jint)arg0, (jint)arg1);
                    (*env)->DeleteLocalRef(env, cls);
                }

                """);
    }

    private void appendFieldHelperSource(StringBuilder builder, List<Binding> bindings) {
        List<String> fieldKeys = bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.fieldKeys().stream())
                .distinct()
                .sorted()
                .toList();
        if (fieldKeys.isEmpty()) {
            return;
        }
        builder.append("""
                typedef struct {
                    int64_t token;
                    const char* owner;
                    const char* name;
                    const char* descriptor;
                } j2ll_field_entry;

                static const j2ll_field_entry j2ll_field_table[] = {
                """);
        for (String fieldKey : fieldKeys) {
            FieldParts parts = parseFieldKey(fieldKey);
            builder.append("    { ")
                    .append(FieldIdentityToken.token(fieldKey))
                    .append("LL, \"")
                    .append(escapeCString(parts.owner()))
                    .append("\", \"")
                    .append(escapeCString(parts.name()))
                    .append("\", \"")
                    .append(escapeCString(parts.descriptor()))
                    .append("\" },\n");
        }
        builder.append("""
                };

                static const j2ll_field_entry* j2ll_find_field(int64_t token) {
                    for (size_t index = 0; index < sizeof(j2ll_field_table) / sizeof(j2ll_field_table[0]); index++) {
                        if (j2ll_field_table[index].token == token) {
                            return &j2ll_field_table[index];
                        }
                    }
                    return NULL;
                }

                static jclass j2ll_static_field_class(JNIEnv* env, jclass owner, const j2ll_field_entry* entry, int* local_ref) {
                    *local_ref = 0;
                    if (owner != NULL) {
                        return owner;
                    }
                    jclass cls = (*env)->FindClass(env, entry->owner);
                    if (cls != NULL) {
                        *local_ref = 1;
                    }
                    return cls;
                }

                static jfieldID j2ll_static_field_id(JNIEnv* env, jclass owner, int64_t token, jclass* resolved_class, int* local_ref) {
                    const j2ll_field_entry* entry = j2ll_find_field(token);
                    if (entry == NULL) {
                        j2ll_throw_new(env, "java/lang/NoSuchFieldError", "unknown j2ll field token");
                        return NULL;
                    }
                    *resolved_class = j2ll_static_field_class(env, owner, entry, local_ref);
                    if (*resolved_class == NULL) {
                        return NULL;
                    }
                    return (*env)->GetStaticFieldID(env, *resolved_class, entry->name, entry->descriptor);
                }

                static jfieldID j2ll_instance_field_id(JNIEnv* env, jobject self, int64_t token, jclass* resolved_class) {
                    if (self == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "field receiver is null");
                        return NULL;
                    }
                    const j2ll_field_entry* entry = j2ll_find_field(token);
                    if (entry == NULL) {
                        j2ll_throw_new(env, "java/lang/NoSuchFieldError", "unknown j2ll field token");
                        return NULL;
                    }
                    *resolved_class = (*env)->GetObjectClass(env, self);
                    if (*resolved_class == NULL) {
                        return NULL;
                    }
                    return (*env)->GetFieldID(env, *resolved_class, entry->name, entry->descriptor);
                }

                int32_t j2ll_rt_field_get_static_i32(JNIEnv* env, jclass owner, int64_t token) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field == NULL) {
                        return 0;
                    }
                    jint value = (*env)->GetStaticIntField(env, cls, field);
                    if (local_ref) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                    return value;
                }

                void j2ll_rt_field_put_static_i32(JNIEnv* env, jclass owner, int64_t token, int32_t value) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field != NULL) {
                        (*env)->SetStaticIntField(env, cls, field, value);
                    }
                    if (local_ref && cls != NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                int64_t j2ll_rt_field_get_static_i64(JNIEnv* env, jclass owner, int64_t token) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field == NULL) {
                        return 0;
                    }
                    jlong value = (*env)->GetStaticLongField(env, cls, field);
                    if (local_ref) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                    return value;
                }

                void j2ll_rt_field_put_static_i64(JNIEnv* env, jclass owner, int64_t token, int64_t value) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field != NULL) {
                        (*env)->SetStaticLongField(env, cls, field, value);
                    }
                    if (local_ref && cls != NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                jobject j2ll_rt_field_get_static_ref(JNIEnv* env, jclass owner, int64_t token) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field == NULL) {
                        return NULL;
                    }
                    jobject value = (*env)->GetStaticObjectField(env, cls, field);
                    if (local_ref) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                    return value;
                }

                void j2ll_rt_field_put_static_ref(JNIEnv* env, jclass owner, int64_t token, jobject value) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field != NULL) {
                        (*env)->SetStaticObjectField(env, cls, field, value);
                    }
                    if (local_ref && cls != NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                int32_t j2ll_rt_field_get_field_i32(JNIEnv* env, jobject self, int64_t token) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field == NULL) {
                        return 0;
                    }
                    jint value = (*env)->GetIntField(env, self, field);
                    (*env)->DeleteLocalRef(env, cls);
                    return value;
                }

                void j2ll_rt_field_put_field_i32(JNIEnv* env, jobject self, int64_t token, int32_t value) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field != NULL) {
                        (*env)->SetIntField(env, self, field, value);
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                int64_t j2ll_rt_field_get_field_i64(JNIEnv* env, jobject self, int64_t token) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field == NULL) {
                        return 0;
                    }
                    jlong value = (*env)->GetLongField(env, self, field);
                    (*env)->DeleteLocalRef(env, cls);
                    return value;
                }

                void j2ll_rt_field_put_field_i64(JNIEnv* env, jobject self, int64_t token, int64_t value) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field != NULL) {
                        (*env)->SetLongField(env, self, field, value);
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                jobject j2ll_rt_field_get_field_ref(JNIEnv* env, jobject self, int64_t token) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field == NULL) {
                        return NULL;
                    }
                    jobject value = (*env)->GetObjectField(env, self, field);
                    (*env)->DeleteLocalRef(env, cls);
                    return value;
                }

                void j2ll_rt_field_put_field_ref(JNIEnv* env, jobject self, int64_t token, jobject value) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field != NULL) {
                        (*env)->SetObjectField(env, self, field, value);
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                """);
    }

    private void appendOwnerRegistration(StringBuilder builder, NativeRegistrationPlan supportedPlan) {
        for (String owner : entriesByOwner(supportedPlan).keySet()) {
            String registerSymbol = "j2ll_register_" + safeSymbol(owner);
            String tableName = "j2ll_natives_" + safeSymbol(owner);
            builder.append("static jint ")
                    .append(registerSymbol)
                    .append("(JNIEnv* env) {\n")
                    .append("    jclass owner = j2ll_class_for_registration(env, \"")
                    .append(escapeCString(owner))
                    .append("\");\n")
                    .append("    if (owner == NULL) {\n")
                    .append("        return JNI_ERR;\n")
                    .append("    }\n")
                    .append("    if ((*env)->RegisterNatives(env, owner, ")
                    .append(tableName)
                    .append(", ")
                    .append(tableName)
                    .append("_count) != 0) {\n")
                    .append("        (*env)->DeleteLocalRef(env, owner);\n")
                    .append("        return JNI_ERR;\n")
                    .append("    }\n")
                    .append("    (*env)->DeleteLocalRef(env, owner);\n")
                    .append("    return JNI_OK;\n")
                    .append("}\n\n");
        }
        builder.append("JNIEXPORT jint JNICALL j2ll_register(JavaVM* vm) {\n")
                .append("    JNIEnv* env = NULL;\n")
                .append("    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_8) != JNI_OK) {\n")
                .append("        return JNI_ERR;\n")
                .append("    }\n");
        for (String owner : entriesByOwner(supportedPlan).keySet()) {
            builder.append("    if (j2ll_register_")
                    .append(safeSymbol(owner))
                    .append("(env) != JNI_OK) {\n")
                    .append("        return JNI_ERR;\n")
                    .append("    }\n");
        }
        builder.append("    return JNI_VERSION_1_8;\n")
                .append("}\n\n")
                .append("JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {\n")
                .append("    (void)reserved;\n")
                .append("    return j2ll_register(vm);\n")
                .append("}\n");
    }

    private FallbackClass fallbackSubstringClass(Binding binding) {
        String originalMethodId = artifactPath.methodId(
                binding.decision().method().owner(),
                binding.decision().method().name(),
                binding.decision().method().descriptor());
        FallbackHelperClass helperClass = fallbackHelperClassFactory.create(
                originalMethodId,
                binding.decision().method().methodKey(),
                binding.decision().method().owner());
        EncodedFallbackBlob encoded = fallbackBlobCodec.encode(
                helperClass.bytes(),
                originalMethodId + "\n" + binding.decision().method().methodKey());
        String symbol = "j2ll_define_fallback_" + safeSymbol(binding.entry().nativeSymbol());
        StringBuilder extra = new StringBuilder();
        extra.append("static const unsigned char ")
                .append(symbol)
                .append("_encoded[] = {");
        appendCByteArray(extra, encoded.encodedBytes());
        extra.append("\n};\n")
                .append("static const unsigned char ")
                .append(symbol)
                .append("_key[] = {");
        appendCByteArray(extra, encoded.keyBytes());
        extra.append("\n};\n")
                .append("static jobject ")
                .append(symbol)
                .append("_loaders[16] = { NULL };\n")
                .append("static jclass ")
                .append(symbol)
                .append("_classes[16] = { NULL };\n")
                .append("static unsigned char* ")
                .append(symbol)
                .append("_decode(JNIEnv* env, size_t* decoded_length) {\n")
                .append("    size_t encoded_length = sizeof(")
                .append(symbol)
                .append("_encoded);\n")
                .append("    if (!j2ll_verify_sha256_hex(env, ")
                .append(symbol)
                .append("_encoded, encoded_length, \"")
                .append(encoded.encodedSha256())
                .append("\")) {\n")
                .append("        if (!(*env)->ExceptionCheck(env)) {\n")
                .append("            j2ll_throw_new(env, \"java/lang/SecurityException\", \"fallback encoded SHA-256 mismatch\");\n")
                .append("        }\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    unsigned char* compressed = (unsigned char*)malloc(encoded_length);\n")
                .append("    if (compressed == NULL) {\n")
                .append("        j2ll_throw_new(env, \"java/lang/OutOfMemoryError\", \"fallback blob decode allocation failed\");\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    for (size_t index = 0; index < encoded_length; index++) {\n")
                .append("        unsigned char stream = (unsigned char)(")
                .append(symbol)
                .append("_key[index % sizeof(")
                .append(symbol)
                .append("_key)] ^ ((index * 31u + (index >> 3)) & 0xffu));\n")
                .append("        compressed[index] = (unsigned char)(")
                .append(symbol)
                .append("_encoded[index] ^ stream);\n")
                .append("    }\n")
                .append("    if (encoded_length < 4) {\n")
                .append("        free(compressed);\n")
                .append("        j2ll_throw_new(env, \"java/lang/ClassFormatError\", \"fallback blob is truncated\");\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    size_t original_length = ((size_t)compressed[0] << 24) | ((size_t)compressed[1] << 16) | ((size_t)compressed[2] << 8) | (size_t)compressed[3];\n")
                .append("    unsigned char* decoded = (unsigned char*)malloc(original_length == 0 ? 1 : original_length);\n")
                .append("    if (decoded == NULL) {\n")
                .append("        free(compressed);\n")
                .append("        j2ll_throw_new(env, \"java/lang/OutOfMemoryError\", \"fallback class allocation failed\");\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    size_t write = 0;\n")
                .append("    for (size_t index = 4; index < encoded_length; index += 2) {\n")
                .append("        if (index + 1 >= encoded_length || compressed[index] == 0 || write + compressed[index] > original_length) {\n")
                .append("            free(compressed);\n")
                .append("            free(decoded);\n")
                .append("            j2ll_throw_new(env, \"java/lang/ClassFormatError\", \"fallback blob decode failed\");\n")
                .append("            return NULL;\n")
                .append("        }\n")
                .append("        memset(decoded + write, compressed[index + 1], compressed[index]);\n")
                .append("        write += compressed[index];\n")
                .append("    }\n")
                .append("    free(compressed);\n")
                .append("    if (write != original_length) {\n")
                .append("        free(decoded);\n")
                .append("        j2ll_throw_new(env, \"java/lang/ClassFormatError\", \"fallback blob length mismatch\");\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    if (!j2ll_verify_sha256_hex(env, decoded, original_length, \"")
                .append(encoded.originalSha256())
                .append("\")) {\n")
                .append("        free(decoded);\n")
                .append("        if (!(*env)->ExceptionCheck(env)) {\n")
                .append("            j2ll_throw_new(env, \"java/lang/SecurityException\", \"fallback decoded SHA-256 mismatch\");\n")
                .append("        }\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    *decoded_length = original_length;\n")
                .append("    return decoded;\n")
                .append("}\n")
                .append("static jclass ")
                .append(symbol)
                .append("(JNIEnv* env, jclass owner) {\n")
                .append("    jobject loader = j2ll_owner_class_loader(env, owner);\n")
                .append("    if ((*env)->ExceptionCheck(env)) {\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    for (size_t index = 0; index < 16; index++) {\n")
                .append("        if (")
                .append(symbol)
                .append("_classes[index] != NULL && (*env)->IsSameObject(env, ")
                .append(symbol)
                .append("_loaders[index], loader)) {\n")
                .append("            if (loader != NULL) {\n")
                .append("                (*env)->DeleteLocalRef(env, loader);\n")
                .append("            }\n")
                .append("            return ")
                .append(symbol)
                .append("_classes[index];\n")
                .append("        }\n")
                .append("    }\n")
                .append("    size_t decoded_length = 0;\n")
                .append("    unsigned char* decoded = ")
                .append(symbol)
                .append("_decode(env, &decoded_length);\n")
                .append("    if (decoded == NULL) {\n")
                .append("        if (loader != NULL) {\n")
                .append("            (*env)->DeleteLocalRef(env, loader);\n")
                .append("        }\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    jclass local = (*env)->DefineClass(env, \"")
                .append(helperClass.internalName())
                .append("\", loader, (const jbyte*)decoded, (jsize)decoded_length);\n")
                .append("    free(decoded);\n")
                .append("    if (local == NULL) {\n")
                .append("        if (loader != NULL) {\n")
                .append("            (*env)->DeleteLocalRef(env, loader);\n")
                .append("        }\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    jclass global_class = (jclass)(*env)->NewGlobalRef(env, local);\n")
                .append("    if (global_class == NULL) {\n")
                .append("        (*env)->DeleteLocalRef(env, local);\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    jobject global_loader = loader == NULL ? NULL : (*env)->NewGlobalRef(env, loader);\n")
                .append("    if (loader != NULL && global_loader == NULL) {\n")
                .append("        (*env)->DeleteGlobalRef(env, global_class);\n")
                .append("        (*env)->DeleteLocalRef(env, local);\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    for (size_t index = 0; index < 16; index++) {\n")
                .append("        if (")
                .append(symbol)
                .append("_classes[index] == NULL) {\n")
                .append("            ")
                .append(symbol)
                .append("_loaders[index] = global_loader;\n")
                .append("            ")
                .append(symbol)
                .append("_classes[index] = global_class;\n")
                .append("            break;\n")
                .append("        }\n")
                .append("    }\n")
                .append("    (*env)->DeleteLocalRef(env, local);\n")
                .append("    if (loader != NULL) {\n")
                .append("        (*env)->DeleteLocalRef(env, loader);\n")
                .append("    }\n")
                .append("    return global_class;\n")
                .append("}\n\n");
        return new FallbackClass(helperClass.internalName(), helperClass.bytes(), extra.toString());
    }

    private void appendCByteArray(StringBuilder builder, byte[] bytes) {
        for (int index = 0; index < bytes.length; index++) {
            if (index % 12 == 0) {
                builder.append("\n    ");
            }
            builder.append(String.format(java.util.Locale.ROOT, "0x%02x, ", bytes[index] & 0xff));
        }
    }

    private Map<String, List<NativeRegistrationEntry>> entriesByOwner(NativeRegistrationPlan plan) {
        Map<String, List<NativeRegistrationEntry>> byOwner = new TreeMap<>();
        for (NativeRegistrationEntry entry : plan.entries()) {
            byOwner.computeIfAbsent(entry.registrationOwner(), ignored -> new ArrayList<>()).add(entry);
        }
        return byOwner;
    }

    private String safeSymbol(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')) {
                result.append(ch);
            } else {
                result.append('_');
            }
        }
        return result.toString();
    }

    private String escapeCString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private long javaStringHashUnsigned(String value) {
        return Integer.toUnsignedLong(value.hashCode());
    }

    private FieldParts parseFieldKey(String fieldKey) {
        int ownerEnd = fieldKey.indexOf('#');
        int descriptorStart = fieldKey.indexOf('!');
        if (ownerEnd < 0 || descriptorStart < ownerEnd) {
            throw new IllegalArgumentException("invalid field key: " + fieldKey);
        }
        return new FieldParts(
                fieldKey.substring(0, ownerEnd),
                fieldKey.substring(ownerEnd + 1, descriptorStart),
                fieldKey.substring(descriptorStart + 1));
    }

    private ClassParts parseAllocationKey(String allocationKey) {
        if (allocationKey.startsWith("object:")) {
            String internalName = allocationKey.substring("object:".length());
            return new ClassParts("L" + internalName + ";", internalName);
        }
        if (allocationKey.startsWith("referenceArray:")) {
            String component = allocationKey.substring("referenceArray:".length());
            if (component.startsWith("[")) {
                return new ClassParts(component, component);
            }
            return new ClassParts("L" + component + ";", component);
        }
        throw new IllegalArgumentException("invalid allocation key: " + allocationKey);
    }

    private ClassParts parseTypeCheckKey(String typeCheckKey) {
        if (typeCheckKey.startsWith("checkcast:")) {
            return parseTypeIdentity(typeCheckKey.substring("checkcast:".length()));
        }
        if (typeCheckKey.startsWith("instanceof:")) {
            return parseTypeIdentity(typeCheckKey.substring("instanceof:".length()));
        }
        throw new IllegalArgumentException("invalid type check key: " + typeCheckKey);
    }

    private ClassParts parseClassObjectKey(String classObjectKey) {
        if (!classObjectKey.startsWith("class:")) {
            throw new IllegalArgumentException("invalid class object key: " + classObjectKey);
        }
        return parseTypeIdentity(classObjectKey.substring("class:".length()));
    }

    private ClassParts parseTypeIdentity(String internalOrDescriptor) {
        if (internalOrDescriptor.startsWith("[")) {
            return new ClassParts(internalOrDescriptor, internalOrDescriptor);
        }
        if (internalOrDescriptor.startsWith("L") && internalOrDescriptor.endsWith(";")) {
            String internalName = internalOrDescriptor.substring(1, internalOrDescriptor.length() - 1);
            return new ClassParts(internalOrDescriptor, internalName);
        }
        return new ClassParts("L" + internalOrDescriptor + ";", internalOrDescriptor);
    }

    private long stableClassObjectToken(String classDescriptor) {
        return Integer.toUnsignedLong(classDescriptor.hashCode());
    }

    private MethodParts parseMethodKey(String methodKey) {
        int ownerEnd = methodKey.indexOf('#');
        int descriptorStart = methodKey.indexOf('!');
        if (ownerEnd < 0 || descriptorStart < ownerEnd) {
            throw new IllegalArgumentException("invalid method key: " + methodKey);
        }
        return new MethodParts(
                methodKey.substring(0, ownerEnd),
                methodKey.substring(ownerEnd + 1, descriptorStart),
                methodKey.substring(descriptorStart + 1));
    }

    private MethodParts parseReflectionMethodKey(String metadataKey) {
        if (metadataKey.startsWith("method:")) {
            return parseMethodKey(metadataKey.substring("method:".length()));
        }
        if (metadataKey.startsWith("constructor:")) {
            return parseMethodKey(metadataKey.substring("constructor:".length()));
        }
        throw new IllegalArgumentException("invalid reflection method metadata key: " + metadataKey);
    }

    private FieldParts parseReflectionFieldKey(String metadataKey) {
        if (!metadataKey.startsWith("field:")) {
            throw new IllegalArgumentException("invalid reflection field metadata key: " + metadataKey);
        }
        String key = metadataKey.substring("field:".length());
        int ownerEnd = key.indexOf('#');
        if (ownerEnd < 0) {
            throw new IllegalArgumentException("invalid reflection field metadata key: " + metadataKey);
        }
        return new FieldParts(key.substring(0, ownerEnd), key.substring(ownerEnd + 1), "");
    }

    private String safeFallbackSegment(String value) {
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
                    result.append(Integer.toHexString(ch).toLowerCase(java.util.Locale.ROOT));
                    result.append('_');
                }
            }
        }
        return result.toString();
    }

    private record Binding(
            NativeRegistrationEntry entry,
            MethodRewriteDecision decision,
            NativeImplementationPath path,
            Optional<String> llvmFunctionSymbol,
            boolean passesJniEnv,
            boolean passesOwnerClass,
            List<String> fieldKeys,
            List<String> directCallTargets,
            List<String> allocationKeys,
            List<String> typeCheckKeys,
            List<String> classObjectKeys,
            List<String> runtimeMetadataKeys,
            List<String> constructorCallKeys,
            List<String> dispatchKeys,
            List<String> stringHelperSymbols,
            Optional<IrMethod> templateIrMethod,
            JniMethodDescriptor descriptor) {
    }

    private record FallbackClass(String internalName, byte[] bytes, String extraSource) {
    }

    private record FieldParts(String owner, String name, String descriptor) {
    }

    private record ClassParts(String identity, String jniName) {
    }

    private record MethodParts(String owner, String name, String descriptor) {
    }
}
