package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.runtime.jni.JniTypeMapper;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

class HostJniInternalMethodDispatchSourceTest {
    private static final String TARGET =
            "pkg/Hidden#hiddenTarget!(Ljava/lang/Object;)Ljava/lang/String;";

    @Test
    void staticReferenceBridgeUsesNestedLocalFrameAndNoMethodIdLookup() {
        HostJniCSourceGenerator.Binding target =
                binding(
                        "pkg/Hidden",
                        "hiddenTarget",
                        "(Ljava/lang/Object;)Ljava/lang/String;",
                        Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC,
                        MethodRewriteStrategy.INTERNAL_NATIVE_ONLY,
                        "j2ll_n_0123456789abcdef0123456789abcdef",
                        List.of(),
                        List.of());
        StringBuilder source = new StringBuilder();

        new HostJniInternalMethodDispatchSource().appendBody(
                source,
                target,
                "ref",
                true);

        String c = source.toString();
        assertTrue(c.contains("PushLocalFrame"));
        assertTrue(c.contains(
                "j2ll_n_0123456789abcdef0123456789abcdef(env, target_owner, (jobject)args[0].l)"));
        assertTrue(c.contains(
                "PopLocalFrame(env, result)"));
        assertTrue(c.contains("ExceptionOccurred"));
        assertTrue(c.contains("ExceptionClear"));
        assertTrue(c.contains("Throw(env, promoted)"));
        assertTrue(c.contains(
                "DeleteLocalRef(env, target_owner)"));
        assertFalse(c.contains("GetStaticMethodID"));
        assertFalse(c.contains("CallStaticObjectMethodA"));
    }

    @Test
    void completeGeneratorKeepsInternalWrapperButOmitsRegistrationMetadata() {
        NativeMethodImplementation caller = implementation(
                binding(
                        "pkg/Caller",
                        "call",
                        "(Ljava/lang/Object;)Ljava/lang/String;",
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        MethodRewriteStrategy.NATIVE_ORIGINAL,
                        "j2ll_n_11111111111111111111111111111111",
                        List.of(TARGET),
                        List.of()));
        NativeMethodImplementation target = implementation(
                binding(
                        "pkg/Hidden",
                        "hiddenTarget",
                        "(Ljava/lang/Object;)Ljava/lang/String;",
                        Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC,
                        MethodRewriteStrategy.INTERNAL_NATIVE_ONLY,
                        "j2ll_n_22222222222222222222222222222222",
                        List.of(),
                        List.of()));
        NativeImplementationPlan plan =
                new NativeImplementationPlan(
                        List.of(caller, target),
                        Map.of());
        StringBuilder dispatchOnly = new StringBuilder();
        HostJniDispatchRuntimeSource.append(
                dispatchOnly,
                List.of(binding(caller), binding(target)),
                RuntimeTokenMapper.fromBytes(
                        NativeTextBuildKey.fromUtf8(
                                        "internal-method-dispatch-test")
                                .bytes()));
        assertFalse(dispatchOnly.toString().contains(
                "GetStaticMethodID"));
        assertFalse(dispatchOnly.toString().contains(
                "CallStaticObjectMethodA"));

        String source = new HostJniCSourceGenerator().generate(
                plan,
                RuntimeLoaderPlan.create("native0", 0),
                false,
                1L,
                NativeTextBuildKey.fromUtf8(
                        "internal-method-dispatch-test"));

        assertTrue(source.contains("PushLocalFrame"));
        assertTrue(source.contains(
                "j2ll_n_22222222222222222222222222222222(env, target_owner"));
        assertFalse(source.contains("\"hiddenTarget\""));
        assertTrue(source.contains(
                "static jstring j2ll_n_22222222222222222222222222222222("));
        assertTrue(plan.registrationPlan().entries().stream()
                .noneMatch(entry ->
                        entry.methodName().equals("hiddenTarget")));
        assertTrue(plan.registrationPlan().entries().stream()
                .anyMatch(entry ->
                        entry.methodName().equals("call")));
    }

    private NativeMethodImplementation implementation(
            HostJniCSourceGenerator.Binding binding) {
        return new NativeMethodImplementation(
                binding.entry(),
                binding.decision(),
                binding.path(),
                binding.llvmFunctionSymbol(),
                binding.reasonCode(),
                binding.passesJniEnv(),
                binding.passesOwnerClass(),
                binding.fieldKeys(),
                binding.directCallTargets(),
                binding.allocationKeys(),
                binding.typeCheckKeys(),
                binding.classObjectKeys(),
                binding.runtimeMetadataKeys(),
                binding.constructorCallKeys(),
                binding.staticCallKeys(),
                binding.dispatchKeys(),
                binding.stringHelperSymbols(),
                binding.implementationIrMethod());
    }

    private HostJniCSourceGenerator.Binding binding(
            NativeMethodImplementation implementation) {
        MethodRewriteDecision decision =
                implementation.decision();
        return new HostJniCSourceGenerator.Binding(
                implementation.entry(),
                decision,
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
                new JniTypeMapper().methodDescriptor(
                        decision.method().owner(),
                        decision.method().name(),
                        decision.method().descriptor(),
                        decision.method().accessFlags()
                                .isStatic()));
    }

    private HostJniCSourceGenerator.Binding binding(
            String owner,
            String name,
            String descriptor,
            int access,
            MethodRewriteStrategy strategy,
            String nativeSymbol,
            List<String> staticCalls,
            List<String> dispatchCalls) {
        MethodNode node = new MethodNode(
                access,
                name,
                descriptor,
                null,
                null);
        ParsedMethod method = new ParsedMethod(
                owner,
                name,
                descriptor,
                new AccessFlags(access),
                List.of(),
                List.of(),
                List.of(),
                true,
                0,
                0,
                node);
        MethodRewriteDecision decision =
                new MethodRewriteDecision(
                        method,
                        strategy,
                        owner,
                        Optional.empty(),
                        strategy
                                == MethodRewriteStrategy
                                        .INTERNAL_NATIVE_ONLY
                                ? "METHOD_INTERNALIZATION_ELIGIBLE"
                                : "NATIVE_ORIGINAL");
        NativeRegistrationEntry entry =
                new NativeRegistrationEntry(
                        owner,
                        name,
                        descriptor,
                        nativeSymbol);
        return new HostJniCSourceGenerator.Binding(
                entry,
                decision,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                Optional.of("j2ll_l_"
                        + (name.equals("call")
                                ? "33333333333333333333333333333333"
                                : "44444444444444444444444444444444")),
                true,
                true,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                staticCalls,
                dispatchCalls,
                List.of(),
                Optional.empty(),
                "LLVM_STATIC_CALL_HELPER_IR",
                new JniTypeMapper().methodDescriptor(
                        owner,
                        name,
                        descriptor,
                        (access & Opcodes.ACC_STATIC) != 0));
    }
}
