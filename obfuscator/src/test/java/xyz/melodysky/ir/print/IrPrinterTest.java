package xyz.melodysky.ir.print;

import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class IrPrinterTest {

    @Test
    public void testPrinterRendersSimpleArithmeticMethod() {
        IrValue left = new IrValue(0, IrType.INT, "left");
        IrValue right = new IrValue(1, IrType.INT, "right");
        IrValue sum = new IrValue(2, IrType.INT, "sum");

        IrMethod method = new IrMethod(
                "add",
                IrType.INT,
                List.of(IrType.INT, IrType.INT),
                2,
                "entry",
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                new IrInstruction.LoadLocal(left, 0),
                                new IrInstruction.LoadLocal(right, 1),
                                new IrInstruction.Binary(sum, IrBinaryOpcode.ADD, left, right)
                        ),
                        new IrTerminator.Return(sum)
                ))
        );

        String printed = new IrPrinter().print(new IrProgram(List.of(
                new IrClass(new IrClassRef("sample/MathOps"), List.of(method))
        )));

        assertTrue(printed.contains("class sample/MathOps {"));
        assertTrue(printed.contains("method add [static](int, int) -> int locals=2 entry=entry {"));
        assertTrue(printed.contains("%left0:int = load_local 0"));
        assertTrue(printed.contains("%sum2:int = add %left0, %right1"));
        assertTrue(printed.contains("return %sum2"));
    }
}
