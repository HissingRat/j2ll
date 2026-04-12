package xyz.melodysky.ir.pass;

import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.*;

import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StringObfuscationPassTest {

    @Test
    public void testRewritesStringConstantHelperName() {
        IrValue value = new IrValue(0, IrType.reference("java/lang/String"), "value");
        IrMethod method = new IrMethod(
                "text",
                IrType.reference("java/lang/String"),
                List.of(),
                0,
                "entry",
                List.of(new IrBlock(
                        "entry",
                        List.of(new IrInstruction.CallHelper(value, "ir_rt_ldc_string__48656c6c6f20f09f8eb5", List.of())),
                        new IrTerminator.Return(value)
                ))
        );

        IrMethod rewritten = new StringObfuscationPass(false, new SecureRandom()).apply(method);

        assertTrue(rewritten.blocks().get(0).instructions().get(0) instanceof IrInstruction.CallHelper);
        IrInstruction.CallHelper helper = (IrInstruction.CallHelper) rewritten.blocks().get(0).instructions().get(0);
        assertTrue(helper.helperName().startsWith("ir_rt_sobf__0__"));
        assertFalse(helper.helperName().contains("48656c6c6f20f09f8eb5"));
    }

    @Test
    public void testRewritesConcatHelperName() {
        IrValue result = new IrValue(0, IrType.reference("java/lang/String"), "value");
        IrValue arg = new IrValue(1, IrType.reference("java/lang/String"), "arg");
        IrMethod method = new IrMethod(
                "concat",
                IrType.reference("java/lang/String"),
                List.of(IrType.reference("java/lang/String")),
                1,
                "entry",
                List.of(new IrBlock(
                        "entry",
                        List.of(new IrInstruction.CallHelper(result, "ir_rt_concat__454252203e2001__java_s_lang_s_String", List.of(arg))),
                        new IrTerminator.Return(result)
                ))
        );

        IrMethod rewritten = new StringObfuscationPass(false, new SecureRandom()).apply(method);

        assertTrue(rewritten.blocks().get(0).instructions().get(0) instanceof IrInstruction.CallHelper);
        IrInstruction.CallHelper helper = (IrInstruction.CallHelper) rewritten.blocks().get(0).instructions().get(0);
        assertTrue(helper.helperName().startsWith("ir_rt_sobf_concat__"));
        assertTrue(helper.helperName().endsWith("__java_s_lang_s_String"));
        assertFalse(helper.helperName().contains("454252203e2001"));
    }
}
