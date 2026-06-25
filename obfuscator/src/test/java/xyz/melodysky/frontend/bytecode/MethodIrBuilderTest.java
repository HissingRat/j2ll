package xyz.melodysky.frontend.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import xyz.melodysky.ir.model.*;
import xyz.melodysky.ir.validate.IrMethodValidator;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

public class MethodIrBuilderTest {

    @Test
    public void testBuildsStraightLineIntegerMethod() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "add", "(II)I", null, null);
        methodNode.maxLocals = 2;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        methodNode.instructions.add(new InsnNode(Opcodes.IADD));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertEquals("add", irMethod.name());
        assertEquals(IrType.INT, irMethod.returnType());
        assertEquals(2, irMethod.parameterTypes().size());
        assertEquals(3, irMethod.blocks().get(0).instructions().size());
        assertTrue(irMethod.blocks().get(0).instructions().get(0) instanceof IrInstruction.LoadLocal);
        assertTrue(irMethod.blocks().get(0).instructions().get(1) instanceof IrInstruction.LoadLocal);
        IrInstruction.Binary binary = (IrInstruction.Binary) irMethod.blocks().get(0).instructions().get(2);
        assertEquals(IrBinaryOpcode.ADD, binary.opcode());
        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.Return);
    }

    @Test
    public void testBuildsConditionalBranchCfg() {
        LabelNode elseLabel = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "choose", "(I)I", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.IFEQ, elseLabel));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.instructions.add(elseLabel);
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertEquals(3, irMethod.blocks().size());
        IrBlock entry = irMethod.blocks().get(0);
        assertEquals("entry", entry.label());
        assertEquals(3, entry.instructions().size());
        assertTrue(entry.instructions().get(0) instanceof IrInstruction.LoadLocal);
        assertTrue(entry.instructions().get(1) instanceof IrInstruction.Const);
        IrInstruction.Compare compare = (IrInstruction.Compare) entry.instructions().get(2);
        assertEquals(IrCompareOpcode.EQ, compare.opcode());
        IrTerminator.Branch branch = (IrTerminator.Branch) entry.terminator();
        assertEquals("block0", branch.trueTarget());
        assertEquals("block1", branch.falseTarget());

        assertTrue(findBlock(irMethod, "block0").terminator() instanceof IrTerminator.Return);
        assertTrue(findBlock(irMethod, "block1").terminator() instanceof IrTerminator.Return);
    }

    @Test
    public void testBuildsGotoBlock() {
        LabelNode target = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "jumpy", "()I", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new JumpInsnNode(Opcodes.GOTO, target));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_2));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.instructions.add(target);
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertEquals(3, irMethod.blocks().size());
        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.Goto);
        IrTerminator.Goto goTo = (IrTerminator.Goto) irMethod.blocks().get(0).terminator();
        assertEquals("block0", goTo.targetBlock());
    }

    @Test
    public void testBuildsStackCarriedAcrossGotoBoundary() {
        LabelNode target = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "badStack", "()I", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.GOTO, target));
        methodNode.instructions.add(target);
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertEquals(1, irMethod.maxLocals());
        assertTrue(findBlock(irMethod, "block0").instructions().get(0) instanceof IrInstruction.LoadLocal);
        assertTrue(findBlock(irMethod, "block0").terminator() instanceof IrTerminator.Return);
    }

    @Test
    public void testBuildsStackCarriedTernaryJoin() {
        LabelNode elseLabel = new LabelNode();
        LabelNode joinLabel = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "select", "(I)I", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.IFEQ, elseLabel));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_2));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.GOTO, joinLabel));
        methodNode.instructions.add(elseLabel);
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_3));
        methodNode.instructions.add(joinLabel);
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.maxLocals() > 1);
        IrBlock joinBlock = findBlock(irMethod, "block2");
        assertTrue(joinBlock.instructions().get(0) instanceof IrInstruction.LoadLocal);
        assertTrue(joinBlock.terminator() instanceof IrTerminator.Return);
    }

    @Test
    public void testBuildsStaticFieldFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fieldFlow", "()I", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "sample/Holder", "VALUE", "I"));
        methodNode.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, "sample/Holder", "CACHE", "I"));
        methodNode.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "sample/Holder", "CACHE", "I"));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(IrInstruction.LoadStaticField.class::isInstance));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(IrInstruction.StoreStaticField.class::isInstance));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(IrInstruction.CallHelper.class::isInstance)
                .map(IrInstruction.CallHelper.class::cast)
                .anyMatch(helper -> "ir_rt_exception_pending".equals(helper.helperName())));
    }

    @Test
    public void testBuildsStaticInvokeFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callFlow", "()I", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_2));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_3));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "sample/MathOps", "mix", "(II)I", false));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(2) instanceof IrInstruction.Invoke);
        IrInstruction.Invoke invoke = (IrInstruction.Invoke) irMethod.blocks().get(0).instructions().get(2);
        assertEquals("mix", invoke.method().name());
        assertEquals(2, invoke.arguments().size());
        assertEquals(IrType.INT, invoke.method().returnType());
    }

    @Test
    public void testBuildsStaticInvokeReferenceReturn() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()Ljava/lang/String;", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "sample/Factory", "make", "()Ljava/lang/String;", false));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        IrInstruction.Invoke invoke = findFirstInstruction(irMethod, IrInstruction.Invoke.class);
        assertEquals(IrType.reference("java/lang/String"), invoke.method().returnType());
        assertTrue(irMethod.blocks().stream().anyMatch(block -> block.terminator() instanceof IrTerminator.Return));
    }

    @Test
    public void testBuildsClassLiteralLdcFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "valueOf", "(Ljava/lang/String;)Lsample/Mode;", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new LdcInsnNode(Type.getObjectType("sample/Mode")));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false));
        methodNode.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "sample/Mode"));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        IrInstruction.CallHelper helper = findFirstInstruction(irMethod, IrInstruction.CallHelper.class);
        assertEquals("ir_rt_ldc_class__" + helperToken("sample/Mode"), helper.helperName());
        assertTrue(irMethod.blocks().stream().anyMatch(block -> block.terminator() instanceof IrTerminator.Return));
    }

    @Test
    public void testBuildsStringConcatInvokeDynamicFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "join", "(Ljava/lang/String;I)Ljava/lang/String;", null, null);
        methodNode.maxLocals = 2;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "makeConcatWithConstants",
                "(Ljava/lang/String;I)Ljava/lang/String;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false
                ),
                "\u0001|\u0001"
        ));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        IrInstruction.CallHelper helper = findFirstInstruction(irMethod, IrInstruction.CallHelper.class);
        assertTrue(helper.helperName().startsWith("ir_rt_concat__"));
        assertEquals(2, helper.arguments().size());
        assertEquals(IrType.reference("java/lang/String"), helper.result().type());
        assertTrue(irMethod.blocks().stream().anyMatch(block -> block.terminator() instanceof IrTerminator.Return));
    }

    @Test
    public void testBuildsStringConcatInvokeDynamicWithConstantsFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "join", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false
                ),
                "\u0002=\u0001",
                "name"
        ));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        IrInstruction.CallHelper helper = findFirstInstruction(irMethod, IrInstruction.CallHelper.class);
        assertTrue(helper.helperName().contains("6e616d653d"));
        assertEquals(1, helper.arguments().size());
        assertEquals(IrType.reference("java/lang/String"), helper.result().type());
    }

    @Test
    public void testBuildsLambdaMetafactoryInvokeDynamicFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "factory", "()Ljava/util/function/Function;", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "apply",
                "()Ljava/util/function/Function;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory",
                        "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        false
                ),
                Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "sample/Helpers",
                        "up",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        false
                ),
                Type.getMethodType("(Ljava/lang/String;)Ljava/lang/String;")
        ));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        IrInstruction.CallHelper helper = findFirstInstruction(irMethod, IrInstruction.CallHelper.class);
        assertTrue(helper.helperName().startsWith("ir_rt_lambda__"));
        assertTrue(helper.helperName().contains("__737461746963"));
        assertEquals(IrType.reference("java/util/function/Function"), helper.result().type());
        assertTrue(irMethod.blocks().stream().anyMatch(block -> block.terminator() instanceof IrTerminator.Return));
    }

    @Test
    public void testBuildsCustomInterfaceLambdaMetafactoryInvokeDynamicFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "factory", "()Lsample/Transformer;", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "apply",
                "()Lsample/Transformer;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory",
                        "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        false
                ),
                Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "sample/Helpers",
                        "triple",
                        "(Ljava/lang/Integer;)Ljava/lang/Integer;",
                        false
                ),
                Type.getMethodType("(Ljava/lang/Integer;)Ljava/lang/Integer;")
        ));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        IrInstruction.CallHelper helper = findFirstInstruction(irMethod, IrInstruction.CallHelper.class);
        assertTrue(helper.helperName().startsWith("ir_rt_lambda__73616d706c652f5472616e73666f726d6572__6170706c79__"));
        assertEquals(IrType.reference("sample/Transformer"), helper.result().type());
        assertTrue(irMethod.blocks().stream().anyMatch(block -> block.terminator() instanceof IrTerminator.Return));
    }

    @Test
    public void testBuildsCapturingLambdaMetafactoryInvokeDynamicFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "factory", "(Lsample/Worker;)Ljava/lang/Runnable;", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "run",
                "(Lsample/Worker;)Ljava/lang/Runnable;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory",
                        "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        false
                ),
                Type.getMethodType("()V"),
                new Handle(
                        Opcodes.H_INVOKESPECIAL,
                        "sample/Worker",
                        "tick",
                        "()V",
                        false
                ),
                Type.getMethodType("()V")
        ));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build("sample/Factory", methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(1) instanceof IrInstruction.CallHelper);
        IrInstruction.CallHelper helper = (IrInstruction.CallHelper) irMethod.blocks().get(0).instructions().get(1);
        assertTrue(helper.helperName().startsWith("ir_rt_lambda__"));
        assertTrue(helper.helperName().contains("__7370656369616c"));
        assertEquals(1, helper.arguments().size());
        assertEquals(IrType.reference("sample/Worker"), helper.arguments().get(0).type());
        assertEquals(IrType.reference("java/lang/Runnable"), helper.result().type());
    }

    @Test
    public void testBuildsConstructorLambdaMetafactoryInvokeDynamicFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "factory", "()Ljava/util/function/Supplier;", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "get",
                "()Ljava/util/function/Supplier;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory",
                        "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        false
                ),
                Type.getMethodType("()Ljava/lang/Object;"),
                new Handle(
                        Opcodes.H_NEWINVOKESPECIAL,
                        "sample/Widget",
                        "<init>",
                        "()V",
                        false
                ),
                Type.getMethodType("()Lsample/Widget;")
        ));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build("sample/Factory", methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(0) instanceof IrInstruction.CallHelper);
        IrInstruction.CallHelper helper = (IrInstruction.CallHelper) irMethod.blocks().get(0).instructions().get(0);
        assertTrue(helper.helperName().startsWith("ir_rt_lambda__"));
        assertTrue(helper.helperName().contains("__636f6e7374727563746f72"));
        assertEquals(IrType.reference("java/util/function/Supplier"), helper.result().type());
    }

    @Test
    public void testBuildsAltLambdaMetafactoryInvokeDynamicFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "factory", "()Lsample/SerializableFunction;", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "apply",
                "()Lsample/SerializableFunction;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory",
                        "altMetafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false
                ),
                Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "sample/Helpers",
                        "box",
                        "(Ljava/lang/Integer;)Ljava/lang/Integer;",
                        false
                ),
                Type.getMethodType("(Ljava/lang/Integer;)Ljava/lang/Integer;"),
                Integer.valueOf(1 | 4),
                Integer.valueOf(0)
        ));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build("sample/Factory", methodNode);

        IrInstruction.CallHelper helper = findFirstInstruction(irMethod, IrInstruction.CallHelper.class);
        assertTrue(helper.helperName().startsWith("ir_rt_lambda__"));
        assertTrue(helper.helperName().contains("616c744d657461666163746f7279"));
        assertTrue(helper.helperName().contains("02616c744d657461666163746f727902350202"));
        assertEquals(IrType.reference("sample/SerializableFunction"), helper.result().type());
    }

    @Test
    public void testBuildsTypeSwitchInvokeDynamicFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "match", "(Lsample/Expr;I)I", null, null);
        methodNode.maxLocals = 2;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "typeSwitch",
                "(Lsample/Expr;I)I",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/runtime/SwitchBootstraps",
                        "typeSwitch",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false
                ),
                Type.getObjectType("sample/Value"),
                Type.getObjectType("sample/Add")
        ));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(2) instanceof IrInstruction.CallHelper);
        IrInstruction.CallHelper helper = (IrInstruction.CallHelper) irMethod.blocks().get(0).instructions().get(2);
        assertEquals("ir_rt_type_switch__" + helperToken("sample/Value") + "__" + helperToken("sample/Add"), helper.helperName());
        assertEquals(IrType.INT, helper.result().type());
    }

    @Test
    public void testBuildsRecordObjectMethodInvokeDynamicFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "toString",
                "(Lsample/Point;)Ljava/lang/String;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/runtime/ObjectMethods",
                        "bootstrap",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;",
                        false
                ),
                Type.getObjectType("sample/Point"),
                "left;name",
                new Handle(Opcodes.H_GETFIELD, "sample/Point", "left", "I", false),
                new Handle(Opcodes.H_GETFIELD, "sample/Point", "name", "Ljava/lang/String;", false)
        ));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build("sample/Point", methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(1) instanceof IrInstruction.CallHelper);
        IrInstruction.CallHelper helper = (IrInstruction.CallHelper) irMethod.blocks().get(0).instructions().get(1);
        assertEquals(
                "ir_rt_record__" + helperToken("sample/Point")
                        + "__" + helperToken("toString")
                        + "__" + utf8Hex("left;name")
                        + "__" + helperToken("left")
                        + "__" + helperToken("int")
                        + "__" + helperToken("name")
                        + "__" + helperToken("java/lang/String"),
                helper.helperName()
        );
        assertEquals(IrType.reference("java/lang/String"), helper.result().type());
    }

    @Test
    public void testBuildsNullConstantFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "nullable", "()Ljava/lang/Object;", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(0) instanceof IrInstruction.Const);
        IrInstruction.Const constant = (IrInstruction.Const) irMethod.blocks().get(0).instructions().get(0);
        assertEquals(null, constant.value());
        assertEquals(IrType.reference("java/lang/Object"), constant.result().type());
    }

    @Test
    public void testBuildsNullReturnForSpecificReferenceType() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "nullableString", "()Ljava/lang/String;", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(1) instanceof IrInstruction.Convert);
        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.Return);
        IrTerminator.Return terminator = (IrTerminator.Return) irMethod.blocks().get(0).terminator();
        assertEquals(IrType.reference("java/lang/String"), terminator.value().type());
    }

    @Test
    public void testBuildsReferenceNullConditionalBranch() {
        LabelNode nonNull = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, nonNull));
        methodNode.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));
        methodNode.instructions.add(nonNull);
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        IrInstruction.Compare compare = (IrInstruction.Compare) irMethod.blocks().get(0).instructions().get(2);
        assertEquals(IrCompareOpcode.NE, compare.opcode());
        assertEquals(IrType.reference("java/lang/String"), compare.left().type());
    }

    @Test
    public void testBuildsInstanceOfFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isThing", "(Ljava/lang/Object;)Z", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, "sample/Thing"));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(1) instanceof IrInstruction.CallHelper);
        IrInstruction.CallHelper helper = (IrInstruction.CallHelper) irMethod.blocks().get(0).instructions().get(1);
        assertEquals("ir_rt_instanceof__" + helperToken("sample/Thing"), helper.helperName());
        assertEquals(IrType.BOOLEAN, helper.result().type());
    }

    @Test
    public void testBuildsLongConstantInvokeFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sleepShort", "()V", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new LdcInsnNode(50L));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(J)V", false));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(0) instanceof IrInstruction.Const);
        IrInstruction.Const constant = (IrInstruction.Const) irMethod.blocks().get(0).instructions().get(0);
        assertEquals(IrType.LONG, constant.result().type());
        assertEquals(50L, constant.value());
        assertTrue(irMethod.blocks().get(0).instructions().get(1) instanceof IrInstruction.Invoke);
        IrInstruction.Invoke invoke = (IrInstruction.Invoke) irMethod.blocks().get(0).instructions().get(1);
        assertEquals(IrType.LONG, invoke.arguments().get(0).type());
    }

    @Test
    public void testBuildsFloatArithmeticFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mix", "(FF)F", null, null);
        methodNode.maxLocals = 2;
        methodNode.instructions.add(new VarInsnNode(Opcodes.FLOAD, 0));
        methodNode.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        methodNode.instructions.add(new InsnNode(Opcodes.FADD));
        methodNode.instructions.add(new InsnNode(Opcodes.FRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertEquals(IrType.FLOAT, irMethod.returnType());
        IrInstruction.Binary binary = (IrInstruction.Binary) irMethod.blocks().get(0).instructions().get(2);
        assertEquals(IrType.FLOAT, binary.result().type());
        assertEquals(IrBinaryOpcode.ADD, binary.opcode());
        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.Return);
    }

    @Test
    public void testBuildsWideParameterSlotLayout() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pickThird", "(IDI)I", null, null);
        methodNode.maxLocals = 4;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertEquals(List.of(IrType.INT, IrType.DOUBLE, IrType.INT), irMethod.parameterTypes());
        assertTrue(irMethod.blocks().get(0).instructions().get(0) instanceof IrInstruction.LoadLocal);
        IrInstruction.LoadLocal loadLocal = (IrInstruction.LoadLocal) irMethod.blocks().get(0).instructions().get(0);
        assertEquals(3, loadLocal.slot());
    }

    @Test
    public void testBuildsFloatCompareOpcodeFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "cmp", "(FF)I", null, null);
        methodNode.maxLocals = 2;
        methodNode.instructions.add(new VarInsnNode(Opcodes.FLOAD, 0));
        methodNode.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        methodNode.instructions.add(new InsnNode(Opcodes.FCMPL));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(2) instanceof IrInstruction.CallHelper);
        IrInstruction.CallHelper helper = (IrInstruction.CallHelper) irMethod.blocks().get(0).instructions().get(2);
        assertEquals("ir_rt_fcmpl", helper.helperName());
        assertEquals(IrType.INT, helper.result().type());
    }

    @Test
    public void testBuildsCharArrayReverseFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "rewriteChar", "([C)V", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        methodNode.instructions.add(new InsnNode(Opcodes.CALOAD));
        methodNode.instructions.add(new InsnNode(Opcodes.CASTORE));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(IrInstruction.CallHelper.class::isInstance)
                .map(IrInstruction.CallHelper.class::cast)
                .anyMatch(helper -> helper.helperName().equals("ir_rt_array_load__" + helperToken("char[]"))));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(IrInstruction.CallHelperVoid.class::isInstance)
                .map(IrInstruction.CallHelperVoid.class::cast)
                .anyMatch(helper -> helper.helperName().equals("ir_rt_array_store__" + helperToken("char[]"))));
        assertTrue(irMethod.blocks().stream().anyMatch(block -> block.terminator() instanceof IrTerminator.ReturnVoid));
    }

    @Test
    public void testBuildsDupX1Flow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "shuffle", "()V", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_2));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP_X1));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.ReturnVoid);
    }

    @Test
    public void testBuildsDup2Flow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "shuffle2", "()V", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_2));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP2));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.ReturnVoid);
    }

    @Test
    public void testBuildsDup2X1Flow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "shuffle3", "()V", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_2));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_3));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP2_X1));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.ReturnVoid);
    }

    @Test
    public void testBuildsDup2X2Flow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "shuffle5", "()V", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_2));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_3));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_4));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP2_X2));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.ReturnVoid);
    }

    @Test
    public void testBuildsDup2X2WithWideInsertTargetFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "shuffle6", "()V", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new LdcInsnNode(7L));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_2));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP2_X2));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP2));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.ReturnVoid);
    }

    @Test
    public void testBuildsDup2X2WithWideTopFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "shuffle7", "()V", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_2));
        methodNode.instructions.add(new LdcInsnNode(42L));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP2_X2));
        methodNode.instructions.add(new InsnNode(Opcodes.POP2));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP2));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.ReturnVoid);
    }

    @Test
    public void testBuildsDup2X2WithTwoWideValuesFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "shuffle8", "()V", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new LdcInsnNode(1L));
        methodNode.instructions.add(new LdcInsnNode(2L));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP2_X2));
        methodNode.instructions.add(new InsnNode(Opcodes.POP2));
        methodNode.instructions.add(new InsnNode(Opcodes.POP2));
        methodNode.instructions.add(new InsnNode(Opcodes.POP2));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.ReturnVoid);
    }

    @Test
    public void testBuildsDupX2Flow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "shuffle4", "()V", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_2));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_3));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP_X2));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.ReturnVoid);
    }

    @Test
    public void testBuildsFloatArrayAndMonitorFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sample", "()V", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_2));
        methodNode.instructions.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_FLOAT));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.MONITORENTER));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.MONITOREXIT));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(IrInstruction.CallHelper.class::isInstance)
                .map(IrInstruction.CallHelper.class::cast)
                .anyMatch(helper -> helper.helperName().equals("ir_rt_new_array__" + helperToken("float[]"))));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(IrInstruction.CallHelperVoid.class::isInstance)
                .map(IrInstruction.CallHelperVoid.class::cast)
                .anyMatch(helper -> helper.helperName().equals("ir_rt_monitor_enter")));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(IrInstruction.CallHelperVoid.class::isInstance)
                .map(IrInstruction.CallHelperVoid.class::cast)
                .anyMatch(helper -> helper.helperName().equals("ir_rt_monitor_exit")));
    }

    @Test
    public void testBuildsCatchHandlerEntryValue() {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "withCatch", "()I", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(start);
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(end);
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.instructions.add(handler);
        methodNode.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        IrBlock catchBlock = findBlock(irMethod, "block0");
        assertTrue(catchBlock.instructions().get(0) instanceof IrInstruction.CallHelper);
        IrInstruction.CallHelper helper = (IrInstruction.CallHelper) catchBlock.instructions().get(0);
        assertEquals("ir_rt_current_exception", helper.helperName());
        assertTrue(catchBlock.instructions().get(1) instanceof IrInstruction.StoreLocal);
    }

    @Test
    public void testBuildsInstanceFieldFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC, "touchField", "()I", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "sample/Box", "value", "I"));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build("sample/Box", methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(0) instanceof IrInstruction.LoadLocal);
        assertTrue(irMethod.blocks().get(0).instructions().get(1) instanceof IrInstruction.LoadField);
    }

    @Test
    public void testBuildsInstanceInvokeFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callWorker", "(Lsample/Worker;I)I", null, null);
        methodNode.maxLocals = 2;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "sample/Worker", "mix", "(I)I", false));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(2) instanceof IrInstruction.Invoke);
        IrInstruction.Invoke invoke = (IrInstruction.Invoke) irMethod.blocks().get(0).instructions().get(2);
        assertEquals("mix", invoke.method().name());
        assertEquals(2, invoke.arguments().size());
        assertEquals(IrType.reference("sample/Worker"), invoke.arguments().get(0).type());
    }

    @Test
    public void testBuildsObjectConstructionAndInvokeFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createAndMix", "(I)I", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new TypeInsnNode(Opcodes.NEW, "sample/Worker"));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "sample/Worker", "<init>", "()V", false));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "sample/Worker", "mix", "(I)I", false));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        IrInstruction.NewObject newObject = findFirstInstruction(irMethod, IrInstruction.NewObject.class);
        assertTrue(newObject != null);
        List<IrInstruction.Invoke> invokes = irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(IrInstruction.Invoke.class::isInstance)
                .map(IrInstruction.Invoke.class::cast)
                .toList();
        IrInstruction.Invoke initInvoke = invokes.stream()
                .filter(invoke -> "<init>".equals(invoke.method().name()))
                .findFirst()
                .orElseThrow();
        assertEquals("<init>", initInvoke.method().name());
        assertEquals(IrType.VOID, initInvoke.method().returnType());
        IrInstruction.Invoke mixInvoke = invokes.stream()
                .filter(invoke -> "mix".equals(invoke.method().name()))
                .findFirst()
                .orElseThrow();
        assertEquals("mix", mixInvoke.method().name());
        assertEquals(IrType.reference("sample/Worker"), mixInvoke.arguments().get(0).type());
    }

    private static <T extends IrInstruction> T findFirstInstruction(IrMethod irMethod, Class<T> type) {
        return irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow();
    }

    @Test
    public void testBuildsPop2WideValueFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dropWide", "()V", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new LdcInsnNode(42L));
        methodNode.instructions.add(new InsnNode(Opcodes.POP2));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertEquals(1, irMethod.blocks().size());
        assertTrue(irMethod.blocks().get(0).instructions().get(0) instanceof IrInstruction.Const);
        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.ReturnVoid);
    }

    @Test
    public void testBuildsByteConditionalJumpWithMatchingCompareTypes() {
        LabelNode nonZero = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "byteBranch", "(B)I", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.instructions.add(nonZero);
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        IrInstruction.Compare compare = irMethod.blocks().get(0).instructions().stream()
                .filter(IrInstruction.Compare.class::isInstance)
                .map(IrInstruction.Compare.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(IrType.INT, compare.left().type());
        assertEquals(IrType.INT, compare.right().type());
        new IrMethodValidator().validate(irMethod);
    }

    @Test
    public void testBuildsShortConditionalJumpWithMatchingCompareTypes() {
        LabelNode negative = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "shortBranch", "(S)I", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.IFLT, negative));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.instructions.add(negative);
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        IrInstruction.Compare compare = irMethod.blocks().get(0).instructions().stream()
                .filter(IrInstruction.Compare.class::isInstance)
                .map(IrInstruction.Compare.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(IrType.INT, compare.left().type());
        assertEquals(IrType.INT, compare.right().type());
        new IrMethodValidator().validate(irMethod);
    }

    @Test
    public void testBuildsBooleanArrayLoadStoreFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "booleanArray", "()I", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.BASTORE));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        methodNode.instructions.add(new InsnNode(Opcodes.BALOAD));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(IrInstruction.CallHelper.class::isInstance)
                .map(IrInstruction.CallHelper.class::cast)
                .anyMatch(helper -> helper.helperName().equals("ir_rt_array_load__" + helperToken("boolean[]"))));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(IrInstruction.CallHelperVoid.class::isInstance)
                .map(IrInstruction.CallHelperVoid.class::cast)
                .anyMatch(helper -> helper.helperName().equals("ir_rt_array_store__" + helperToken("boolean[]"))));
    }

    @Test
    public void testBuildsByteArraySizeAndIndexPromotionFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "byteArray", "(B)I", null, null);
        methodNode.maxLocals = 2;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.IASTORE));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.IALOAD));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        new IrMethodValidator().validate(irMethod);
        assertTrue(irMethod.blocks().get(0).instructions().stream()
                .filter(IrInstruction.CallHelper.class::isInstance)
                .map(IrInstruction.CallHelper.class::cast)
                .anyMatch(helper -> helper.helperName().equals("ir_rt_new_array__" + helperToken("int[]"))));
    }

    @Test
    public void testBuildsMultiArrayFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "multi", "(II)[[[I", null, null);
        methodNode.maxLocals = 2;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        methodNode.instructions.add(new MultiANewArrayInsnNode("[[[I", 2));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        IrInstruction.CallHelper helper = irMethod.blocks().get(0).instructions().stream()
                .filter(IrInstruction.CallHelper.class::isInstance)
                .map(IrInstruction.CallHelper.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("ir_rt_multi_new_array__" + helperToken("int[][][]"), helper.helperName());
        assertEquals(2, helper.arguments().size());
        new IrMethodValidator().validate(irMethod);
    }

    @Test
    public void testBuildsNestedPrimitiveArrayLoadFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "nestedArray", "()I", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "sample/Holder", "GRID", "[[I"));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        methodNode.instructions.add(new InsnNode(Opcodes.AALOAD));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.IALOAD));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(IrInstruction.CallHelper.class::isInstance)
                .map(IrInstruction.CallHelper.class::cast)
                .anyMatch(helper -> helper.helperName().equals("ir_rt_array_load__" + helperToken("int[][]"))));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(IrInstruction.CallHelper.class::isInstance)
                .map(IrInstruction.CallHelper.class::cast)
                .anyMatch(helper -> helper.helperName().equals("ir_rt_array_load__" + helperToken("int[]"))));
    }

    @Test
    public void testBuildsMergedNullAndNestedReferenceArrayLoadFlow() {
        LabelNode elseLabel = new LabelNode();
        LabelNode joinLabel = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mergedArray", "([[Ljava/lang/String;Z)I", null, null);
        methodNode.maxLocals = 2;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.IFEQ, elseLabel));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.GOTO, joinLabel));
        methodNode.instructions.add(elseLabel);
        methodNode.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        methodNode.instructions.add(joinLabel);
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        methodNode.instructions.add(new InsnNode(Opcodes.AALOAD));
        methodNode.instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(IrInstruction.CallHelper.class::isInstance)
                .map(IrInstruction.CallHelper.class::cast)
                .anyMatch(helper -> helper.helperName().equals("ir_rt_array_load__" + helperToken("java/lang/String[][]"))));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(IrInstruction.CallHelper.class::isInstance)
                .map(IrInstruction.CallHelper.class::cast)
                .anyMatch(helper -> helper.helperName().equals("ir_rt_array_length")));
        new IrMethodValidator().validate(irMethod);
    }

    @Test
    public void testBuildsEnumTypeSwitchInvokeDynamicFlow() {
        Handle constantBootstrapsInvoke = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/ConstantBootstraps",
                "invoke",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/invoke/MethodHandle;[Ljava/lang/Object;)Ljava/lang/Object;",
                false
        );
        ConstantDynamic classDesc = new ConstantDynamic(
                "invoke",
                "Ljava/lang/constant/ClassDesc;",
                constantBootstrapsInvoke,
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/constant/ClassDesc",
                        "of",
                        "(Ljava/lang/String;)Ljava/lang/constant/ClassDesc;",
                        true
                ),
                "sample.Mode"
        );
        ConstantDynamic enumLeft = new ConstantDynamic(
                "invoke",
                "Ljava/lang/Enum$EnumDesc;",
                constantBootstrapsInvoke,
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/Enum$EnumDesc",
                        "of",
                        "(Ljava/lang/constant/ClassDesc;Ljava/lang/String;)Ljava/lang/Enum$EnumDesc;",
                        false
                ),
                classDesc,
                "LEFT"
        );

        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "switchOnEnum", "(Ljava/lang/Object;I)I", null, null);
        methodNode.maxLocals = 2;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "typeSwitch",
                "(Ljava/lang/Object;I)I",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/runtime/SwitchBootstraps",
                        "typeSwitch",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false
                ),
                enumLeft
        ));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(2) instanceof IrInstruction.CallHelper);
        IrInstruction.CallHelper helper = (IrInstruction.CallHelper) irMethod.blocks().get(0).instructions().get(2);
        assertTrue(helper.helperName().contains(helperToken("enum:sample/Mode:LEFT")));
    }

    @Test
    public void testBuildsEnumSwitchInvokeDynamicFlow() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "switchOnEnum", "(Lsample/Mode;I)I", null, null);
        methodNode.maxLocals = 2;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "enumSwitch",
                "(Lsample/Mode;I)I",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/runtime/SwitchBootstraps",
                        "enumSwitch",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false
                ),
                "LEFT",
                "RIGHT"
        ));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().get(0).instructions().get(2) instanceof IrInstruction.CallHelper);
        IrInstruction.CallHelper helper = (IrInstruction.CallHelper) irMethod.blocks().get(0).instructions().get(2);
        assertEquals(
                "ir_rt_type_switch__" + helperToken("enum:sample/Mode:LEFT") + "__" + helperToken("enum:sample/Mode:RIGHT"),
                helper.helperName()
        );
        assertEquals(IrType.INT, helper.result().type());
    }

    @Test
    public void testBuildsEnumTypeSwitchDefaultThrowFlow() {
        Handle constantBootstrapsInvoke = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/ConstantBootstraps",
                "invoke",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/invoke/MethodHandle;[Ljava/lang/Object;)Ljava/lang/Object;",
                false
        );
        ConstantDynamic classDesc = new ConstantDynamic(
                "invoke",
                "Ljava/lang/constant/ClassDesc;",
                constantBootstrapsInvoke,
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/constant/ClassDesc",
                        "of",
                        "(Ljava/lang/String;)Ljava/lang/constant/ClassDesc;",
                        true
                ),
                "sample.Mode"
        );
        ConstantDynamic enumLeft = new ConstantDynamic(
                "invoke",
                "Ljava/lang/Enum$EnumDesc;",
                constantBootstrapsInvoke,
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/Enum$EnumDesc",
                        "of",
                        "(Ljava/lang/constant/ClassDesc;Ljava/lang/String;)Ljava/lang/Enum$EnumDesc;",
                        false
                ),
                classDesc,
                "LEFT"
        );
        LabelNode caseLeft = new LabelNode();
        LabelNode caseRight = new LabelNode();
        LabelNode defaultCase = new LabelNode();
        LabelNode join = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "switchThrow", "(Ljava/lang/Enum;Ljava/lang/Enum;)V", null, null);
        methodNode.maxLocals = 4;
        methodNode.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "sample/Holder", "INSTANCE", "Lsample/Holder;"));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "typeSwitch",
                "(Ljava/lang/Object;I)I",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/runtime/SwitchBootstraps",
                        "typeSwitch",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false
                ),
                enumLeft
        ));
        methodNode.instructions.add(new LookupSwitchInsnNode(defaultCase, new int[]{0, 1}, new LabelNode[]{caseLeft, caseRight}));
        methodNode.instructions.add(caseLeft);
        methodNode.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.GOTO, join));
        methodNode.instructions.add(caseRight);
        methodNode.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.GOTO, join));
        methodNode.instructions.add(defaultCase);
        methodNode.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalArgumentException"));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false));
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false
                ),
                "Unexpected value: \u0001"
        ));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false));
        methodNode.instructions.add(new InsnNode(Opcodes.ATHROW));
        methodNode.instructions.add(join);
        methodNode.instructions.add(new InsnNode(Opcodes.POP2));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertEquals("switchThrow", irMethod.name());
        assertTrue(irMethod.blocks().stream().anyMatch(block -> block.terminator() instanceof IrTerminator.Throw));
    }

    @Test
    public void testBuildsCharLookupSwitchFlow() {
        LabelNode caseA = new LabelNode();
        LabelNode caseB = new LabelNode();
        LabelNode defaultCase = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "charSwitch", "(C)I", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new LookupSwitchInsnNode(defaultCase, new int[]{'A', 'B'}, new LabelNode[]{caseA, caseB}));
        methodNode.instructions.add(caseA);
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.instructions.add(caseB);
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_2));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.instructions.add(defaultCase);
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        new IrMethodValidator().validate(irMethod);
        assertEquals(4, irMethod.blocks().size());
        assertTrue(irMethod.blocks().get(0).terminator() instanceof IrTerminator.Switch);
    }

    @Test
    public void testBuildsBroadTryCatchMethodWithProtectedInvokeAndThrow() {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode done = new LabelNode();

        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "(Z)I", null, null);
        methodNode.maxLocals = 2;
        methodNode.instructions.add(start);
        methodNode.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.IFEQ, done));
        methodNode.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP));
        methodNode.instructions.add(new LdcInsnNode("boom"));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false));
        methodNode.instructions.add(new InsnNode(Opcodes.ATHROW));
        methodNode.instructions.add(end);
        methodNode.instructions.add(done);
        methodNode.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 7));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.instructions.add(handler);
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_M1));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction instanceof IrInstruction.CallHelper)
                .map(instruction -> (IrInstruction.CallHelper) instruction)
                .anyMatch(helper -> "ir_rt_exception_pending".equals(helper.helperName())));
        assertTrue(irMethod.blocks().stream()
                .filter(block -> block.terminator() instanceof IrTerminator.Goto)
                .map(block -> (IrTerminator.Goto) block.terminator())
                .map(IrTerminator.Goto::targetBlock)
                .distinct()
                .map(target -> findBlock(irMethod, target))
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction instanceof IrInstruction.CallHelper)
                .map(instruction -> (IrInstruction.CallHelper) instruction)
                .anyMatch(helper -> "ir_rt_current_exception".equals(helper.helperName())));
    }

    @Test
    public void testBuildsBroadTryCatchMethodWithProtectedInvokeDynamic() {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();

        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "()V", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(start);
        methodNode.instructions.add(new InvokeDynamicInsnNode(
                "run",
                "()Ljava/lang/Runnable;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory",
                        "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        false
                ),
                Type.getMethodType("()V"),
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "sample/Helpers",
                        "run",
                        "()V",
                        false
                ),
                Type.getMethodType("()V")
        ));
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(end);
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));
        methodNode.instructions.add(handler);
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));
        methodNode.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction instanceof IrInstruction.CallHelper)
                .map(instruction -> (IrInstruction.CallHelper) instruction)
                .anyMatch(helper -> "ir_rt_exception_pending".equals(helper.helperName())));
    }

    @Test
    public void testBuildsTypedTryCatchMethodWithProtectedInvokeInterface() {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();

        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "(Ljava/lang/Runnable;)V", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(start);
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
        methodNode.instructions.add(end);
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));
        methodNode.instructions.add(handler);
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));
        methodNode.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Exception"));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction instanceof IrInstruction.CallHelper)
                .map(instruction -> (IrInstruction.CallHelper) instruction)
                .anyMatch(helper -> "ir_rt_exception_pending".equals(helper.helperName())));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction instanceof IrInstruction.CallHelper)
                .map(instruction -> (IrInstruction.CallHelper) instruction)
                .anyMatch(helper -> "ir_rt_current_exception".equals(helper.helperName())));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction instanceof IrInstruction.CallHelper)
                .map(instruction -> (IrInstruction.CallHelper) instruction)
                .anyMatch(helper -> ("ir_rt_instanceof__" + helperToken("java/lang/Exception")).equals(helper.helperName())));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction instanceof IrInstruction.CallHelperVoid helper
                        && "ir_rt_throw".equals(helper.helperName())));
    }

    @Test
    public void testBuildsUnhandledInvokeInterfaceExceptionEdge() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "(Ljava/lang/Runnable;)V", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
        methodNode.instructions.add(new InsnNode(Opcodes.RETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction instanceof IrInstruction.CallHelper)
                .map(instruction -> (IrInstruction.CallHelper) instruction)
                .anyMatch(helper -> "ir_rt_exception_pending".equals(helper.helperName())));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction instanceof IrInstruction.CallHelper)
                .map(instruction -> (IrInstruction.CallHelper) instruction)
                .anyMatch(helper -> "ir_rt_current_exception".equals(helper.helperName())));
        assertTrue(irMethod.blocks().stream()
                .anyMatch(block -> block.terminator() instanceof IrTerminator.Throw));
    }

    @Test
    public void testBuildsUnhandledGetStaticExceptionEdge() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "()Ljava/lang/Object;", null, null);
        methodNode.maxLocals = 0;
        methodNode.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "sample/Holder", "INSTANCE", "Ljava/lang/Object;"));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction instanceof IrInstruction.CallHelper)
                .map(instruction -> (IrInstruction.CallHelper) instruction)
                .anyMatch(helper -> "ir_rt_exception_pending".equals(helper.helperName())));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction instanceof IrInstruction.CallHelper)
                .map(instruction -> (IrInstruction.CallHelper) instruction)
                .anyMatch(helper -> "ir_rt_current_exception".equals(helper.helperName())));
        assertTrue(irMethod.blocks().stream()
                .anyMatch(block -> block.terminator() instanceof IrTerminator.Throw));
    }

    @Test
    public void testBuildsTypedTryCatchMethodWithExplicitThrow() {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode done = new LabelNode();

        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "(Z)I", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(start);
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.IFEQ, done));
        methodNode.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        methodNode.instructions.add(new InsnNode(Opcodes.DUP));
        methodNode.instructions.add(new LdcInsnNode("boom"));
        methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false));
        methodNode.instructions.add(new InsnNode(Opcodes.ATHROW));
        methodNode.instructions.add(end);
        methodNode.instructions.add(done);
        methodNode.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 7));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.instructions.add(handler);
        methodNode.instructions.add(new InsnNode(Opcodes.POP));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_M1));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException"));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction instanceof IrInstruction.CallHelper)
                .map(instruction -> (IrInstruction.CallHelper) instruction)
                .anyMatch(helper -> ("ir_rt_instanceof__" + helperToken("java/lang/RuntimeException")).equals(helper.helperName())));
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction instanceof IrInstruction.CallHelperVoid helper
                        && "ir_rt_throw".equals(helper.helperName())));
    }

    @Test
    public void testBuildsReferenceLoadAfterBranchLocalSlotReuse() {
        LabelNode loadOriginal = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reuseObject", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.IFNULL, loadOriginal));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_5));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ISTORE, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));
        methodNode.instructions.add(loadOriginal);
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.ARETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction instanceof IrInstruction.StoreLocal storeLocal
                        && storeLocal.slot() == 1
                        && storeLocal.value().type() == IrType.INT));
        assertTrue(findBlock(irMethod, "block0").instructions().stream()
                .anyMatch(instruction -> instruction instanceof IrInstruction.LoadLocal loadLocal
                        && loadLocal.slot() == 0
                        && !loadLocal.result().type().isPrimitive()));
    }

    @Test
    public void testBuildsIntLoadAfterBranchLocalSlotReuse() {
        LabelNode loadFlag = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reuseInt", "(Ljava/lang/Object;Z)I", null, null);
        methodNode.maxLocals = 2;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, loadFlag));
        methodNode.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.instructions.add(loadFlag);
        methodNode.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction instanceof IrInstruction.StoreLocal storeLocal
                        && storeLocal.slot() == 2
                        && !storeLocal.value().type().isPrimitive()));
        assertTrue(findBlock(irMethod, "block0").instructions().stream()
                .anyMatch(instruction -> instruction instanceof IrInstruction.LoadLocal loadLocal
                        && loadLocal.slot() == 1
                        && loadLocal.result().type() == IrType.BOOLEAN));
    }

    @Test
    public void testBuildsLongLoadAfterBranchLocalSlotReuse() {
        LabelNode loadLong = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reuseLong", "(Ljava/lang/String;)J", null, null);
        methodNode.maxLocals = 3;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.IFNULL, loadLong));
        methodNode.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        methodNode.instructions.add(new LdcInsnNode(2L));
        methodNode.instructions.add(new InsnNode(Opcodes.LRETURN));
        methodNode.instructions.add(loadLong);
        methodNode.instructions.add(new InsnNode(Opcodes.LCONST_1));
        methodNode.instructions.add(new VarInsnNode(Opcodes.LSTORE, 1));
        methodNode.instructions.add(new VarInsnNode(Opcodes.LLOAD, 1));
        methodNode.instructions.add(new InsnNode(Opcodes.LRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction instanceof IrInstruction.StoreLocal storeLocal
                        && storeLocal.slot() == 1
                        && !storeLocal.value().type().isPrimitive()));
        assertTrue(findBlock(irMethod, "block0").instructions().stream()
                .anyMatch(instruction -> instruction instanceof IrInstruction.LoadLocal loadLocal
                        && loadLocal.slot() == 3
                        && loadLocal.result().type() == IrType.LONG));
    }

    @Test
    public void testBuildsFloatLoadAfterBranchLocalSlotReuse() {
        LabelNode loadFloat = new LabelNode();
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reuseFloat", "(F)F", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        methodNode.instructions.add(new JumpInsnNode(Opcodes.IFNE, loadFloat));
        methodNode.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        methodNode.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.FCONST_1));
        methodNode.instructions.add(new InsnNode(Opcodes.FRETURN));
        methodNode.instructions.add(loadFloat);
        methodNode.instructions.add(new VarInsnNode(Opcodes.FLOAD, 0));
        methodNode.instructions.add(new InsnNode(Opcodes.FRETURN));

        IrMethod irMethod = new MethodIrBuilder().build(methodNode);

        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction instanceof IrInstruction.StoreLocal storeLocal
                        && storeLocal.slot() == 1
                        && !storeLocal.value().type().isPrimitive()));
        assertTrue(findBlock(irMethod, "block0").instructions().stream()
                .anyMatch(instruction -> instruction instanceof IrInstruction.LoadLocal loadLocal
                        && loadLocal.slot() == 0
                        && loadLocal.result().type() == IrType.FLOAT));
    }

    @Test
    public void testRejectsInstanceMethodWithoutOwnerMetadata() {
        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC, "callWorker", "()I", null, null);
        methodNode.maxLocals = 1;
        methodNode.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        methodNode.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "sample/Worker", "value", "I"));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));

        UnsupportedBytecodeException exception = assertThrows(UnsupportedBytecodeException.class, () -> new MethodIrBuilder().build(methodNode));

        assertEquals("Instance method callWorker()I requires owner metadata for local typing", exception.getMessage());
    }

    private IrBlock findBlock(IrMethod method, String label) {
        return method.blocks().stream()
                .filter(block -> block.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Missing block " + label));
    }

    private String helperToken(String value) {
        return utf8Hex(value);
    }

    private String utf8Hex(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            builder.append(String.format("%02x", current & 0xff));
        }
        return builder.toString();
    }
}
