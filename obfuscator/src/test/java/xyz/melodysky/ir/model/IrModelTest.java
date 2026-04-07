package xyz.melodysky.ir.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class IrModelTest {

    @Test
    public void testMethodRequiresExistingEntryBlock() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> new IrMethod(
                "broken",
                IrType.INT,
                List.of(),
                0,
                "missing",
                List.of(new IrBlock("entry", List.of(), new IrTerminator.ReturnVoid()))
        ));

        assertEquals("entryBlock does not exist: missing", exception.getMessage());
    }

    @Test
    public void testBlockInstructionsAreDefensivelyCopied() {
        ArrayList<IrInstruction> instructions = new ArrayList<>();
        instructions.add(new IrInstruction.Const(new IrValue(0, IrType.INT), 7));

        IrBlock block = new IrBlock("entry", instructions, new IrTerminator.ReturnVoid());
        instructions.clear();

        assertEquals(1, block.instructions().size());
    }
}
