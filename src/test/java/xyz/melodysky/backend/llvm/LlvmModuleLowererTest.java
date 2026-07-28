package xyz.melodysky.backend.llvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
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
import xyz.melodysky.analysis.field.NativeFieldStorageKind;
import xyz.melodysky.ir.model.NativeFieldSlotRef;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiDomain;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiPlan;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiPlanner;

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
        assertTrue(text.contains("define external hidden i32 @" + new LlvmNameMangler().functionName(method)));
        assertFalse(text.contains("pkg_Mathy"));
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

        assertTrue(text.contains("define external hidden i32 @" + new LlvmNameMangler().functionName(method)));
        assertFalse(text.contains("pkg_Mathy"));
        assertTrue(text.contains("%sum = add i32 %p0, %p1"));
    }

    @Test
    void classLiteralOnlyStaticAndInstanceFunctionsPassJniEnvToLocalizedHelper() {
        IrValue staticResult =
                new IrValue("%static_class", IrType.REFERENCE);
        IrMethod staticLiteral = new IrMethod(
                "pkg/ClassLiteralOps",
                "staticLiteral",
                "()Ljava/lang/Class;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.symbolicConstant(
                                staticResult,
                                IrOpcode.CONST_CLASS,
                                "class:Ljava/lang/String;")),
                        IrTerminator.returnValue(staticResult))));
        IrValue self = new IrValue("%this", IrType.REFERENCE);
        IrValue instanceResult =
                new IrValue("%instance_class", IrType.REFERENCE);
        IrMethod instanceLiteral = new IrMethod(
                "pkg/ClassLiteralOps",
                "instanceLiteral",
                "()Ljava/lang/Class;",
                IrType.REFERENCE,
                List.of(self),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.symbolicConstant(
                                instanceResult,
                                IrOpcode.CONST_CLASS,
                                "class:Ljava/lang/String;")),
                        IrTerminator.returnValue(instanceResult))));

        String text = new LlvmTextEmitter().emit(
                new LlvmModuleLowerer().lowerClass(new IrClass(
                        "pkg/ClassLiteralOps",
                        List.of(staticLiteral, instanceLiteral))));

        assertTrue(text.contains(
                "define external hidden ptr @"
                        + new LlvmNameMangler().functionName(staticLiteral)
                        + "(ptr %j2ll_env)"));
        assertTrue(text.contains(
                "define external hidden ptr @"
                        + new LlvmNameMangler().functionName(instanceLiteral)
                        + "(ptr %j2ll_env, ptr %this)"));
        String helper = RuntimeTokenMapper.compatibility().helperSymbol(
                RuntimeTokenDomain.CLASS_OBJECT,
                "class_object",
                "Ljava/lang/String;");
        assertEquals(
                2,
                occurrences(
                        text,
                        "call ptr @"
                                + helper
                                + "(ptr %j2ll_env)"),
                text);
    }

    @Test
    void declaresEveryLocalizedReferenceArrayAllocatorThatItCalls() {
        IrValue length = new IrValue("%p0", IrType.I32);
        IrValue array = new IrValue("%array", IrType.REFERENCE);
        String allocationKey = "referenceArray:java/lang/String";
        IrMethod method = new IrMethod(
                "pkg/ReferenceArrays",
                "allocate",
                "(I)[Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(length),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.operation(
                                Optional.of(array),
                                IrOpcode.NEW_ARRAY,
                                List.of(length),
                                allocationKey)),
                        IrTerminator.returnValue(array))));

        String text = new LlvmTextEmitter().emit(
                new LlvmModuleLowerer().lowerClass(new IrClass(
                        method.owner(),
                        List.of(method))));
        String helper = RuntimeTokenMapper.compatibility().helperSymbol(
                RuntimeTokenDomain.CLASS_RUNTIME,
                "new_object_array",
                allocationKey);

        assertTrue(text.contains(
                "declare ptr @" + helper
                        + "(ptr, i32) ; localizedReferenceArrayAllocation"),
                text);
        assertTrue(text.contains(
                "call ptr @" + helper
                        + "(ptr %j2ll_env, i32 %p0)"),
                text);
    }

    @Test
    void declaresEveryLocalizedLambdaFactoryThatItCalls() {
        IrValue capture = new IrValue("%p0", IrType.REFERENCE);
        IrValue metadata = new IrValue("%metadata", IrType.I64);
        IrValue lambda = new IrValue("%lambda", IrType.REFERENCE);
        String identity = "lambda:fixture";
        IrMethod method = new IrMethod(
                "pkg/Lambdas",
                "factory",
                "(Ljava/lang/Object;)Ljava/lang/Runnable;",
                IrType.REFERENCE,
                List.of(capture),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constLong(metadata, 7),
                                IrInstruction.call(
                                        Optional.of(lambda),
                                        IrOpcode.CALL_RUNTIME_HELPER,
                                        List.of(metadata, capture),
                                        "j2ll_rt_lambda_new|" + identity)),
                        IrTerminator.returnValue(lambda))));

        String text = new LlvmTextEmitter().emit(
                new LlvmModuleLowerer().lowerClass(new IrClass(
                        method.owner(),
                        List.of(method))));
        String helper = RuntimeTokenMapper.compatibility().helperSymbol(
                RuntimeTokenDomain.LAMBDA,
                "lambda_new",
                identity);

        assertTrue(text.contains(
                "declare ptr @" + helper
                        + "(ptr, ptr) ; localizedLambdaFactory"),
                text);
        assertTrue(text.contains(
                "call ptr @" + helper
                        + "(ptr %j2ll_env, ptr %p0)"),
                text);
    }

    @Test
    void rejectsPlannerAndLowererFunctionAbiMismatchBeforeEmission() {
        IrValue result = new IrValue("%class", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/ClassLiteralOps",
                "literal",
                "()Ljava/lang/Class;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.symbolicConstant(
                                result,
                                IrOpcode.CONST_CLASS,
                                "class:Ljava/lang/String;")),
                        IrTerminator.returnValue(result))));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new LlvmModuleLowerer().lowerClass(
                        new IrClass("pkg/ClassLiteralOps", List.of(method)),
                        LlvmLinkage.EXTERNAL,
                        LlvmVisibility.HIDDEN,
                        Map.of(),
                        Map.of(),
                        Map.of(
                                method.methodKey(),
                                new LlvmFunctionAbi(false, false))));

        assertTrue(failure.getMessage().contains(method.methodKey()));
        assertTrue(failure.getMessage().contains("planned="));
        assertTrue(failure.getMessage().contains("inferred="));
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

        assertTrue(text.contains("call void @j2ll_rt_rethrow(ptr %j2ll_env, ptr %p0)"));
        assertTrue(text.contains("ret void"));
    }

    @Test
    void lowersFieldAccessThroughLocalizedBindingHelpers() {
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

        assertTrue(text.contains("define external hidden i32 @" + new LlvmNameMangler().functionName(readStatic)));
        assertTrue(text.contains("(ptr %j2ll_env, ptr %j2ll_owner)"));
        RuntimeTokenMapper runtimeTokens =
                RuntimeTokenMapper.compatibility();
        String staticHelper = runtimeTokens.helperSymbol(
                RuntimeTokenDomain.FIELD_RUNTIME,
                "field_get_static_i32",
                staticFieldKey);
        assertTrue(text.contains(
                "call i32 @" + staticHelper
                        + "("
                        + localAbiCall(
                                runtimeTokens,
                                RuntimeLocalAbiDomain.FIELD,
                                "field_get_static_i32",
                                staticFieldKey,
                                List.of(
                                        "ptr %j2ll_env",
                                        "ptr %j2ll_owner"))
                        + ")"));
        assertTrue(text.contains("define external hidden i32 @" + new LlvmNameMangler().functionName(readInstance)));
        assertFalse(text.contains("pkg_Fields"));
        assertTrue(text.contains("(ptr %j2ll_env, ptr %p0)"));
        String instanceHelper = runtimeTokens.helperSymbol(
                RuntimeTokenDomain.FIELD_RUNTIME,
                "field_get_instance_i32",
                instanceFieldKey);
        assertTrue(text.contains(
                "call i32 @" + instanceHelper
                        + "("
                        + localAbiCall(
                                runtimeTokens,
                                RuntimeLocalAbiDomain.FIELD,
                                "field_get_instance_i32",
                                instanceFieldKey,
                                List.of(
                                        "ptr %j2ll_env",
                                        "ptr %p0"))
                        + ")"));
        assertTrue(!text.contains("@j2ll_get_field_pkg_Fields_value_I"));
    }

    @Test
    void lowersTypedNativeFieldSlotsAndLazilyCachesReferenceSidecarPerFunctionActivation() {
        IrValue i32 = new IrValue("%p0", IrType.I32);
        IrValue i64 = new IrValue("%p1", IrType.I64);
        IrValue f32 = new IrValue("%p2", IrType.F32);
        IrValue f64 = new IrValue("%p3", IrType.F64);
        IrValue ref = new IrValue("%p4", IrType.REFERENCE);
        List<IrInstruction> instructions = new java.util.ArrayList<>();
        for (NativeFieldStorageKind kind : List.of(
                NativeFieldStorageKind.BOOLEAN,
                NativeFieldStorageKind.BYTE,
                NativeFieldStorageKind.SHORT,
                NativeFieldStorageKind.CHAR,
                NativeFieldStorageKind.INT)) {
            instructions.add(IrInstruction.fieldGet(
                    new IrValue("%get." + kind.wireName(), IrType.I32),
                    IrOpcode.GET_NATIVE_STATIC,
                    List.of(),
                    nativeSlot(kind, -1)));
            instructions.add(IrInstruction.fieldPut(
                    IrOpcode.PUT_NATIVE_STATIC,
                    List.of(i32),
                    nativeSlot(kind, -1)));
        }
        instructions.add(IrInstruction.fieldGet(
                new IrValue("%get.j", IrType.I64),
                IrOpcode.GET_NATIVE_STATIC,
                List.of(),
                nativeSlot(NativeFieldStorageKind.LONG, -1)));
        instructions.add(IrInstruction.fieldPut(
                IrOpcode.PUT_NATIVE_STATIC,
                List.of(i64),
                nativeSlot(NativeFieldStorageKind.LONG, -1)));
        instructions.add(IrInstruction.fieldGet(
                new IrValue("%get.f", IrType.F32),
                IrOpcode.GET_NATIVE_STATIC,
                List.of(),
                nativeSlot(NativeFieldStorageKind.FLOAT, -1)));
        instructions.add(IrInstruction.fieldPut(
                IrOpcode.PUT_NATIVE_STATIC,
                List.of(f32),
                nativeSlot(NativeFieldStorageKind.FLOAT, -1)));
        instructions.add(IrInstruction.fieldGet(
                new IrValue("%get.d", IrType.F64),
                IrOpcode.GET_NATIVE_STATIC,
                List.of(),
                nativeSlot(NativeFieldStorageKind.DOUBLE, -1)));
        instructions.add(IrInstruction.fieldPut(
                IrOpcode.PUT_NATIVE_STATIC,
                List.of(f64),
                nativeSlot(NativeFieldStorageKind.DOUBLE, -1)));
        instructions.add(IrInstruction.fieldGet(
                new IrValue("%get.r0", IrType.REFERENCE),
                IrOpcode.GET_NATIVE_STATIC,
                List.of(),
                nativeSlot(NativeFieldStorageKind.REFERENCE, 0)));
        instructions.add(IrInstruction.fieldPut(
                IrOpcode.PUT_NATIVE_STATIC,
                List.of(ref),
                nativeSlot(NativeFieldStorageKind.REFERENCE, 0)));
        instructions.add(IrInstruction.fieldGet(
                new IrValue("%get.r1", IrType.REFERENCE),
                IrOpcode.GET_NATIVE_STATIC,
                List.of(),
                nativeSlot(NativeFieldStorageKind.REFERENCE, 1)));
        IrMethod method = new IrMethod(
                "pkg/State",
                "exercise",
                "(IJFDLjava/lang/Object;)V",
                IrType.VOID,
                List.of(i32, i64, f32, f64, ref),
                List.of(new IrBlock("entry", instructions, IrTerminator.returnVoid())));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(
                new IrClass("pkg/State", List.of(method))));

        for (String helper : List.of(
                "call i32 @j2ll_nfs_get_z(",
                "call void @j2ll_nfs_put_z(",
                "call i32 @j2ll_nfs_get_b(",
                "call void @j2ll_nfs_put_b(",
                "call i32 @j2ll_nfs_get_s(",
                "call void @j2ll_nfs_put_s(",
                "call i32 @j2ll_nfs_get_c(",
                "call void @j2ll_nfs_put_c(",
                "call i32 @j2ll_nfs_get_i32(",
                "call void @j2ll_nfs_put_i32(",
                "call i64 @j2ll_nfs_get_i64(",
                "call void @j2ll_nfs_put_i64(",
                "call i32 @j2ll_nfs_get_f32_bits(",
                "call void @j2ll_nfs_put_f32_bits(",
                "call i64 @j2ll_nfs_get_f64_bits(",
                "call void @j2ll_nfs_put_f64_bits(")) {
            assertTrue(text.contains(helper), helper + "\n" + text);
        }
        assertTrue(text.contains("bitcast i32"));
        assertTrue(text.contains("bitcast float %p2 to i32"));
        assertTrue(text.contains("bitcast i64"));
        assertTrue(text.contains("bitcast double %p3 to i64"));
        assertEquals(1, occurrences(text, "%j2ll_nfs_ref_cache = alloca ptr"), text);
        assertEquals(1, occurrences(text, "store ptr null, ptr %j2ll_nfs_ref_cache"), text);
        assertTrue(text.contains("j2ll.nfs.prologue."));
        assertTrue(text.indexOf("j2ll.nfs.prologue.") < text.indexOf("entry:"));
        assertEquals(
                3,
                occurrences(text, "call ptr @j2ll_nfs_reference_sidecar_cached("),
                text);
        assertEquals(
                1,
                occurrences(text, "call void @j2ll_nfs_release_reference_sidecar("),
                text);
        assertEquals(2, occurrences(text, "call ptr @j2ll_nfs_get_ref("), text);
        assertEquals(1, occurrences(text, "call void @j2ll_nfs_put_ref("), text);
        assertTrue(text.contains("ptr %j2ll.nfs.sidecar."));
        assertFalse(text.contains("call ptr @j2ll_nfs_reference_sidecar("));
        assertFalse(text.contains("pkg/State#"));
    }

    @Test
    void referenceSidecarCachePrologueIsNotReenteredByABackedgeToTheIrEntry() {
        IrInstruction read = IrInstruction.fieldGet(
                new IrValue("%value", IrType.REFERENCE),
                IrOpcode.GET_NATIVE_STATIC,
                List.of(),
                nativeSlot(NativeFieldStorageKind.REFERENCE, 0));
        IrMethod method = new IrMethod(
                "pkg/State",
                "spin",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(read),
                        IrTerminator.gotoBlock("entry"))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(
                new IrClass("pkg/State", List.of(method))));

        assertEquals(1, occurrences(text, "%j2ll_nfs_ref_cache = alloca ptr"), text);
        assertEquals(1, occurrences(text, "store ptr null, ptr %j2ll_nfs_ref_cache"), text);
        assertEquals(1, occurrences(text, "call ptr @j2ll_nfs_reference_sidecar_cached("), text);
        assertTrue(text.indexOf("j2ll.nfs.prologue.") < text.indexOf("entry:"));
        assertTrue(text.contains("br label %entry"));
        assertFalse(text.contains("call void @j2ll_nfs_release_reference_sidecar("));
    }

    @Test
    void rejectsNativeFieldSlotKindThatDoesNotMatchSsaType() {
        IrValue wrong = new IrValue("%wrong", IrType.F32);
        IrInstruction instruction = IrInstruction.fieldGet(
                wrong,
                IrOpcode.GET_NATIVE_STATIC,
                List.of(),
                nativeSlot(NativeFieldStorageKind.BYTE, -1));
        IrMethod method = new IrMethod(
                "pkg/State",
                "wrong",
                "()F",
                IrType.F32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(instruction),
                        IrTerminator.returnValue(wrong))));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new LlvmModuleLowerer().lowerClass(
                        new IrClass("pkg/State", List.of(method))));

        assertTrue(error.getMessage().contains("does not match slot kind BYTE"));
    }

    private String nativeSlot(NativeFieldStorageKind kind, int referenceIndex) {
        return new NativeFieldSlotRef(
                        kind,
                        "j2ll_nfs_" + kind.wireName() + Math.max(referenceIndex, 0),
                        kind.reference() ? referenceIndex : -1)
                .encoded();
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
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

        assertTrue(text.contains("define external hidden i32 @" + new LlvmNameMangler().functionName(method)));
        assertFalse(text.contains("pkg_Div"));
        assertTrue(text.contains("(ptr %j2ll_env, i32 %p0, i32 %p1)"));
        assertTrue(text.contains("%quotient = call i32 @j2ll_rt_div_i32(ptr %j2ll_env, i32 %p0, i32 %p1)"));
        assertTrue(text.contains("%remainder = call i32 @j2ll_rt_rem_i32(ptr %j2ll_env, i32 %p0, i32 %p1)"));
        assertFalse(text.contains("sdiv i32"));
        assertFalse(text.contains("srem i32"));
    }

    @Test
    void lowersProtectedFloatAndDoubleBitcastsFromIntegerBits() {
        IrValue floatEncoded = new IrValue("%float_encoded", IrType.I32);
        IrValue floatKey = new IrValue("%float_key", IrType.I32);
        IrValue floatBits = new IrValue("%float_bits", IrType.I32);
        IrValue floatValue = new IrValue("%float_value", IrType.F32);
        IrMethod floatMethod = new IrMethod(
                "pkg/ProtectedConstants",
                "floatValue",
                "()F",
                IrType.F32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(floatEncoded, 0x12345678),
                                IrInstruction.constInt(floatKey, 0x0f0f0f0f),
                                IrInstruction.binary(floatBits, IrOpcode.XOR_I32, floatEncoded, floatKey),
                                IrInstruction.unary(floatValue, IrOpcode.BITCAST_I32_TO_F32, floatBits)),
                        IrTerminator.returnValue(floatValue))));
        IrValue doubleEncoded = new IrValue("%double_encoded", IrType.I64);
        IrValue doubleKey = new IrValue("%double_key", IrType.I64);
        IrValue doubleBits = new IrValue("%double_bits", IrType.I64);
        IrValue doubleValue = new IrValue("%double_value", IrType.F64);
        IrMethod doubleMethod = new IrMethod(
                "pkg/ProtectedConstants",
                "doubleValue",
                "()D",
                IrType.F64,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constLong(doubleEncoded, 0x123456789abcdef0L),
                                IrInstruction.constLong(doubleKey, 0x0f0f0f0f0f0f0f0fL),
                                IrInstruction.binary(doubleBits, IrOpcode.XOR_I64, doubleEncoded, doubleKey),
                                IrInstruction.unary(doubleValue, IrOpcode.BITCAST_I64_TO_F64, doubleBits)),
                        IrTerminator.returnValue(doubleValue))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(
                new IrClass("pkg/ProtectedConstants", List.of(floatMethod, doubleMethod))));

        assertTrue(text.contains("%float_bits = xor i32 %float_encoded, %float_key"));
        assertTrue(text.contains("%float_value = bitcast i32 %float_bits to float"));
        assertTrue(text.contains("ret float %float_value"));
        assertTrue(text.contains("%double_bits = xor i64 %double_encoded, %double_key"));
        assertTrue(text.contains("%double_value = bitcast i64 %double_bits to double"));
        assertTrue(text.contains("ret double %double_value"));
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

        assertTrue(text.contains("define external hidden void @" + new LlvmNameMangler().functionName(method)));
        assertFalse(text.contains("pkg_Locks"));
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

        assertTrue(text.contains("define external hidden i32 @" + new LlvmNameMangler().functionName(method)));
        assertFalse(text.contains("pkg_Arrays"));
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

        assertTrue(text.contains("call i32 @" + new LlvmNameMangler().functionName(calleeKey)));
        assertFalse(text.contains("@j2ll_pkg_Calls_callee_"));
        assertTrue(!text.contains("@j2ll_call_pkg_Calls_callee"));
    }

    @Test
    void directCallPassesTheCalleeImplicitJniContext() {
        String calleeKey = "pkg/Calls#calleeWithStatic!()I";
        String fieldKey = "pkg/Calls#VALUE!I";
        IrValue fieldValue = new IrValue("%field", IrType.I32);
        IrMethod callee = new IrMethod(
                "pkg/Calls",
                "calleeWithStatic",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.fieldGet(fieldValue, IrOpcode.GET_STATIC, List.of(), fieldKey)),
                        IrTerminator.returnValue(fieldValue))));
        IrValue result = new IrValue("%result", IrType.I32);
        IrMethod caller = new IrMethod(
                "pkg/Calls",
                "caller",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(result),
                                IrOpcode.CALL_STATIC,
                                List.of(),
                                calleeKey)),
                        IrTerminator.returnValue(result))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(
                new IrClass("pkg/Calls", List.of(callee, caller)),
                LlvmLinkage.EXTERNAL,
                LlvmVisibility.HIDDEN,
                Set.of(calleeKey)));
        String calleeSymbol = new LlvmNameMangler().functionName(calleeKey);

        assertTrue(text.contains("call i32 @" + calleeSymbol + "(ptr %j2ll_env, ptr %j2ll_owner)"));
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

        assertTrue(text.contains("call i32 @" + new LlvmNameMangler().functionName(calleeKey)));
        assertFalse(text.contains("@j2ll_pkg_Calls_privateValue_"));
        assertTrue(text.contains("(ptr %p0, i32 %p1)"));
        assertTrue(!text.contains("@j2ll_call_pkg_Calls_privateValue"));
    }

    @Test
    void lowersVirtualAndInterfaceDispatchWithArgumentsThroughLocalizedJvmHelpers() {
        IrValue receiver = new IrValue("%p0", IrType.REFERENCE);
        IrValue intArg = new IrValue("%p1", IrType.I32);
        IrValue intResult = new IrValue("%int_result", IrType.I32);
        IrValue refArg = new IrValue("%p2", IrType.REFERENCE);
        IrValue refResult = new IrValue("%ref_result", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Dispatch",
                "call",
                "(Lpkg/Base;ILjava/lang/String;)Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(receiver, intArg, refArg),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.call(
                                        Optional.of(intResult),
                                        IrOpcode.CALL_VIRTUAL,
                                        List.of(receiver, intArg),
                                        "pkg/Base#add!(I)I"),
                                IrInstruction.call(
                                        Optional.of(refResult),
                                        IrOpcode.CALL_INTERFACE,
                                        List.of(receiver, refArg),
                                        "pkg/I#name!(Ljava/lang/String;)Ljava/lang/String;")),
                        IrTerminator.returnValue(refResult))));

        String text = new LlvmTextEmitter().emit(new LlvmModuleLowerer().lowerClass(
                new IrClass("pkg/Dispatch", List.of(method))));

        RuntimeTokenMapper runtimeTokens =
                RuntimeTokenMapper.compatibility();
        String virtualHelper = runtimeTokens.helperSymbol(
                RuntimeTokenDomain.DISPATCH_METHOD,
                "virtual_dispatch_i32",
                "pkg/Base#add!(I)I");
        String interfaceHelper = runtimeTokens.helperSymbol(
                RuntimeTokenDomain.DISPATCH_METHOD,
                "interface_dispatch_ref",
                "pkg/I#name!(Ljava/lang/String;)Ljava/lang/String;");
        assertTrue(text.contains("@" + virtualHelper + "("));
        assertTrue(text.contains("@" + interfaceHelper + "("));
        assertLocalAbiInvocation(
                text,
                "call i32 @" + virtualHelper + "(",
                runtimeTokens,
                RuntimeLocalAbiDomain.DISPATCH,
                "virtual_dispatch_i32",
                "pkg/Base#add!(I)I",
                List.of(
                        "ptr %j2ll_env",
                        "ptr %p0",
                        "ptr %j2ll_args_base_"));
        assertLocalAbiInvocation(
                text,
                "call ptr @" + interfaceHelper + "(",
                runtimeTokens,
                RuntimeLocalAbiDomain.DISPATCH,
                "interface_dispatch_ref",
                "pkg/I#name!(Ljava/lang/String;)Ljava/lang/String;",
                List.of(
                        "ptr %j2ll_env",
                        "ptr %p0",
                        "ptr %j2ll_args_base_"));
        assertFalse(text.contains("vtable"));
    }

    private static String localAbiCall(
            RuntimeTokenMapper runtimeTokens,
            RuntimeLocalAbiDomain domain,
            String operation,
            String identity,
            List<String> logicalArguments) {
        RuntimeLocalAbiPlan plan = new RuntimeLocalAbiPlanner().plan(
                runtimeTokens,
                domain,
                operation,
                identity,
                logicalArguments.size());
        return String.join(
                ", ",
                plan.arrange(logicalArguments));
    }

    private static void assertLocalAbiInvocation(
            String llvm,
            String callPrefix,
            RuntimeTokenMapper runtimeTokens,
            RuntimeLocalAbiDomain domain,
            String operation,
            String identity,
            List<String> logicalArgumentPrefixes) {
        RuntimeLocalAbiPlan plan = new RuntimeLocalAbiPlanner().plan(
                runtimeTokens,
                domain,
                operation,
                identity,
                logicalArgumentPrefixes.size());
        int start = llvm.indexOf(callPrefix);
        assertTrue(start >= 0, llvm);
        int argumentsStart = start + callPrefix.length();
        int end = llvm.indexOf(')', argumentsStart);
        assertTrue(end >= argumentsStart, llvm);
        List<String> actual = List.of(
                llvm.substring(argumentsStart, end).split(", "));
        assertEquals(plan.physicalSlots().size(), actual.size(), llvm);
        for (int physical = 0;
                physical < plan.physicalSlots().size();
                physical++) {
            int logical = plan.physicalSlots().get(physical);
            assertTrue(
                    actual.get(physical).startsWith(
                            logicalArgumentPrefixes.get(logical)),
                    llvm);
        }
    }
}
