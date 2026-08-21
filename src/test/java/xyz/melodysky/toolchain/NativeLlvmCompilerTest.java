package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;

class NativeLlvmCompilerTest {
    @Test
    void compilesOnlyFinalLlvmImplementationsAndSharesTheirPassEvidence() throws Exception {
        IrMethod llvmMethod = branchingMethod("compiled");
        IrMethod templateMethod = branchingMethod("templateOnly");
        NativeImplementationPlan implementationPlan = new NativeImplementationPlan(List.of(
                implementation(llvmMethod, NativeImplementationPath.LLVM_NATIVE_PATH),
                implementation(templateMethod, NativeImplementationPath.TEMPLATE_JNI_PATH)));
        LinkedHashMap<String, IrMethod> irMethods = new LinkedHashMap<>();
        irMethods.put(templateMethod.methodKey(), templateMethod);
        irMethods.put(llvmMethod.methodKey(), llvmMethod);
        LlvmProtectionConfig config =
                LlvmProtectionConfig.selected(41L, false, true, true, false, false);
        RecordingListener listener = new RecordingListener();

        NativeLlvmCompilation compilation = new NativeLlvmCompiler(
                        xyz.melodysky.testsupport.TestProtectionMaterials.llvmLowerer(),
                        new LlvmTextEmitter())
                .compile(implementationPlan, irMethods, config, listener);

        assertEquals(List.of(1), listener.startedTotals);
        assertEquals(List.of("1/1:pkg/FinalPath"), listener.modules);
        assertEquals(List.of(1), listener.completedTotals);
        assertEquals(1, compilation.modules().size());
        NativeLlvmModuleCompilation module = compilation.modules().get(0);
        assertEquals(List.of(llvmMethod.methodKey()),
                module.registeredMethods().stream().map(IrMethod::methodKey).toList());
        assertEquals(List.of(llvmMethod.methodKey()),
                module.compiledMethods().stream().map(IrMethod::methodKey).toList());
        String compiledSymbol = new LlvmNameMangler().functionName(llvmMethod);
        String templateSymbol = new LlvmNameMangler().functionName(templateMethod);
        assertTrue(module.llvmText().contains("@" + compiledSymbol));
        assertFalse(module.llvmText().contains("@" + templateSymbol));
        assertEquals(List.of(compiledSymbol), module.blockLayout().affectedFunctions());
        assertEquals(List.of(compiledSymbol), module.opaquePredicates().affectedFunctions());

        NativeImplementationPlan reconstructedPlan = new NativeImplementationPlan(List.of(
                implementation(llvmMethod, NativeImplementationPath.LLVM_NATIVE_PATH),
                implementation(templateMethod, NativeImplementationPath.TEMPLATE_JNI_PATH)));
        NativeLlvmCompilation repeated = new NativeLlvmCompiler(
                        xyz.melodysky.testsupport.TestProtectionMaterials.llvmLowerer(),
                        new LlvmTextEmitter())
                .compile(reconstructedPlan, Map.copyOf(irMethods), config);
        NativeLlvmCompilation changedConfig = new NativeLlvmCompiler(
                        xyz.melodysky.testsupport.TestProtectionMaterials.llvmLowerer(),
                        new LlvmTextEmitter())
                .compile(
                        implementationPlan,
                        irMethods,
                        LlvmProtectionConfig.disabled(41L));
        assertEquals(compilation.inputKey(), repeated.inputKey());
        assertEquals(compilation.textByOwner(), repeated.textByOwner());
        assertNotEquals(compilation.inputKey(), changedConfig.inputKey());
    }

    @Test
    void rejectsNestedCallsFromCompilerInternalHelpersUntilTheirAbiClosureIsModeled() {
        IrMethod secondHelper = constantMethod("secondHelper", 17);
        IrMethod firstHelper = callingMethod("firstHelper", secondHelper.methodKey());
        IrMethod registered = callingMethod("registered", firstHelper.methodKey());
        NativeMethodImplementation base =
                implementation(registered, NativeImplementationPath.LLVM_NATIVE_PATH);
        NativeMethodImplementation withOutlinedTarget = new NativeMethodImplementation(
                base.entry(),
                base.decision(),
                base.path(),
                base.llvmFunctionSymbol(),
                base.reasonCode(),
                false,
                true,
                List.of(),
                List.of(firstHelper.methodKey()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty());
        LinkedHashMap<String, IrMethod> irMethods = new LinkedHashMap<>();
        irMethods.put(registered.methodKey(), registered);
        irMethods.put(firstHelper.methodKey(), firstHelper);
        irMethods.put(secondHelper.methodKey(), secondHelper);

        IOException exception = assertThrows(
                IOException.class,
                () -> new NativeLlvmCompiler(
                                xyz.melodysky.testsupport.TestProtectionMaterials.llvmLowerer(),
                                new LlvmTextEmitter())
                        .compile(
                                new NativeImplementationPlan(List.of(withOutlinedTarget)),
                                irMethods,
                                LlvmProtectionConfig.disabled(41L)));

        assertTrue(exception.getMessage().contains(
                "compiler-internal method contains unsupported nested calls"));
        assertTrue(exception.getMessage().contains(firstHelper.methodKey()));
        assertTrue(exception.getMessage().contains(secondHelper.methodKey()));
    }

    private NativeMethodImplementation implementation(
            IrMethod method,
            NativeImplementationPath path) {
        ParsedMethod parsed = new ParsedMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                new AccessFlags(AccessFlags.PUBLIC | AccessFlags.STATIC),
                List.of(),
                List.of(),
                List.of(),
                true,
                1,
                2,
                new MethodNode(
                        Opcodes.ASM9,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        method.name(),
                        method.descriptor(),
                        null,
                        null));
        MethodRewriteDecision decision = new MethodRewriteDecision(
                parsed,
                MethodRewriteStrategy.NATIVE_ORIGINAL,
                method.owner(),
                Optional.empty(),
                "test");
        NativeRegistrationEntry entry = new NativeRegistrationEntry(
                method.owner(),
                method.name(),
                method.descriptor(),
                "j2ll_test_" + method.name());
        return new NativeMethodImplementation(
                entry,
                decision,
                path,
                path == NativeImplementationPath.LLVM_NATIVE_PATH
                        ? Optional.of(new LlvmNameMangler().functionName(method))
                        : Optional.empty(),
                "test");
    }

    private IrMethod branchingMethod(String name) {
        IrValue input = new IrValue("%input", IrType.I32);
        IrValue zero = new IrValue("%zero_" + name, IrType.I32);
        IrValue condition = new IrValue("%condition_" + name, IrType.I1);
        IrValue positive = new IrValue("%positive_" + name, IrType.I32);
        IrValue negative = new IrValue("%negative_" + name, IrType.I32);
        return new IrMethod(
                "pkg/FinalPath",
                name,
                "(I)I",
                IrType.I32,
                List.of(input),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        IrInstruction.constInt(zero, 0),
                                        IrInstruction.binary(
                                                condition,
                                                IrOpcode.CMP_GE_I32,
                                                input,
                                                zero)),
                                IrTerminator.branch(condition, "positive", "negative")),
                        new IrBlock(
                                "positive",
                                List.of(IrInstruction.constInt(positive, 7)),
                                IrTerminator.returnValue(positive)),
                        new IrBlock(
                                "negative",
                                List.of(IrInstruction.constInt(negative, -7)),
                                IrTerminator.returnValue(negative))));
    }

    private IrMethod callingMethod(String name, String target) {
        IrValue result = new IrValue("%result_" + name, IrType.I32);
        return new IrMethod(
                "pkg/FinalPath",
                name,
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(result),
                                IrOpcode.CALL_STATIC,
                                List.of(),
                                target)),
                        IrTerminator.returnValue(result))));
    }

    private IrMethod constantMethod(String name, int value) {
        IrValue result = new IrValue("%result_" + name, IrType.I32);
        return new IrMethod(
                "pkg/FinalPath",
                name,
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constInt(result, value)),
                        IrTerminator.returnValue(result))));
    }

    private static final class RecordingListener implements NativeLlvmCompilationListener {
        private final List<Integer> startedTotals = new ArrayList<>();
        private final List<String> modules = new ArrayList<>();
        private final List<Integer> completedTotals = new ArrayList<>();

        @Override
        public void started(int totalOwners) {
            startedTotals.add(totalOwners);
        }

        @Override
        public void moduleStarted(int currentOwner, int totalOwners, String owner) {
            modules.add(currentOwner + "/" + totalOwners + ":" + owner);
        }

        @Override
        public void completed(int totalOwners) {
            completedTotals.add(totalOwners);
        }
    }
}
