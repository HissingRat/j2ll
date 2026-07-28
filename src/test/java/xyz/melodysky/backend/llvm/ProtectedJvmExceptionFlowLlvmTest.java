package xyz.melodysky.backend.llvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.pipeline.LoweringStatus;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.testsupport.ProtectedExceptionFlowFixture;

class ProtectedJvmExceptionFlowLlvmTest {
    @TempDir
    Path temp;

    private ParsedClass fixtureClass;

    @BeforeEach
    void compileFixture() throws Exception {
        Path jar = ProtectedExceptionFlowFixture.compileJar(temp);
        fixtureClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        ProtectedExceptionFlowFixture.OPS_INTERNAL_NAME + ".class",
                        ProtectedExceptionFlowFixture.classBytes(
                                jar,
                                ProtectedExceptionFlowFixture.OPS_INTERNAL_NAME),
                        "protected-exception-flow-fixture"))
                .artifact()
                .orElseThrow();
    }

    @Test
    void emitsPendingClearOrderedTypedDispatchAndUnmatchedRethrow() {
        String llvm = emit("typedAndContinue");

        int pending = llvm.indexOf("call ptr @j2ll_rt_pending_exception(");
        int clear = llvm.indexOf("call void @j2ll_rt_clear_exception(", pending);
        int firstTypeCheck = llvm.indexOf(
                "call i32 @" + catchTypeHelper("java/lang/NullPointerException") + "(",
                clear);
        int secondTypeCheck = llvm.indexOf(
                "call i32 @" + catchTypeHelper("java/lang/IndexOutOfBoundsException") + "(",
                firstTypeCheck + 1);
        int rethrow = llvm.indexOf("call void @j2ll_rt_rethrow(", secondTypeCheck);

        assertTrue(pending >= 0, llvm);
        assertTrue(clear > pending, llvm);
        assertTrue(firstTypeCheck > clear, llvm);
        assertTrue(secondTypeCheck > firstTypeCheck, llvm);
        assertTrue(rethrow > secondTypeCheck, llvm);
        assertTrue(llvm.contains("br i1 "), llvm);
        assertTrue(llvm.contains("phi i32 "), llvm);
        assertFalse(llvm.contains("call ptr @j2ll_rt_catch_dispatch("), llvm);
    }

    @Test
    void broadThrowableCatchClearsPendingExceptionAndCanContinue() {
        String llvm = emit("catchAllAndContinue");

        assertTrue(llvm.contains("call ptr @j2ll_rt_pending_exception("), llvm);
        assertTrue(llvm.contains("call void @j2ll_rt_clear_exception("), llvm);
        assertTrue(
                occurrences(llvm, "call ptr @j2ll_rt_pending_exception(")
                        > occurrences(
                                llvm,
                                "call i32 @"
                                        + catchTypeHelper("java/lang/Throwable")
                                        + "("),
                llvm);
        assertTrue(llvm.contains("call void @j2ll_rt_rethrow("), llvm);
        assertTrue(llvm.contains("phi i32 "), llvm);
        assertTrue(llvm.contains("ret i32 "), llvm);
    }

    @Test
    void unmatchedTypedExceptionAndFinallyCleanupBothEmitRethrowPaths() {
        String typed = emit("typedOrRethrow");
        String cleanup = emit("finallyAndRethrow");

        assertTrue(
                occurrences(typed, "call ptr @j2ll_rt_pending_exception(")
                        > occurrences(
                                typed,
                                "call i32 @"
                                        + catchTypeHelper("java/lang/IndexOutOfBoundsException")
                                        + "("),
                typed);
        assertTrue(typed.contains("call void @j2ll_rt_rethrow("), typed);
        assertTrue(cleanup.contains("call void @j2ll_rt_clear_exception("), cleanup);
        assertFalse(cleanup.contains("; localizedCatchTypeCheck"), cleanup);
        assertTrue(cleanup.contains("call void @j2ll_rt_rethrow("), cleanup);
        String cleanupFieldHelper = RuntimeTokenMapper.compatibility().helperSymbol(
                RuntimeTokenDomain.FIELD_RUNTIME,
                "field_put_static_i32",
                "pkg/ProtectedExceptionOps#cleanupMarker!I");
        assertTrue(
                cleanup.contains("call void @" + cleanupFieldHelper + "("),
                cleanup);
    }

    @Test
    void unprotectedThrowableInstructionsPreservePendingExceptionAndReturnDefault() {
        String llvm = emit("unprotectedLengthAndMarker");

        assertTrue(llvm.contains("call ptr @j2ll_rt_pending_exception("), llvm);
        assertTrue(llvm.contains("j2ll.ex.unhandled."), llvm);
        assertTrue(llvm.contains("ret i32 0"), llvm);
        assertFalse(llvm.contains("call void @j2ll_rt_clear_exception("), llvm);
        assertFalse(llvm.contains("call void @j2ll_rt_rethrow("), llvm);
    }

    private String emit(String methodName) {
        IrMethod method = lowerNative(methodName);
        return new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(
                new IrClass(method.owner(), List.of(method))));
    }

    private IrMethod lowerNative(String methodName) {
        ParsedMethod method = fixtureClass.methods().stream()
                .filter(candidate -> candidate.name().equals(methodName))
                .findFirst()
                .orElseThrow();
        var stage = new BytecodeToSsaLowerer().lower(
                new MethodCfgBuilder().build(method).artifact().orElseThrow());
        assertFalse(stage.hasErrors(), stage.diagnostics().toString());
        var result = stage.artifact().orElseThrow();
        assertEquals(LoweringStatus.NATIVE_LOWERED, result.status(), result.reason());
        return result.irMethod().orElseThrow();
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private String catchTypeHelper(String internalName) {
        return RuntimeTokenMapper.compatibility().helperSymbol(
                RuntimeTokenDomain.CLASS_RUNTIME,
                "instanceof",
                "instanceof:" + internalName);
    }
}
