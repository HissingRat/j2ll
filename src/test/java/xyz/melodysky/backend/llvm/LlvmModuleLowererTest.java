package xyz.melodysky.backend.llvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrSwitchCase;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.runtime.FieldIdentityToken;

class LlvmModuleLowererTest {
    @Test
    void lowersIrClassToPerClassModuleModelAndText() {
        IrValue left = new IrValue("%p0", IrType.I32);
        IrValue right = new IrValue("%p1", IrType.I32);
        IrValue sum = new IrValue("%sum", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Mathy",
                "add",
                "(II)I",
                IrType.I32,
                List.of(left, right),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.binary(sum, IrOpcode.ADD_I32, left, right)),
                        IrTerminator.returnValue(sum))));

        var module = new LlvmModuleLowerer().lowerClass(new IrClass("pkg/Mathy", List.of(method)));
        String text = new LlvmTextEmitter().emit(module);

        assertEquals("pkg/Mathy", module.identifier());
        assertTrue(text.contains("define external hidden i32 @j2ll_pkg_Mathy_add_"));
        assertTrue(text.contains("%sum = add i32 %p0, %p1"));
        assertTrue(text.contains("ret i32 %sum"));
    }

    @Test
    void canLowerNativeBuildModuleWithHiddenLinkableFunctions() {
        IrValue left = new IrValue("%p0", IrType.I32);
        IrValue right = new IrValue("%p1", IrType.I32);
        IrValue sum = new IrValue("%sum", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Mathy",
                "add",
                "(II)I",
                IrType.I32,
                List.of(left, right),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.binary(sum, IrOpcode.ADD_I32, left, right)),
                        IrTerminator.returnValue(sum))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(
                new IrClass("pkg/Mathy", List.of(method)),
                LlvmLinkage.EXTERNAL,
                LlvmVisibility.HIDDEN));

        assertTrue(text.contains("define external hidden i32 @j2ll_pkg_Mathy_add_"));
        assertTrue(text.contains("%sum = add i32 %p0, %p1"));
    }

    @Test
    void lowersSwitchTerminatorToLlvmSwitchText() {
        IrValue selector = new IrValue("%p0", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Switchy",
                "select",
                "(I)V",
                IrType.VOID,
                List.of(selector),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.switchOn(selector, "default", List.of(new IrSwitchCase(7, "seven")))),
                        new IrBlock("seven", List.of(), IrTerminator.returnVoid()),
                        new IrBlock("default", List.of(), IrTerminator.returnVoid())));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(new IrClass("pkg/Switchy", List.of(method))));

        assertTrue(text.contains("switch i32 %p0, label %default ["));
        assertTrue(text.contains("i32 7, label %seven"));
    }

    @Test
    void lowersBlockParametersToLlvmPhiIncoming() {
        IrValue left = new IrValue("%left", IrType.I32);
        IrValue right = new IrValue("%right", IrType.I32);
        IrValue merged = new IrValue("%merged", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Merge",
                "choose",
                "()I",
                IrType.I32,
                List.of(),
                List.of(
                        new IrBlock(
                                "left",
                                List.of(IrInstruction.constInt(left, 1)),
                                IrTerminator.gotoBlock("join", List.of(left))),
                        new IrBlock(
                                "right",
                                List.of(IrInstruction.constInt(right, 2)),
                                IrTerminator.gotoBlock("join", List.of(right))),
                        new IrBlock(
                                "join",
                                List.of(merged),
                                List.of(),
                                IrTerminator.returnValue(merged))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(new IrClass("pkg/Merge", List.of(method))));

        assertTrue(text.contains("%merged = phi i32 [ %left, %left ], [ %right, %right ]"));
    }

    @Test
    void lowersThrowTerminatorToRuntimeHelperAndUnreachable() {
        IrValue thrown = new IrValue("%p0", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Raise",
                "raise",
                "(Ljava/lang/RuntimeException;)V",
                IrType.VOID,
                List.of(thrown),
                List.of(new IrBlock(
                        "entry",
                        List.of(),
                        IrTerminator.throwValue(thrown))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(new IrClass("pkg/Raise", List.of(method))));

        assertTrue(text.contains("call void @j2ll_rt_throw(ptr %j2ll_env, ptr %p0)"));
        assertTrue(text.contains("ret void"));
    }

    @Test
    void lowersFieldAccessThroughGenericTokenizedRuntimeHelpers() {
        String staticFieldKey = "pkg/Fields#VALUE!I";
        String instanceFieldKey = "pkg/Fields#value!I";
        IrValue staticValue = new IrValue("%static", IrType.I32);
        IrMethod readStatic = new IrMethod(
                "pkg/Fields",
                "readStatic",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.fieldGet(staticValue, IrOpcode.GET_STATIC, List.of(), staticFieldKey)),
                        IrTerminator.returnValue(staticValue))));
        IrValue self = new IrValue("%p0", IrType.REFERENCE);
        IrValue fieldValue = new IrValue("%field", IrType.I32);
        IrMethod readInstance = new IrMethod(
                "pkg/Fields",
                "readInstance",
                "()I",
                IrType.I32,
                List.of(self),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.fieldGet(fieldValue, IrOpcode.GET_FIELD, List.of(self), instanceFieldKey)),
                        IrTerminator.returnValue(fieldValue))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(
                new IrClass("pkg/Fields", List.of(readStatic, readInstance))));

        assertTrue(text.contains("define external hidden i32 @j2ll_pkg_Fields_readStatic_"));
        assertTrue(text.contains("(ptr %j2ll_env, ptr %j2ll_owner)"));
        assertTrue(text.contains("call i32 @j2ll_rt_field_get_static_i32(ptr %j2ll_env, ptr %j2ll_owner, i64 "
                + FieldIdentityToken.token(staticFieldKey) + ")"));
        assertTrue(text.contains("define external hidden i32 @j2ll_pkg_Fields_readInstance_"));
        assertTrue(text.contains("(ptr %j2ll_env, ptr %p0)"));
        assertTrue(text.contains("call i32 @j2ll_rt_field_get_field_i32(ptr %j2ll_env, ptr %p0, i64 "
                + FieldIdentityToken.token(instanceFieldKey) + ")"));
        assertTrue(!text.contains("@j2ll_get_field_pkg_Fields_value_I"));
    }

    @Test
    void lowersIntegerDivisionAndRemainderThroughExceptionHelpers() {
        IrValue left = new IrValue("%p0", IrType.I32);
        IrValue right = new IrValue("%p1", IrType.I32);
        IrValue quotient = new IrValue("%quotient", IrType.I32);
        IrValue remainder = new IrValue("%remainder", IrType.I32);
        IrValue sum = new IrValue("%sum", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Div",
                "divRem",
                "(II)I",
                IrType.I32,
                List.of(left, right),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.binary(quotient, IrOpcode.DIV_I32, left, right),
                                IrInstruction.binary(remainder, IrOpcode.REM_I32, left, right),
                                IrInstruction.binary(sum, IrOpcode.ADD_I32, quotient, remainder)),
                        IrTerminator.returnValue(sum))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(new IrClass("pkg/Div", List.of(method))));

        assertTrue(text.contains("define external hidden i32 @j2ll_pkg_Div_divRem_"));
        assertTrue(text.contains("(ptr %j2ll_env, i32 %p0, i32 %p1)"));
        assertTrue(text.contains("%quotient = call i32 @j2ll_rt_div_i32(ptr %j2ll_env, i32 %p0, i32 %p1)"));
        assertTrue(text.contains("%remainder = call i32 @j2ll_rt_rem_i32(ptr %j2ll_env, i32 %p0, i32 %p1)"));
        assertFalse(text.contains("sdiv i32"));
        assertFalse(text.contains("srem i32"));
    }

    @Test
    void lowersMonitorHelpersThroughJniEnvBackedRuntimeCalls() {
        IrValue monitor = new IrValue("%p0", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Locks",
                "locked",
                "(Ljava/lang/Object;)V",
                IrType.VOID,
                List.of(monitor),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.operation(Optional.empty(), IrOpcode.MONITOR_ENTER, List.of(monitor), "monitor"),
                                IrInstruction.operation(Optional.empty(), IrOpcode.MONITOR_HAPPENS_BEFORE, List.of(monitor), "monitorEnter"),
                                IrInstruction.operation(Optional.empty(), IrOpcode.MONITOR_EXIT, List.of(monitor), "monitor"),
                                IrInstruction.operation(Optional.empty(), IrOpcode.MONITOR_HAPPENS_BEFORE, List.of(monitor), "monitorExit")),
                        IrTerminator.returnVoid())));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(new IrClass("pkg/Locks", List.of(method))));

        assertTrue(text.contains("define external hidden void @j2ll_pkg_Locks_locked_"));
        assertTrue(text.contains("(ptr %j2ll_env, ptr %p0)"));
        assertTrue(text.contains("declare void @j2ll_rt_monitor_enter(ptr, ptr) ; monitorEnter"));
        assertTrue(text.contains("call void @j2ll_rt_monitor_enter(ptr %j2ll_env, ptr %p0)"));
        assertTrue(text.contains("call void @j2ll_rt_monitor_exit(ptr %j2ll_env, ptr %p0)"));
        assertTrue(text.contains("fence acquire"));
        assertTrue(text.contains("fence release"));
    }

    @Test
    void lowersIntArraySubsetThroughJniArrayHelpers() {
        IrValue array = new IrValue("%p0", IrType.REFERENCE);
        IrValue value = new IrValue("%p1", IrType.I32);
        IrValue index = new IrValue("%zero", IrType.I32);
        IrValue length = new IrValue("%length", IrType.I32);
        IrValue first = new IrValue("%first", IrType.I32);
        IrValue result = new IrValue("%result", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Arrays",
                "setFirstPlusLength",
                "([II)I",
                IrType.I32,
                List.of(array, value),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(index, 0),
                                IrInstruction.operation(Optional.empty(), IrOpcode.ARRAY_STORE_I32, List.of(array, index, value), "int"),
                                IrInstruction.operation(Optional.of(length), IrOpcode.ARRAY_LENGTH, List.of(array), "arrayLength"),
                                IrInstruction.operation(Optional.of(first), IrOpcode.ARRAY_LOAD_I32, List.of(array, index), "int"),
                                IrInstruction.binary(result, IrOpcode.ADD_I32, length, first)),
                        IrTerminator.returnValue(result))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(new IrClass("pkg/Arrays", List.of(method))));

        assertTrue(text.contains("define external hidden i32 @j2ll_pkg_Arrays_setFirstPlusLength_"));
        assertTrue(text.contains("(ptr %j2ll_env, ptr %p0, i32 %p1)"));
        assertTrue(text.contains("call void @j2ll_rt_array_store_i32(ptr %j2ll_env, ptr %p0, i32 %zero, i32 %p1)"));
        assertTrue(text.contains("%length = call i32 @j2ll_rt_array_length_i32(ptr %j2ll_env, ptr %p0)"));
        assertTrue(text.contains("%first = call i32 @j2ll_rt_array_load_i32(ptr %j2ll_env, ptr %p0, i32 %zero)"));
        assertFalse(text.contains("@j2ll_rt_array_load_i32_"));
        assertFalse(text.contains("@j2ll_rt_array_store_i32_"));
    }

    @Test
    void lowersByteAndReferenceArraysThroughJniArrayHelpers() {
        IrValue bytes = new IrValue("%p0", IrType.REFERENCE);
        IrValue refs = new IrValue("%p1", IrType.REFERENCE);
        IrValue value = new IrValue("%p2", IrType.REFERENCE);
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue one = new IrValue("%one", IrType.I32);
        IrValue loadedByte = new IrValue("%loadedByte", IrType.I32);
        IrValue loadedRef = new IrValue("%loadedRef", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Arrays",
                "mixed",
                "([B[Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(bytes, refs, value),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(zero, 0),
                                IrInstruction.constInt(one, 1),
                                IrInstruction.operation(
                                        Optional.of(loadedByte),
                                        IrOpcode.ARRAY_LOAD_I32,
                                        List.of(bytes, zero),
                                        "byteOrBoolean"),
                                IrInstruction.operation(
                                        Optional.empty(),
                                        IrOpcode.ARRAY_STORE_I32,
                                        List.of(bytes, one, loadedByte),
                                        "byteOrBoolean"),
                                IrInstruction.operation(
                                        Optional.empty(),
                                        IrOpcode.ARRAY_STORE_REF,
                                        List.of(refs, zero, value),
                                        "reference"),
                                IrInstruction.operation(
                                        Optional.of(loadedRef),
                                        IrOpcode.ARRAY_LOAD_REF,
                                        List.of(refs, zero),
                                        "reference")),
                        IrTerminator.returnValue(loadedRef))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(new IrClass("pkg/Arrays", List.of(method))));

        assertTrue(text.contains("call i32 @j2ll_rt_array_load_i8(ptr %j2ll_env"));
        assertTrue(text.contains("call void @j2ll_rt_array_store_i8(ptr %j2ll_env"));
        assertTrue(text.contains("call void @j2ll_rt_array_store_ref(ptr %j2ll_env"));
        assertTrue(text.contains("call ptr @j2ll_rt_array_load_ref(ptr %j2ll_env"));
        assertFalse(text.contains("@j2ll_rt_array_load_ref_"));
    }

    @Test
    void lowersWideAndFloatingPrimitiveArraysThroughJniArrayHelpers() {
        IrValue longs = new IrValue("%p0", IrType.REFERENCE);
        IrValue floats = new IrValue("%p1", IrType.REFERENCE);
        IrValue doubles = new IrValue("%p2", IrType.REFERENCE);
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue one = new IrValue("%one", IrType.I32);
        IrValue loadedLong = new IrValue("%loadedLong", IrType.I64);
        IrValue loadedFloat = new IrValue("%loadedFloat", IrType.F32);
        IrValue loadedDouble = new IrValue("%loadedDouble", IrType.F64);
        IrMethod method = new IrMethod(
                "pkg/Arrays",
                "wide",
                "([J[F[D)D",
                IrType.F64,
                List.of(longs, floats, doubles),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(zero, 0),
                                IrInstruction.constInt(one, 1),
                                IrInstruction.operation(
                                        Optional.of(loadedLong),
                                        IrOpcode.ARRAY_LOAD_I64,
                                        List.of(longs, zero),
                                        "long"),
                                IrInstruction.operation(
                                        Optional.empty(),
                                        IrOpcode.ARRAY_STORE_I64,
                                        List.of(longs, one, loadedLong),
                                        "long"),
                                IrInstruction.operation(
                                        Optional.of(loadedFloat),
                                        IrOpcode.ARRAY_LOAD_F32,
                                        List.of(floats, zero),
                                        "float"),
                                IrInstruction.operation(
                                        Optional.empty(),
                                        IrOpcode.ARRAY_STORE_F32,
                                        List.of(floats, one, loadedFloat),
                                        "float"),
                                IrInstruction.operation(
                                        Optional.of(loadedDouble),
                                        IrOpcode.ARRAY_LOAD_F64,
                                        List.of(doubles, zero),
                                        "double"),
                                IrInstruction.operation(
                                        Optional.empty(),
                                        IrOpcode.ARRAY_STORE_F64,
                                        List.of(doubles, one, loadedDouble),
                                        "double")),
                        IrTerminator.returnValue(loadedDouble))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(new IrClass("pkg/Arrays", List.of(method))));

        assertTrue(text.contains("call i64 @j2ll_rt_array_load_i64(ptr %j2ll_env"));
        assertTrue(text.contains("call void @j2ll_rt_array_store_i64(ptr %j2ll_env"));
        assertTrue(text.contains("call float @j2ll_rt_array_load_f32(ptr %j2ll_env"));
        assertTrue(text.contains("call void @j2ll_rt_array_store_f32(ptr %j2ll_env"));
        assertTrue(text.contains("call double @j2ll_rt_array_load_f64(ptr %j2ll_env"));
        assertTrue(text.contains("call void @j2ll_rt_array_store_f64(ptr %j2ll_env"));
        assertFalse(text.contains("@j2ll_rt_array_load_i64_"));
        assertFalse(text.contains("@j2ll_rt_array_store_f64_"));
    }

    @Test
    void lowersConfiguredStaticCallTargetsToDirectLlvmCalls() {
        IrValue calleeArg = new IrValue("%p0", IrType.I32);
        IrValue calleeResult = new IrValue("%plus", IrType.I32);
        IrMethod callee = new IrMethod(
                "pkg/Calls",
                "callee",
                "(I)I",
                IrType.I32,
                List.of(calleeArg),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.binary(calleeResult, IrOpcode.ADD_I32, calleeArg, calleeArg)),
                        IrTerminator.returnValue(calleeResult))));
        IrValue callerArg = new IrValue("%p0", IrType.I32);
        IrValue callResult = new IrValue("%call", IrType.I32);
        String calleeKey = "pkg/Calls#callee!(I)I";
        IrMethod caller = new IrMethod(
                "pkg/Calls",
                "caller",
                "(I)I",
                IrType.I32,
                List.of(callerArg),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(Optional.of(callResult), IrOpcode.CALL_STATIC, List.of(callerArg), calleeKey)),
                        IrTerminator.returnValue(callResult))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(
                new IrClass("pkg/Calls", List.of(callee, caller)),
                LlvmLinkage.EXTERNAL,
                LlvmVisibility.HIDDEN,
                Set.of(calleeKey)));

        assertTrue(text.contains("call i32 @j2ll_pkg_Calls_callee_"));
        assertTrue(!text.contains("@j2ll_call_pkg_Calls_callee"));
    }

    @Test
    void lowersConfiguredSpecialCallTargetsToDirectLlvmCalls() {
        IrValue self = new IrValue("%p0", IrType.REFERENCE);
        IrValue calleeArg = new IrValue("%p1", IrType.I32);
        IrValue calleeResult = new IrValue("%plus", IrType.I32);
        IrMethod callee = new IrMethod(
                "pkg/Calls",
                "privateValue",
                "(I)I",
                IrType.I32,
                List.of(self, calleeArg),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.binary(calleeResult, IrOpcode.ADD_I32, calleeArg, calleeArg)),
                        IrTerminator.returnValue(calleeResult))));
        IrValue callerSelf = new IrValue("%p0", IrType.REFERENCE);
        IrValue callerArg = new IrValue("%p1", IrType.I32);
        IrValue callResult = new IrValue("%call", IrType.I32);
        String calleeKey = "pkg/Calls#privateValue!(I)I";
        IrMethod caller = new IrMethod(
                "pkg/Calls",
                "caller",
                "(I)I",
                IrType.I32,
                List.of(callerSelf, callerArg),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(callResult),
                                IrOpcode.CALL_SPECIAL,
                                List.of(callerSelf, callerArg),
                                calleeKey)),
                        IrTerminator.returnValue(callResult))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(
                new IrClass("pkg/Calls", List.of(callee, caller)),
                LlvmLinkage.EXTERNAL,
                LlvmVisibility.HIDDEN,
                Set.of(calleeKey)));

        assertTrue(text.contains("call i32 @j2ll_pkg_Calls_privateValue_"));
        assertTrue(text.contains("(ptr %p0, i32 %p1)"));
        assertTrue(!text.contains("@j2ll_call_pkg_Calls_privateValue"));
    }
}
