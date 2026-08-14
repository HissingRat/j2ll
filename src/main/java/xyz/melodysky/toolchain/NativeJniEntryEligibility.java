package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Optional;
import xyz.melodysky.backend.llvm.LlvmFunctionAbi;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.runtime.jni.JniTypeMapper;

/** Fail-closed policy for relocating an ordinary JNI entry into LLVM. */
final class NativeJniEntryEligibility {
    private final JniTypeMapper typeMapper = new JniTypeMapper();

    Decision assess(
            NativeMethodImplementation implementation,
            IrMethod method,
            boolean requiresSemanticTopology) {
        if (implementation.path()
                        != NativeImplementationPath.LLVM_NATIVE_PATH
                || !implementation.emitsStandaloneLlvmBody()) {
            return Decision.rejected("LLVM_JNI_PROXY_NOT_STANDALONE_LLVM");
        }
        if (implementation.decision().strategy()
                != MethodRewriteStrategy.NATIVE_ORIGINAL) {
            return Decision.rejected("LLVM_JNI_PROXY_SPECIAL_REWRITE");
        }
        if (implementation.initializerPlan().isPresent()) {
            return Decision.rejected("LLVM_JNI_PROXY_INITIALIZER_PLAN");
        }
        if (implementation.decision().method().accessFlags().isSynchronized()) {
            return Decision.rejected("LLVM_JNI_PROXY_SYNCHRONIZED");
        }
        boolean staticMethod = implementation.decision()
                .method()
                .accessFlags()
                .isStatic();
        if (!staticMethod && implementation.passesOwnerClass()) {
            return Decision.rejected(
                    "LLVM_JNI_PROXY_INSTANCE_OWNER_CLASS");
        }
        if (method == null
                || !method.methodKey().equals(implementation.methodKey())) {
            return Decision.rejected("LLVM_JNI_PROXY_IR_MISSING");
        }
        String descriptor = implementation.decision().method().descriptor();
        if (!NativeJniEntryDescriptorPolicy.supports(descriptor)
                || !descriptorMatchesMethod(descriptor, staticMethod, method)) {
            return Decision.rejected("LLVM_JNI_PROXY_UNSAFE_DESCRIPTOR");
        }
        Optional<NativeJniProxyAbiProjection> projection =
                NativeJniProxyAbiProjection.derive(implementation);
        if (projection.isEmpty()) {
            return Decision.rejected("LLVM_JNI_PROXY_ABI_NOT_PROJECTABLE");
        }
        NativeLocalAbiProfile profile = requiresSemanticTopology
                ? NativeLocalAbiProfile.JVM_SEMANTIC_SURFACE
                : NativeLocalAbiProfile.COMPACT_DIVERSE;
        return Decision.approved(
                LlvmFunctionAbi.physicalJniEntry(staticMethod),
                projection.orElseThrow(),
                profile);
    }

    private boolean descriptorMatchesMethod(
            String descriptor,
            boolean staticMethod,
            IrMethod method) {
        List<String> parameterDescriptors =
                typeMapper.parameterDescriptors(descriptor);
        int offset = staticMethod ? 0 : 1;
        if (method.parameters().size()
                != parameterDescriptors.size() + offset) {
            return false;
        }
        if (!staticMethod
                && method.parameters().get(0).type()
                        != IrType.REFERENCE) {
            return false;
        }
        for (int index = 0; index < parameterDescriptors.size(); index++) {
            Optional<IrType> expected = NativeJniEntryDescriptorPolicy.irType(
                    parameterDescriptors.get(index));
            if (expected.isEmpty()
                    || method.parameters().get(index + offset).type()
                            != expected.orElseThrow()) {
                return false;
            }
        }
        String returnDescriptor = typeMapper.returnDescriptor(descriptor);
        if (returnDescriptor.equals("V")) {
            return method.returnType() == IrType.VOID;
        }
        return NativeJniEntryDescriptorPolicy.irType(returnDescriptor)
                .map(type -> method.returnType() == type)
                .orElse(false);
    }

    record Decision(
            boolean approved,
            LlvmFunctionAbi physicalAbi,
            Optional<NativeJniProxyAbiProjection> projection,
            NativeLocalAbiProfile profile,
            String reasonCode) {
        private static Decision approved(
                LlvmFunctionAbi physicalAbi,
                NativeJniProxyAbiProjection projection,
                NativeLocalAbiProfile profile) {
            return new Decision(
                    true,
                    physicalAbi,
                    Optional.of(projection),
                    profile,
                    profile == NativeLocalAbiProfile.JVM_SEMANTIC_SURFACE
                            ? "LLVM_JNI_PROXY_SEMANTIC_SURFACE"
                            : "LLVM_JNI_PROXY_PURE_SCALAR");
        }

        private static Decision rejected(String reasonCode) {
            return new Decision(
                    false,
                    new LlvmFunctionAbi(false, false),
                    Optional.empty(),
                    NativeLocalAbiProfile.COMPACT_DIVERSE,
                    reasonCode);
        }
    }
}
