package xyz.melodysky.runtime.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class StringConcatFactoryBootstrapTest {
    @Test
    void parsesMakeConcatAsOperandOnlyPlan() {
        var plan = new StringConcatFactoryBootstrap().parse(
                "makeConcat",
                bootstrap("makeConcat"),
                new Object[0]);

        assertTrue(plan.stringConcatFactory());
        assertTrue(plan.supported());
        assertTrue(plan.tokens().isEmpty());
    }

    @Test
    void parsesMakeConcatWithConstantsRecipe() {
        var plan = new StringConcatFactoryBootstrap().parse(
                "makeConcatWithConstants",
                bootstrap("makeConcatWithConstants"),
                new Object[] {"x=\u0001:\u0002", 7});

        assertTrue(plan.supported());
        assertEquals(StringConcatTokenKind.CONSTANT, plan.tokens().get(0).kind());
        assertEquals("x=", plan.tokens().get(0).constant());
        assertEquals(StringConcatTokenKind.OPERAND, plan.tokens().get(1).kind());
        assertEquals(StringConcatTokenKind.CONSTANT, plan.tokens().get(2).kind());
        assertEquals(":", plan.tokens().get(2).constant());
        assertEquals(StringConcatTokenKind.CONSTANT, plan.tokens().get(3).kind());
        assertEquals("7", plan.tokens().get(3).constant());
    }

    @Test
    void rejectsUnsupportedRecipeConstant() {
        var plan = new StringConcatFactoryBootstrap().parse(
                "makeConcatWithConstants",
                bootstrap("makeConcatWithConstants"),
                new Object[] {"\u0002", Type.getType("Ljava/lang/String;")});

        assertTrue(plan.stringConcatFactory());
        assertFalse(plan.supported());
    }

    private Handle bootstrap(String name) {
        String descriptor = name.equals("makeConcat")
                ? "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;"
                : "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";
        return new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                name,
                descriptor,
                false);
    }
}
