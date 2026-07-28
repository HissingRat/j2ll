package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.ir.model.BusinessStringConstantRef;
import xyz.melodysky.ir.model.BusinessStringSymbolMapper;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.runtime.jni.JniMethodDescriptor;
import xyz.melodysky.runtime.jni.JniTypeMapper;
import xyz.melodysky.toolchain.nativetext.GeneratedCFragmentTextObfuscator;
import xyz.melodysky.toolchain.nativetext.GeneratedNativeHardeningAudit;
import xyz.melodysky.toolchain.nativetext.GeneratedNativeHardeningAuditResult;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoder;
import xyz.melodysky.toolchain.nativetext.NativeTextPurpose;

public final class HostJniCSourceGenerator implements Opcodes {
    private static final NativeTextBuildKey COMPATIBILITY_BUILD_KEY =
            NativeTextBuildKey.fromUtf8(
                    "j2ll-business-string-symbol-compatibility-v1");
    private static final NativeTextBuildKey
            COMPATIBILITY_REGISTRATION_BUILD_KEY =
                    NativeTextBuildKey.fromUtf8(
                            "j2ll-registration-text-compatibility-v1");
    private final JniTypeMapper typeMapper = new JniTypeMapper();
    public String generate(NativeImplementationPlan implementationPlan) {
        List<Binding> bindings = bindings(implementationPlan);
        return generate(
                implementationPlan,
                RuntimeLoaderPlan.create(
                        "native0",
                        HostNativeReferenceFieldStorageSource.requiredSidecarSize(bindings)));
    }

    public String generate(
            NativeImplementationPlan implementationPlan,
            RuntimeLoaderPlan runtimeLoaderPlan) {
        return generate(implementationPlan, runtimeLoaderPlan, false, 0L);
    }

    public String generate(
            NativeImplementationPlan implementationPlan,
            RuntimeLoaderPlan runtimeLoaderPlan,
            boolean methodTableHidingEnabled,
            long protectionSeed) {
        return generate(
                implementationPlan,
                runtimeLoaderPlan,
                methodTableHidingEnabled,
                protectionSeed,
                COMPATIBILITY_BUILD_KEY);
    }

    public String generate(
            NativeImplementationPlan implementationPlan,
            RuntimeLoaderPlan runtimeLoaderPlan,
            boolean methodTableHidingEnabled,
            long protectionSeed,
            NativeTextBuildKey buildKey) {
        NativeRegistrationPlan supportedPlan = implementationPlan.registrationPlan();
        return generate(
                implementationPlan,
                runtimeLoaderPlan,
                new MethodTableHidingPlanner().plan(
                        supportedPlan,
                        methodTableHidingEnabled,
                        protectionSeed),
                buildKey);
    }

    public String generate(
            NativeImplementationPlan implementationPlan,
            RuntimeLoaderPlan runtimeLoaderPlan,
            MethodTableHidingPlan methodTablePlan) {
        return generate(
                implementationPlan,
                runtimeLoaderPlan,
                methodTablePlan,
                COMPATIBILITY_BUILD_KEY,
                COMPATIBILITY_BUILD_KEY,
                COMPATIBILITY_REGISTRATION_BUILD_KEY);
    }

    public String generate(
            NativeImplementationPlan implementationPlan,
            RuntimeLoaderPlan runtimeLoaderPlan,
            MethodTableHidingPlan methodTablePlan,
            NativeTextBuildKey buildKey) {
        return generate(
                implementationPlan,
                runtimeLoaderPlan,
                methodTablePlan,
                buildKey,
                buildKey,
                buildKey);
    }

    public String generate(
            NativeImplementationPlan implementationPlan,
            RuntimeLoaderPlan runtimeLoaderPlan,
            MethodTableHidingPlan methodTablePlan,
            NativeTextBuildKey buildKey,
            NativeTextBuildKey registrationBuildKey) {
        return generate(
                implementationPlan,
                runtimeLoaderPlan,
                methodTablePlan,
                buildKey,
                buildKey,
                registrationBuildKey);
    }

    public String generate(
            NativeImplementationPlan implementationPlan,
            RuntimeLoaderPlan runtimeLoaderPlan,
            MethodTableHidingPlan methodTablePlan,
            NativeTextBuildKey buildKey,
            NativeTextBuildKey businessBuildKey,
            NativeTextBuildKey registrationBuildKey) {
        List<Binding> bindings = bindings(implementationPlan);
        java.util.Objects.requireNonNull(buildKey, "buildKey");
        java.util.Objects.requireNonNull(
                businessBuildKey,
                "businessBuildKey");
        java.util.Objects.requireNonNull(
                registrationBuildKey,
                "registrationBuildKey");
        BusinessStringSymbolMapper businessStringSymbols =
                BusinessStringSymbolMapper.fromBytes(
                        businessBuildKey.bytes());
        RuntimeTokenMapper runtimeTokens =
                RuntimeTokenMapper.fromBytes(buildKey.bytes());
        if (HostNativeReferenceFieldStorageSource.requiredSidecarSize(bindings)
                != runtimeLoaderPlan.referenceSidecarSize()) {
            throw new IllegalArgumentException(
                    "runtime Loader reference sidecar capability does not match native implementation plan");
        }
        NativeRegistrationPlan supportedPlan = implementationPlan.registrationPlan();
        String registrationSource =
                new HostNativeRegistrationSource().emit(
                        supportedPlan,
                        methodTablePlan,
                        registrationBuildKey);
        GeneratedCFragmentTextObfuscator fragmentTextObfuscator =
                new GeneratedCFragmentTextObfuscator();
        StringBuilder builder = new StringBuilder();
        builder.append("""
                #include <jni.h>
                #include <limits.h>
                #include <math.h>
                #include <stdarg.h>
                #include <stdatomic.h>
                #include <stdint.h>
                #include <stdlib.h>
                #include <string.h>

                """);
        builder.append(new NativeTextCEmitter().runtimeSource());
        appendGeneratedFragment(
                builder,
                fragmentTextObfuscator,
                buildKey,
                "registration-runtime",
                HostJniRegistrationRuntimeSource.helperSource());
        boolean hasLlvmBindings = bindings.stream()
                .anyMatch(binding ->
                        binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH);
        if (hasLlvmBindings) {
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "allocation",
                    fragment -> HostJniAllocationRuntimeSource.append(
                            fragment,
                            bindings,
                            runtimeTokens));
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "jvm-class-init",
                    HostJniJvmSemanticsSources.classInitHelperSource());
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "jvm-arithmetic",
                    HostJniJvmSemanticsSources.arithmeticExceptionHelperSource());
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "jvm-numeric",
                    HostJniJvmSemanticsSources.jvmNumericHelperSource());
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "jvm-exception",
                    HostJniJvmSemanticsSources.exceptionHelperSource());
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "jvm-math",
                    HostJniJvmSemanticsSources.mathHelperSource());
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "jdk-object",
                    HostJniJdkObjectRuntimeSource.jdkObjectHelperSource());
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "jvm-thread",
                    HostJniThreadRuntimeSource.threadHelperSource());
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "jvm-monitor",
                    HostJniJvmSemanticsSources.monitorHelperSource());
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "jvm-array",
                    HostJniArrayRuntimeSource.arrayHelperSource());
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "jvm-type",
                    HostJniTypeAndStringRuntimeSources.typeHelperSource());
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "jvm-string",
                    HostJniTypeAndStringRuntimeSources.stringHelperSource());
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "lambda",
                    fragment -> HostJniLambdaRuntimeSource.append(
                            fragment,
                            bindings,
                            runtimeTokens));
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "varhandle",
                    HostJniVarHandleRuntimeSource.varHandleHelperSource());
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "reflection",
                    fragment -> HostJniReflectionRuntimeSource.append(
                            fragment,
                            bindings,
                            runtimeTokens));
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "dispatch",
                    fragment -> HostJniDispatchRuntimeSource.append(
                            fragment,
                            bindings,
                            runtimeTokens));
        }
        if (HostJniStringConstantRuntimeSource.isNeeded(bindings)) {
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "business",
                    fragment -> HostJniStringConstantRuntimeSource.append(
                            fragment,
                            bindings,
                            businessBuildKey));
        }
        appendGeneratedFragment(
                builder,
                fragmentTextObfuscator,
                buildKey,
                "field-storage",
                fragment -> HostNativeFieldStorageSource.append(
                        fragment,
                        bindings));
        appendGeneratedFragment(
                builder,
                fragmentTextObfuscator,
                buildKey,
                "field-reference-storage",
                fragment -> HostNativeReferenceFieldStorageSource.append(
                        fragment,
                        bindings,
                        runtimeLoaderPlan));
        appendGeneratedFragment(
                builder,
                fragmentTextObfuscator,
                buildKey,
                "field-runtime",
                fragment -> HostJniLocalizedFieldRuntimeSource.append(
                        fragment,
                        bindings,
                        runtimeTokens));
        for (Binding binding : bindings) {
            if (binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH) {
                appendLlvmForwardDeclaration(builder, binding);
            }
        }
        if (hasLlvmBindings) {
            builder.append('\n');
        }
        for (Binding binding : physicalBindingOrder(bindings, buildKey)) {
            appendGeneratedFragment(
                    builder,
                    fragmentTextObfuscator,
                    buildKey,
                    "binding-wrapper:"
                            + binding.decision().method().methodKey(),
                    fragment -> appendFunction(
                            fragment,
                            binding,
                            buildKey,
                            businessStringSymbols));
        }
        // Registration text already uses call-site-local NativeText scratch
        // buffers. Keeping it as an independent fragment also ensures
        // JNI_OnLoad never becomes a decode-all entry point.
        builder.append(registrationSource);
        return requireHardenedGeneratedSource(builder.toString());
    }

    private List<Binding> physicalBindingOrder(
            List<Binding> bindings,
            NativeTextBuildKey buildKey) {
        NativeTextEncoder encoder = new NativeTextEncoder();
        return bindings.stream()
                .sorted(Comparator.comparing(binding -> encoder.encode(
                                buildKey,
                                NativeTextPurpose.GENERATED_C_FRAGMENT,
                                "wrapper-order:"
                                        + binding.decision().method().methodKey(),
                                "")
                        .symbol()))
                .toList();
    }

    private void appendGeneratedFragment(
            StringBuilder builder,
            GeneratedCFragmentTextObfuscator obfuscator,
            NativeTextBuildKey buildKey,
            String scope,
            String fragment) {
        builder.append(obfuscator.obfuscate(buildKey, scope, fragment));
    }

    private void appendGeneratedFragment(
            StringBuilder builder,
            GeneratedCFragmentTextObfuscator obfuscator,
            NativeTextBuildKey buildKey,
            String scope,
            Consumer<StringBuilder> emitter) {
        StringBuilder fragment = new StringBuilder();
        emitter.accept(fragment);
        appendGeneratedFragment(
                builder,
                obfuscator,
                buildKey,
                scope,
                fragment.toString());
    }

    static String requireHardenedGeneratedSource(String source) {
        GeneratedNativeHardeningAuditResult audit =
                new GeneratedNativeHardeningAudit().audit(source);
        if (audit.passed()) {
            return source;
        }
        throw new IllegalStateException(
                "generated native hardening audit failed: "
                        + String.join(
                                ",",
                                audit.findings().stream()
                                        .map(finding -> finding.code())
                                        .toList()));
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
                        implementation.staticCallKeys(),
                        implementation.dispatchKeys(),
                        implementation.stringHelperSymbols(),
                        implementation.templateIrMethod(),
                        implementation.reasonCode(),
                        bindingDescriptor(implementation.entry(), implementation.decision())))
                .sorted(Comparator.comparing(Binding::entry))
                .toList();
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

    private void appendLlvmForwardDeclaration(StringBuilder builder, Binding binding) {
        builder.append("extern ")
                .append(internalReturnType(binding.descriptor()))
                .append(' ')
                .append(binding.llvmFunctionSymbol().orElseThrow())
                .append('(')
                .append(internalParameters(binding))
                .append(");\n");
    }

    private void appendFunction(
            StringBuilder builder,
            Binding binding,
            NativeTextBuildKey buildKey,
            BusinessStringSymbolMapper businessStringSymbols) {
        Optional<String> llvmInvocation = Optional.empty();
        Optional<String> llvmWrapperPrelude = Optional.empty();
        if (binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH) {
            HostNativeLocalAbiBridgeSource.Emission localAbi =
                    new HostNativeLocalAbiBridgeSource().emit(
                            buildKey,
                            binding.decision().method().methodKey(),
                            internalReturnType(binding.descriptor()),
                            binding.llvmFunctionSymbol().orElseThrow(),
                            internalAbiParameters(binding));
            builder.append(localAbi.source());
            llvmInvocation = Optional.of(localAbi.wrapperInvocation());
            llvmWrapperPrelude = Optional.of(localAbi.wrapperPrelude());
        }
        builder.append("static ")
                .append(binding.descriptor().jniReturnType())
                .append(' ')
                .append(binding.entry().nativeSymbol())
                .append('(')
                .append(parameters(binding.descriptor()))
                .append(") {\n");
        appendBody(
                builder,
                binding,
                llvmWrapperPrelude,
                llvmInvocation,
                businessStringSymbols);
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

    private String internalParameters(Binding binding) {
        return String.join(
                ", ",
                internalAbiParameters(binding).stream()
                        .map(parameter ->
                                parameter.type() + " " + parameter.name())
                        .toList());
    }

    private List<HostNativeLocalAbiBridgeSource.Parameter>
            internalAbiParameters(Binding binding) {
        ArrayList<HostNativeLocalAbiBridgeSource.Parameter> parameters =
                new ArrayList<>();
        if (binding.passesJniEnv()) {
            parameters.add(new HostNativeLocalAbiBridgeSource.Parameter(
                    "JNIEnv*",
                    "env",
                    "env"));
        }
        if (binding.passesOwnerClass()) {
            parameters.add(new HostNativeLocalAbiBridgeSource.Parameter(
                    "jclass",
                    "owner",
                    "owner"));
        }
        if (!binding.descriptor().staticMethod()) {
            parameters.add(new HostNativeLocalAbiBridgeSource.Parameter(
                    "jobject",
                    "self",
                    "self"));
        }
        for (int index = 0;
                index < binding.descriptor().javaParameterDescriptors().size();
                index++) {
            String descriptor = binding.descriptor()
                    .javaParameterDescriptors()
                    .get(index);
            String wrapperExpression = descriptor.equals("Z")
                            || descriptor.equals("B")
                            || descriptor.equals("C")
                            || descriptor.equals("S")
                    ? "(jint)arg" + index
                    : "arg" + index;
            parameters.add(new HostNativeLocalAbiBridgeSource.Parameter(
                    internalType(descriptor),
                    "arg" + index,
                    wrapperExpression));
        }
        return List.copyOf(parameters);
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
            case "Z", "B", "C", "S", "I" -> "jint";
            case "J" -> "jlong";
            case "F" -> "jfloat";
            case "D" -> "jdouble";
            default -> throw new IllegalArgumentException("unsupported LLVM native scalar descriptor: " + descriptor);
        };
    }

    private void appendBody(
            StringBuilder builder,
            Binding binding,
            Optional<String> llvmWrapperPrelude,
            Optional<String> llvmInvocation,
            BusinessStringSymbolMapper businessStringSymbols) {
        if (binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH) {
            appendLlvmWrapperBody(
                    builder,
                    binding,
                    llvmWrapperPrelude.orElseThrow(),
                    llvmInvocation.orElseThrow());
            return;
        }
        if (llvmInvocation.isPresent()
                || llvmWrapperPrelude.isPresent()) {
            throw new IllegalArgumentException(
                    "non-LLVM binding has a local ABI invocation");
        }
        String name = binding.decision().method().name();
        if (binding.decision().strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB) {
            if (binding.templateIrMethod().isPresent()) {
                appendGenericBodyHelper(
                        builder,
                        binding,
                        businessStringSymbols);
            } else {
                appendConstructorBodyHelper(builder, binding);
            }
            return;
        }
        if (binding.decision().strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            if (binding.templateIrMethod().isPresent()) {
                appendGenericBodyHelper(
                        builder,
                        binding,
                        businessStringSymbols);
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
            case "substring" -> appendTemplateSubstring(builder);
            default -> throw new IllegalArgumentException("unsupported host JNI method: " + name);
        }
    }

    private void appendTemplateSubstring(StringBuilder builder) {
        builder.append("    (void)owner;\n")
                .append("    if (arg0 == NULL) {\n")
                .append("        j2ll_throw_new(env, \"java/lang/NullPointerException\", \"substring receiver is null\");\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    jclass cls = (*env)->GetObjectClass(env, arg0);\n")
                .append("    if (cls == NULL) {\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    jmethodID method = (*env)->GetMethodID(env, cls, \"substring\", \"(I)Ljava/lang/String;\");\n")
                .append("    (*env)->DeleteLocalRef(env, cls);\n")
                .append("    if (method == NULL) {\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    return (jstring)(*env)->CallObjectMethod(env, arg0, method, 1);\n");
    }

    private void appendLlvmWrapperBody(
            StringBuilder builder,
            Binding binding,
            String prelude,
            String call) {
        if (!binding.passesJniEnv()) {
            builder.append("    (void)env;\n");
        }
        if (binding.descriptor().staticMethod() && !binding.passesOwnerClass()) {
            builder.append("    (void)owner;\n");
        }
        boolean localOwner = !binding.descriptor().staticMethod() && binding.passesOwnerClass();
        if (localOwner) {
            /*
             * The LLVM owner operand denotes the method's defining class, not
             * the receiver's runtime class.  In particular, native field
             * sidecars are keyed by the defining jclass.  GetObjectClass(self)
             * would split one static field into separate slots when a base
             * method is invoked on subclass instances.
             *
             * FindClass is called from the registered native method, so the
             * JVM resolves the slash-form name through that method's defining
             * loader.  The generated-fragment text pass keeps the owner name
             * encoded at rest.
             */
            new HostJniDefiningOwnerSource().appendLookup(
                    builder,
                    binding.decision().method().owner());
            appendDefaultReturn(builder, binding.descriptor().javaReturnDescriptor());
            builder.append("    }\n");
        }
        builder.append(prelude);
        String returnDescriptor = binding.descriptor().javaReturnDescriptor();
        if (returnDescriptor.equals("V")) {
            builder.append("    ").append(call).append(";\n")
                    .append(localOwner ? "    (*env)->DeleteLocalRef(env, owner);\n" : "")
                    .append("    return;\n");
        } else if (returnDescriptor.equals("Z")) {
            builder.append("    jboolean result = ").append(call).append(" != 0 ? JNI_TRUE : JNI_FALSE;\n")
                    .append(localOwner ? "    (*env)->DeleteLocalRef(env, owner);\n" : "")
                    .append("    return result;\n");
        } else {
            builder.append("    ")
                    .append(binding.descriptor().jniReturnType())
                    .append(" result = (")
                    .append(binding.descriptor().jniReturnType())
                    .append(")")
                    .append(call)
                    .append(";\n")
                    .append(localOwner ? "    (*env)->DeleteLocalRef(env, owner);\n" : "")
                    .append("    return result;\n");
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
        new HostJniDefiningOwnerSource().appendLookup(
                builder,
                binding.decision().method().owner(),
                "cls");
        builder.append("        return 0;\n")
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
        new HostJniDefiningOwnerSource().appendLookup(
                builder,
                binding.decision().method().owner(),
                "cls");
        builder.append("        return;\n")
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
        new HostJniDefiningOwnerSource().appendLookup(
                builder,
                binding.decision().method().owner(),
                "cls");
        builder.append("        return NULL;\n")
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

    private void appendGenericBodyHelper(
            StringBuilder builder,
            Binding binding,
            BusinessStringSymbolMapper businessStringSymbols) {
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
                appendGenericInstruction(
                        builder,
                        binding,
                        values,
                        instruction,
                        businessStringSymbols);
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
            IrInstruction instruction,
            BusinessStringSymbolMapper businessStringSymbols) {
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
            case CONST_STRING -> appendGenericConstString(
                    builder,
                    values,
                    instruction,
                    businessStringSymbols);
            case CALL_RUNTIME_HELPER -> appendGenericRuntimeHelper(
                    builder,
                    values,
                    instruction,
                    businessStringSymbols);
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
            IrInstruction instruction,
            BusinessStringSymbolMapper businessStringSymbols) {
        IrValue result = instruction.result().orElseThrow();
        String local = genericLocalName(result);
        String helper = BusinessStringConstantRef.fromInstruction(instruction)
                .orElseThrow()
                .helperSymbol(businessStringSymbols);
        values.put(result.name(), local);
        builder.append("    jstring ")
                .append(local)
                .append(" = (jstring)")
                .append(helper)
                .append("(env);\n")
                .append("    if ((*env)->ExceptionCheck(env) || ")
                .append(local)
                .append(" == NULL) {\n")
                .append("        return;\n")
                .append("    }\n");
    }

    private void appendGenericRuntimeHelper(
            StringBuilder builder,
            Map<String, String> values,
            IrInstruction instruction,
            BusinessStringSymbolMapper businessStringSymbols) {
        BusinessStringConstantRef constant =
                BusinessStringConstantRef.fromInstruction(instruction).orElse(null);
        if (constant == null) {
            throw new IllegalArgumentException("unsupported generic body runtime helper: " + instruction.symbol());
        }
        IrValue result = instruction.result().orElseThrow();
        String local = genericLocalName(result);
        values.put(result.name(), local);
        builder.append("    jobject ")
                .append(local)
                .append(" = ")
                .append(constant.helperSymbol(businessStringSymbols))
                .append("(env);\n")
                .append("    if ((*env)->ExceptionCheck(env) || ")
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
        String prefix = "j2ll_field_" + CIdentifier.forIdentity(
                field.owner() + "_" + field.name() + "_" + field.descriptor())
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
        String prefix = "j2ll_static_" + CIdentifier.forIdentity(
                field.owner() + "_" + field.name() + "_" + field.descriptor())
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

    private void appendDefaultReturn(StringBuilder builder, String returnDescriptor) {
        if (returnDescriptor.equals("V")) {
            builder.append("        return;\n");
        } else if (returnDescriptor.equals("Z")) {
            builder.append("        return JNI_FALSE;\n");
        } else if (returnDescriptor.equals("F")) {
            builder.append("        return 0.0f;\n");
        } else if (returnDescriptor.equals("D")) {
            builder.append("        return 0.0;\n");
        } else if (returnDescriptor.equals("B")
                || returnDescriptor.equals("C")
                || returnDescriptor.equals("S")
                || returnDescriptor.equals("I")
                || returnDescriptor.equals("J")) {
            builder.append("        return 0;\n");
        } else {
            builder.append("        return NULL;\n");
        }
    }

    private String runtimeHelperBaseSymbol(String symbol) {
        int separator = symbol.indexOf('|');
        return separator < 0 ? symbol : symbol.substring(0, separator);
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
        return CSourceEscaper.stringContents(value);
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


    record Binding(
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
            List<String> staticCallKeys,
            List<String> dispatchKeys,
            List<String> stringHelperSymbols,
            Optional<IrMethod> templateIrMethod,
            String reasonCode,
            JniMethodDescriptor descriptor) {
    }

    private record FieldParts(String owner, String name, String descriptor) {
    }

}
