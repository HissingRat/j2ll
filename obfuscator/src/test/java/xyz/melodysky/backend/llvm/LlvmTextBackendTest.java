package xyz.melodysky.backend.llvm;

import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LlvmTextBackendTest {

    @Test
    public void testEmitsBranchingFunction() {
        IrValue local = new IrValue(0, IrType.INT, "local");
        IrValue zero = new IrValue(1, IrType.INT, "zero");
        IrValue condition = new IrValue(2, IrType.BOOLEAN, "cmp");
        IrValue thenValue = new IrValue(3, IrType.INT, "then");
        IrValue elseValue = new IrValue(4, IrType.INT, "else");

        IrMethod method = new IrMethod(
                "choose",
                IrType.INT,
                List.of(IrType.INT),
                1,
                true,
                "entry",
                List.of(
                        new IrBlock("entry", List.of(
                                new IrInstruction.LoadLocal(local, 0),
                                new IrInstruction.Const(zero, 0),
                                new IrInstruction.Compare(condition, IrCompareOpcode.EQ, local, zero)
                        ), new IrTerminator.Branch(condition, "then", "otherwise")),
                        new IrBlock("then", List.of(new IrInstruction.Const(thenValue, 1)), new IrTerminator.Return(thenValue)),
                        new IrBlock("otherwise", List.of(new IrInstruction.Const(elseValue, 2)), new IrTerminator.Return(elseValue))
                )
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/MathOps"), List.of(method))
        )));

        assertTrue(llvm.contains("define i32 @\"" + JniMangler.symbolPrefix()));
        assertTrue(llvm.contains("define i32 @\"" + JniMangler.nativeBridgeName("sample/MathOps", "choose", "(I)I") + "\"(ptr %jni.env, ptr %jni.classOrThis, i32 %jni.arg0) {"));
        assertTrue(llvm.contains("%local.0 = alloca i32"));
        assertTrue(llvm.contains("store i32 %arg0, ptr %local.0"));
        assertTrue(llvm.contains("br i1 %cmp2, label %then, label %otherwise"));
        assertTrue(llvm.contains("ret i32 %then3"));
    }

    @Test
    public void testEmitsDistinctNativeBridgeNamesForMethodsWithDifferentReturnTypes() {
        IrValue objectValue = new IrValue(0, IrType.reference("sample/Thing"), "value");
        IrMethod objectMethod = new IrMethod(
                "a",
                IrType.reference("sample/Thing"),
                List.of(),
                1,
                false,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.Const(objectValue, null)
                ), new IrTerminator.Return(objectValue)))
        );

        IrValue intValue = new IrValue(1, IrType.INT, "value");
        IrMethod intMethod = new IrMethod(
                "a",
                IrType.INT,
                List.of(),
                1,
                false,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.Const(intValue, 7)
                ), new IrTerminator.Return(intValue)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/Overloads"), List.of(objectMethod, intMethod))
        )));

        assertTrue(llvm.contains("define ptr @\"" + JniMangler.nativeBridgeName("sample/Overloads", "a", "()Lsample/Thing;") + "\""));
        assertTrue(llvm.contains("define i32 @\"" + JniMangler.nativeBridgeName("sample/Overloads", "a", "()I") + "\""));
    }

    @Test
    public void testEmitsSyntheticPrologueSoEntryBackedgesDoNotLoopOverAllocas() {
        IrValue receiver = new IrValue(0, IrType.reference("sample/Worker"), "receiver");

        IrMethod method = new IrMethod(
                "spin",
                IrType.VOID,
                List.of(),
                1,
                false,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(receiver, 0)
                ), new IrTerminator.Goto("entry")))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/Worker"), List.of(method))
        )));

        assertTrue(llvm.contains("define void @\"" + JniMangler.symbolPrefix()));
        assertTrue(llvm.contains("prologue:\n  %local.0 = alloca ptr\n  store ptr %arg0, ptr %local.0\n  br label %entry"));
        assertTrue(llvm.contains("entry:\n  %receiver0 = load ptr, ptr %local.0\n  br label %entry"));
    }

    @Test
    public void testEmitsHelperDeclarationsForFieldsAndCalls() {
        IrValue receiver = new IrValue(0, IrType.reference("sample/Worker"), "receiver");
        IrValue argument = new IrValue(1, IrType.INT, "arg");
        IrValue fieldValue = new IrValue(2, IrType.INT, "field");
        IrValue callValue = new IrValue(3, IrType.INT, "call");

        IrMethod method = new IrMethod(
                "mix",
                IrType.INT,
                List.of(IrType.reference("sample/Worker"), IrType.INT),
                2,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(receiver, 0),
                        new IrInstruction.LoadLocal(argument, 1),
                        new IrInstruction.LoadStaticField(fieldValue, new IrFieldRef(new IrClassRef("sample/Holder"), "VALUE", IrType.INT, true)),
                        new IrInstruction.Invoke(
                                callValue,
                                new IrMethodRef(
                                        new IrClassRef("sample/Worker"),
                                        "mix",
                                        IrType.INT,
                                        List.of(IrType.INT),
                                        IrMethodRef.CallKind.VIRTUAL
                                ),
                                List.of(receiver, argument)
                        )
                ), new IrTerminator.Return(callValue)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/Caller"), List.of(method))
        )));

        assertTrue(llvm.contains("; helper-meta " + JniMangler.symbolPrefix()));
        assertTrue(llvm.contains("= ir_rt_get_static__73616d706c652f486f6c646572__56414c5545__696e74"));
        assertTrue(llvm.contains("= ir_rt_call__virtual__73616d706c652f576f726b6572__6d6978__696e74__696e74"));
        assertTrue(llvm.contains("call i32 @\"" + JniMangler.symbolPrefix()));
    }

    @Test
    public void testEmitsBooleanConstAsLiteralValue() {
        IrValue boolValue = new IrValue(0, IrType.BOOLEAN, "flag");

        IrMethod method = new IrMethod(
                "flag",
                IrType.BOOLEAN,
                List.of(),
                0,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.Const(boolValue, false)
                ), new IrTerminator.Return(boolValue)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/Flags"), List.of(method))
        )));

        assertTrue(llvm.contains("%flag0 = or i1 false, false"));
    }

    @Test
    public void testEmitsLongConstAsLiteralValue() {
        IrValue longValue = new IrValue(0, IrType.LONG, "delay");

        IrMethod method = new IrMethod(
                "delay",
                IrType.LONG,
                List.of(),
                0,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.Const(longValue, 50L)
                ), new IrTerminator.Return(longValue)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/Timers"), List.of(method))
        )));

        assertTrue(llvm.contains("%delay0 = add i64 0, 50"));
    }

    @Test
    public void testEmitsFloatArithmeticAndConstant() {
        IrValue left = new IrValue(0, IrType.FLOAT, "left");
        IrValue right = new IrValue(1, IrType.FLOAT, "right");
        IrValue bias = new IrValue(2, IrType.FLOAT, "bias");
        IrValue sum = new IrValue(3, IrType.FLOAT, "sum");

        IrMethod method = new IrMethod(
                "mix",
                IrType.FLOAT,
                List.of(IrType.FLOAT, IrType.FLOAT),
                2,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(left, 0),
                        new IrInstruction.LoadLocal(right, 1),
                        new IrInstruction.Const(bias, 1.5f),
                        new IrInstruction.Binary(sum, IrBinaryOpcode.ADD, left, right)
                ), new IrTerminator.Return(sum)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/Floats"), List.of(method))
        )));

        assertTrue(llvm.contains("%bias2 = fadd float 0.0, 0x"));
        assertTrue(llvm.contains("%sum3 = fadd float %left0, %right1"));
    }

    @Test
    public void testUsesWideParameterLocalSlots() {
        IrValue tail = new IrValue(0, IrType.INT, "tail");

        IrMethod method = new IrMethod(
                "pickThird",
                IrType.INT,
                List.of(IrType.INT, IrType.DOUBLE, IrType.INT),
                4,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(tail, 3)
                ), new IrTerminator.Return(tail)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/WideSlots"), List.of(method))
        )));

        assertTrue(llvm.contains("%local.3 = alloca i32"));
        assertTrue(llvm.contains("store i32 %arg2, ptr %local.3"));
        assertTrue(llvm.contains("%tail0 = load i32, ptr %local.3"));
    }

    @Test
    public void testDeclaresThrowHelperWhenMethodThrows() {
        IrValue exception = new IrValue(0, IrType.reference("java/lang/Throwable"), "ex");

        IrMethod method = new IrMethod(
                "boom",
                IrType.VOID,
                List.of(),
                0,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.CallHelper(exception, "ir_rt_current_exception", List.of())
                ), new IrTerminator.Throw(exception)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/Throws"), List.of(method))
        )));

        assertTrue(llvm.contains("= ir_rt_throw"));
        assertTrue(llvm.contains("declare void @\"" + JniMangler.symbolPrefix()));
        assertTrue(llvm.contains("call void @\"" + JniMangler.symbolPrefix()));
        assertTrue(llvm.contains("ret void\n"));
        assertTrue(!llvm.contains("call void @\"ir_rt_throw\"(ptr %ex0)"));
    }

    @Test
    public void testReturnsDefaultValueAfterThrowInNonVoidMethod() {
        IrValue exception = new IrValue(0, IrType.reference("java/lang/Throwable"), "ex");

        IrMethod method = new IrMethod(
                "boom",
                IrType.reference("java/lang/String"),
                List.of(),
                0,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.CallHelper(exception, "ir_rt_current_exception", List.of())
                ), new IrTerminator.Throw(exception)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/ThrowsRef"), List.of(method))
        )));

        assertTrue(llvm.contains("= ir_rt_throw"));
        assertTrue(llvm.contains("ret ptr null\n"));
        assertTrue(!llvm.contains("call void @\"ir_rt_throw\"(ptr %ex0)"));
    }

    @Test
    public void testLowersInProgramStaticInvokeToDirectCall() {
        IrValue left = new IrValue(0, IrType.INT, "left");
        IrValue right = new IrValue(1, IrType.INT, "right");
        IrValue sum = new IrValue(2, IrType.INT, "sum");
        IrMethod add = new IrMethod(
                "add",
                IrType.INT,
                List.of(IrType.INT, IrType.INT),
                2,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(left, 0),
                        new IrInstruction.LoadLocal(right, 1),
                        new IrInstruction.Binary(sum, IrBinaryOpcode.ADD, left, right)
                ), new IrTerminator.Return(sum)))
        );

        IrValue arg0 = new IrValue(3, IrType.INT, "arg0");
        IrValue arg1 = new IrValue(4, IrType.INT, "arg1");
        IrValue call = new IrValue(5, IrType.INT, "call");
        IrMethod callAdd = new IrMethod(
                "callAdd",
                IrType.INT,
                List.of(IrType.INT, IrType.INT),
                2,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(arg0, 0),
                        new IrInstruction.LoadLocal(arg1, 1),
                        new IrInstruction.Invoke(
                                call,
                                new IrMethodRef(
                                        new IrClassRef("sample/MathOps"),
                                        "add",
                                        IrType.INT,
                                        List.of(IrType.INT, IrType.INT),
                                        IrMethodRef.CallKind.STATIC
                                ),
                                List.of(arg0, arg1)
                        )
                ), new IrTerminator.Return(call)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/MathOps"), List.of(add, callAdd))
        )));

        assertTrue(llvm.contains("call i32 @\"" + JniMangler.symbolPrefix()));
        assertTrue(llvm.contains("define internal i32 @\"" + JniMangler.symbolPrefix()));
        assertTrue(llvm.contains("call i32 @\"" + JniMangler.symbolPrefix()));
        assertTrue(!llvm.contains("declare i32 @\"ir_rt_call__static__sample_s_MathOps__add__int__int__int\"(i32, i32)"));
    }

    @Test
    public void testLowersInProgramSpecialInvokeToDirectCall() {
        IrValue receiver = new IrValue(0, IrType.reference("sample/Worker"), "receiver");
        IrValue arg = new IrValue(1, IrType.INT, "arg");
        IrValue plusOne = new IrValue(2, IrType.INT, "plusOne");
        IrMethod helper = new IrMethod(
                "helper",
                IrType.INT,
                List.of(IrType.INT),
                2,
                false,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(receiver, 0),
                        new IrInstruction.LoadLocal(arg, 1),
                        new IrInstruction.Const(new IrValue(3, IrType.INT, "one"), 1),
                        new IrInstruction.Binary(plusOne, IrBinaryOpcode.ADD, arg, new IrValue(3, IrType.INT, "one"))
                ), new IrTerminator.Return(plusOne)))
        );

        IrValue callReceiver = new IrValue(4, IrType.reference("sample/Worker"), "receiver");
        IrValue callArg = new IrValue(5, IrType.INT, "arg");
        IrValue callResult = new IrValue(6, IrType.INT, "call");
        IrMethod callHelper = new IrMethod(
                "callHelper",
                IrType.INT,
                List.of(IrType.INT),
                2,
                false,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(callReceiver, 0),
                        new IrInstruction.LoadLocal(callArg, 1),
                        new IrInstruction.Invoke(
                                callResult,
                                new IrMethodRef(
                                        new IrClassRef("sample/Worker"),
                                        "helper",
                                        IrType.INT,
                                        List.of(IrType.INT),
                                        IrMethodRef.CallKind.SPECIAL
                                ),
                                List.of(callReceiver, callArg)
                        )
                ), new IrTerminator.Return(callResult)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/Worker"), List.of(helper, callHelper))
        )));

        assertTrue(llvm.contains("call i32 @\"" + JniMangler.symbolPrefix()));
        assertTrue(llvm.contains("define internal i32 @\"" + JniMangler.symbolPrefix()));
        assertTrue(llvm.contains("call i32 @\"" + JniMangler.symbolPrefix()));
        assertTrue(!llvm.contains("declare i32 @\"ir_rt_call__special__sample_s_Worker__helper__int__int\""));
    }

    @Test
    public void testLowersInProgramPrivateVirtualInvokeToDirectCall() {
        IrValue receiver = new IrValue(0, IrType.reference("sample/FontRenderer"), "receiver");
        IrValue arg = new IrValue(1, IrType.INT, "arg");
        IrMethod helper = new IrMethod(
                "helper",
                IrType.VOID,
                List.of(IrType.INT),
                2,
                false,
                true,
                false,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(receiver, 0),
                        new IrInstruction.LoadLocal(arg, 1)
                ), new IrTerminator.ReturnVoid()))
        );

        IrValue callReceiver = new IrValue(2, IrType.reference("sample/FontRenderer"), "receiver");
        IrValue callArg = new IrValue(3, IrType.INT, "arg");
        IrMethod callHelper = new IrMethod(
                "callHelper",
                IrType.VOID,
                List.of(IrType.INT),
                2,
                false,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(callReceiver, 0),
                        new IrInstruction.LoadLocal(callArg, 1),
                        new IrInstruction.Invoke(
                                new IrValue(4, IrType.VOID, "void"),
                                new IrMethodRef(
                                        new IrClassRef("sample/FontRenderer"),
                                        "helper",
                                        IrType.VOID,
                                        List.of(IrType.INT),
                                        IrMethodRef.CallKind.VIRTUAL
                                ),
                                List.of(callReceiver, callArg)
                        )
                ), new IrTerminator.ReturnVoid()))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/FontRenderer"), List.of(helper, callHelper))
        )));

        assertTrue(llvm.contains("call void @\"" + JniMangler.symbolPrefix()));
        assertTrue(llvm.contains("define internal void @\"" + JniMangler.symbolPrefix()));
        assertTrue(llvm.contains("call void @\"" + JniMangler.symbolPrefix()));
        assertTrue(!llvm.contains("declare void @\"ir_rt_call__virtual__sample_s_FontRenderer__helper__void__int\""));
    }

    @Test
    public void testEmitsAllocationHelperDeclaration() {
        IrValue created = new IrValue(0, IrType.reference("sample/Worker"), "created");

        IrMethod method = new IrMethod(
                "makeWorker",
                IrType.reference("sample/Worker"),
                List.of(),
                0,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.NewObject(created, new IrClassRef("sample/Worker"))
                ), new IrTerminator.Return(created)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/Factory"), List.of(method))
        )));

        assertTrue(llvm.contains("= ir_rt_new__" + helperToken("sample/Worker")));
        assertTrue(llvm.contains("call ptr @\"" + JniMangler.symbolPrefix()));
    }

    @Test
    public void testEncodesNestedClassHelperNamesWithoutLosingDollarSign() {
        IrValue owner = new IrValue(0, IrType.reference("bench/FeatureScenarios$Pair"), "owner");
        IrValue result = new IrValue(1, IrType.INT, "field");

        IrMethod method = new IrMethod(
                "readLeft",
                IrType.INT,
                List.of(IrType.reference("bench/FeatureScenarios$Pair")),
                1,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(owner, 0),
                        new IrInstruction.LoadField(
                                result,
                                new IrFieldRef(new IrClassRef("bench/FeatureScenarios$Pair"), "left", IrType.INT, false),
                                owner
                        )
                ), new IrTerminator.Return(result)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("bench/Reader"), List.of(method))
        )));

        assertTrue(llvm.contains("= ir_rt_get_field__"
                + helperToken("bench/FeatureScenarios$Pair")
                + "__" + helperToken("left")
                + "__" + helperToken("int")));
    }

    @Test
    public void testLowersReferenceEqualityToRuntimeHelper() {
        IrValue left = new IrValue(0, IrType.reference("net/minecraft/class_320"), "left");
        IrValue right = new IrValue(1, IrType.reference("net/minecraft/class_320"), "right");
        IrValue cmp = new IrValue(2, IrType.BOOLEAN, "cmp");

        IrMethod method = new IrMethod(
                "sameUser",
                IrType.BOOLEAN,
                List.of(IrType.reference("net/minecraft/class_320"), IrType.reference("net/minecraft/class_320")),
                2,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(left, 0),
                        new IrInstruction.LoadLocal(right, 1),
                        new IrInstruction.Compare(cmp, IrCompareOpcode.EQ, left, right)
                ), new IrTerminator.Return(cmp)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/Refs"), List.of(method))
        )));

        assertTrue(llvm.contains("= ir_rt_ref_eq"));
        assertTrue(llvm.contains("%cmp2 = call i1 @\"" + JniMangler.symbolPrefix()));
        assertTrue(!llvm.contains("@\"ir_rt_ref_eq\"(ptr, ptr)"));
    }

    @Test
    public void testLowersNewAndConstructorPairToCombinedHelper() {
        IrValue created = new IrValue(0, IrType.reference("java/net/URI"), "created");
        IrValue text = new IrValue(1, IrType.reference("java/lang/String"), "text");

        IrMethod method = new IrMethod(
                "makeUri",
                IrType.reference("java/net/URI"),
                List.of(IrType.reference("java/lang/String")),
                1,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(text, 0),
                        new IrInstruction.NewObject(created, new IrClassRef("java/net/URI")),
                        new IrInstruction.Invoke(
                                new IrValue(2, IrType.VOID, "ignored"),
                                new IrMethodRef(
                                        new IrClassRef("java/net/URI"),
                                        "<init>",
                                        IrType.VOID,
                                        List.of(IrType.reference("java/lang/String")),
                                        IrMethodRef.CallKind.SPECIAL
                                ),
                                List.of(created, text)
                        )
                ), new IrTerminator.Return(created)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/Uris"), List.of(method))
        )));

        assertTrue(llvm.contains("= ir_rt_new_init__" + helperToken("java/net/URI") + "__" + helperToken("java/lang/String")));
        assertTrue(llvm.contains("%created0 = call ptr @\"" + JniMangler.symbolPrefix()));
        assertTrue(!llvm.contains("call ptr @\"ir_rt_new__" + helperToken("java/net/URI") + "\"()"));
        assertTrue(!llvm.contains("call void @\"ir_rt_call__special__"
                + helperToken("java/net/URI")
                + "__" + helperToken("<init>")
                + "__" + helperToken("java/lang/String")
                + "__" + helperToken("void") + "\""));
    }

    @Test
    public void testLowersConstructorPairWithInterveningArgumentsToCombinedHelper() {
        IrValue created = new IrValue(0, IrType.reference("sample/Task"), "created");
        IrValue outer = new IrValue(1, IrType.reference("sample/Owner"), "outer");
        IrValue state = new IrValue(2, IrType.reference("sample/State"), "state");

        IrMethod method = new IrMethod(
                "makeTask",
                IrType.reference("sample/Task"),
                List.of(IrType.reference("sample/Owner"), IrType.reference("sample/State")),
                2,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(outer, 0),
                        new IrInstruction.LoadLocal(state, 1),
                        new IrInstruction.NewObject(created, new IrClassRef("sample/Task")),
                        new IrInstruction.Invoke(
                                new IrValue(3, IrType.VOID, "ignored"),
                                new IrMethodRef(
                                        new IrClassRef("sample/Task"),
                                        "<init>",
                                        IrType.VOID,
                                        List.of(IrType.reference("sample/Owner"), IrType.reference("sample/State")),
                                        IrMethodRef.CallKind.SPECIAL
                                ),
                                List.of(created, outer, state)
                        )
                ), new IrTerminator.Return(created)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/Tasks"), List.of(method))
        )));

        assertTrue(llvm.contains("= ir_rt_new_init__" + helperToken("sample/Task")
                + "__" + helperToken("sample/Owner")
                + "__" + helperToken("sample/State")));
        assertTrue(llvm.contains("%created0 = call ptr @\"" + JniMangler.symbolPrefix()));
        assertTrue(!llvm.contains("call ptr @\"ir_rt_new__" + helperToken("sample/Task") + "\"()"));
        assertTrue(!llvm.contains("call void @\"ir_rt_call__special__"
                + helperToken("sample/Task")
                + "__" + helperToken("<init>")
                + "__" + helperToken("sample/Owner")
                + "__" + helperToken("sample/State")
                + "__" + helperToken("void") + "\""));
    }

    @Test
    public void testLowersNestedConstructorPairsToCombinedHelpers() {
        IrValue createdOuter = new IrValue(0, IrType.reference("sample/SocketClient"), "outer");
        IrValue createdUri = new IrValue(1, IrType.reference("java/net/URI"), "uri");
        IrValue text = new IrValue(2, IrType.reference("java/lang/String"), "text");

        IrMethod method = new IrMethod(
                "makeClient",
                IrType.reference("sample/SocketClient"),
                List.of(IrType.reference("java/lang/String")),
                1,
                true,
                "entry",
                List.of(new IrBlock("entry", List.of(
                        new IrInstruction.LoadLocal(text, 0),
                        new IrInstruction.NewObject(createdOuter, new IrClassRef("sample/SocketClient")),
                        new IrInstruction.NewObject(createdUri, new IrClassRef("java/net/URI")),
                        new IrInstruction.Invoke(
                                new IrValue(3, IrType.VOID, "uriInit"),
                                new IrMethodRef(
                                        new IrClassRef("java/net/URI"),
                                        "<init>",
                                        IrType.VOID,
                                        List.of(IrType.reference("java/lang/String")),
                                        IrMethodRef.CallKind.SPECIAL
                                ),
                                List.of(createdUri, text)
                        ),
                        new IrInstruction.Invoke(
                                new IrValue(4, IrType.VOID, "clientInit"),
                                new IrMethodRef(
                                        new IrClassRef("sample/SocketClient"),
                                        "<init>",
                                        IrType.VOID,
                                        List.of(IrType.reference("java/net/URI")),
                                        IrMethodRef.CallKind.SPECIAL
                                ),
                                List.of(createdOuter, createdUri)
                        )
                ), new IrTerminator.Return(createdOuter)))
        );

        String llvm = new LlvmTextBackend().emit(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/Clients"), List.of(method))
        )));

        assertTrue(llvm.contains("= ir_rt_new_init__" + helperToken("java/net/URI") + "__" + helperToken("java/lang/String")));
        assertTrue(llvm.contains("= ir_rt_new_init__" + helperToken("sample/SocketClient") + "__" + helperToken("java/net/URI")));
        assertTrue(llvm.contains("%uri1 = call ptr @\"" + JniMangler.symbolPrefix()));
        assertTrue(llvm.contains("%outer0 = call ptr @\"" + JniMangler.symbolPrefix()));
        assertTrue(!llvm.contains("call ptr @\"ir_rt_new__" + helperToken("sample/SocketClient") + "\"()"));
        assertTrue(!llvm.contains("call ptr @\"ir_rt_new__" + helperToken("java/net/URI") + "\"()"));
        assertTrue(!llvm.contains("call void @\"ir_rt_call__special__"
                + helperToken("sample/SocketClient")
                + "__" + helperToken("<init>")
                + "__" + helperToken("java/net/URI")
                + "__" + helperToken("void") + "\""));
    }

    @Test
    public void testSplitsLargeClassAcrossMultipleShards() {
        IrClassRef classRef = new IrClassRef("sample/HugeClass");
        List<IrMethod> methods = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> hugeMethod("m" + index))
                .toList();

        LlvmTextBackend.ModuleSet moduleSet = new LlvmTextBackend(8 * 1024).emitModuleSet(
                new IrProgram(List.of(new IrClass(classRef, methods))),
                16
        );

        long shardMentions = moduleSet.shardModules().stream()
                .filter(fragment -> fragment.fileName().startsWith("shard-"))
                .filter(fragment -> fragment.llvmText().contains("; class " + classRef.internalName()))
                .count();

        assertTrue(shardMentions > 1, "expected a huge single class to be split across multiple shards");
    }

    @Test
    public void testMaxShardBytesCanForceAdditionalLlvmShards() {
        IrClassRef classRef = new IrClassRef("sample/CappedClass");
        List<IrMethod> methods = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> hugeMethod("cap" + index))
                .toList();

        LlvmTextBackend.ModuleSet moduleSet = new LlvmTextBackend(16 * 1024 * 1024, 8 * 1024).emitModuleSet(
                new IrProgram(List.of(new IrClass(classRef, methods))),
                1
        );

        long shardMentions = moduleSet.shardModules().stream()
                .filter(fragment -> fragment.fileName().startsWith("shard-"))
                .filter(fragment -> fragment.llvmText().contains("; class " + classRef.internalName()))
                .count();

        assertTrue(shardMentions > 1, "expected maxShardBytes to force more than one LLVM shard");
    }

    private IrMethod hugeMethod(String name) {
        java.util.ArrayList<IrInstruction> instructions = new java.util.ArrayList<>();
        IrValue lastValue = null;
        for (int index = 0; index < 1000; index++) {
            IrValue value = new IrValue(index, IrType.INT, "v" + index);
            instructions.add(new IrInstruction.Const(value, index));
            lastValue = value;
        }
        return new IrMethod(
                name,
                IrType.INT,
                List.of(),
                0,
                true,
                "entry",
                List.of(new IrBlock("entry", instructions, new IrTerminator.Return(lastValue)))
        );
    }

    private String helperToken(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            builder.append(String.format("%02x", current & 0xff));
        }
        return builder.toString();
    }
}
