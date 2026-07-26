package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrType;

class ProgramIrProtectionResultTest {
    @Test
    void preservesDeterministicMethodOrderWhileKeepingMapsImmutable() {
        IrMethod second = method("second");
        IrMethod first = method("first");
        LinkedHashMap<String, IrMethod> javaMethods = new LinkedHashMap<>();
        javaMethods.put(second.methodKey(), second);
        javaMethods.put(first.methodKey(), first);

        ProgramIrProtectionResult result =
                new ProgramIrProtectionResult(javaMethods, java.util.Map.of(), List.of(), List.of());

        assertEquals(
                List.of(second.methodKey(), first.methodKey()),
                result.javaMethods().keySet().stream().toList());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.javaMethods().put("other", method("other")));
    }

    private IrMethod method(String name) {
        return new IrMethod(
                "pkg/Order",
                name,
                "()V",
                IrType.VOID,
                List.of(),
                List.of());
    }
}
