package xyz.melodysky.ir.validate;

import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class IrMethodValidatorTest {

    @Test
    public void testAcceptsValidMethod() {
        IrValue localForCompare = new IrValue(0, IrType.INT, "local");
        IrValue zero = new IrValue(1, IrType.INT, "zero");
        IrValue condition = new IrValue(2, IrType.BOOLEAN, "cond");
        IrValue one = new IrValue(3, IrType.INT, "one");
        IrValue localForReturn = new IrValue(4, IrType.INT, "local");

        IrMethod method = new IrMethod(
                "branchy",
                IrType.INT,
                List.of(IrType.INT),
                1,
                "entry",
                List.of(
                        new IrBlock("entry", List.of(
                                new IrInstruction.LoadLocal(localForCompare, 0),
                                new IrInstruction.Const(zero, 0),
                                new IrInstruction.Compare(condition, xyz.melodysky.ir.model.IrCompareOpcode.EQ, localForCompare, zero)
                        ), new IrTerminator.Branch(condition, "isZero", "nonZero")),
                        new IrBlock("isZero", List.of(new IrInstruction.Const(one, 1)), new IrTerminator.Return(one)),
                        new IrBlock("nonZero", List.of(new IrInstruction.LoadLocal(localForReturn, 0)), new IrTerminator.Return(localForReturn))
                )
        );

        assertDoesNotThrow(() -> new IrMethodValidator().validate(method));
    }

    @Test
    public void testRejectsMissingTargetBlock() {
        IrValue condition = new IrValue(0, IrType.BOOLEAN, "cond");
        IrMethod method = new IrMethod(
                "broken",
                IrType.VOID,
                List.of(),
                0,
                "entry",
                List.of(new IrBlock(
                        "entry",
                        List.of(new IrInstruction.Const(condition, true)),
                        new IrTerminator.Branch(condition, "missing", "entry")
                ))
        );

        IrValidationException exception = assertThrows(IrValidationException.class, () -> new IrMethodValidator().validate(method));

        assertEquals("Invalid IR for method broken: terminator target does not exist: missing", exception.getMessage());
    }

    @Test
    public void testRejectsUseBeforeDefinition() {
        IrValue value = new IrValue(7, IrType.INT, "late");
        IrMethod method = new IrMethod(
                "broken",
                IrType.INT,
                List.of(),
                1,
                "entry",
                List.of(new IrBlock(
                        "entry",
                        List.of(new IrInstruction.StoreLocal(0, value)),
                        new IrTerminator.Return(value)
                ))
        );

        IrValidationException exception = assertThrows(IrValidationException.class, () -> new IrMethodValidator().validate(method));

        assertEquals("Invalid IR for method broken: store_local value uses undefined value %late7", exception.getMessage());
    }

    @Test
    public void testRejectsNonBooleanBranchCondition() {
        IrValue intValue = new IrValue(0, IrType.INT, "n");
        IrMethod method = new IrMethod(
                "broken",
                IrType.VOID,
                List.of(),
                1,
                "entry",
                List.of(new IrBlock(
                        "entry",
                        List.of(new IrInstruction.LoadLocal(intValue, 0)),
                        new IrTerminator.Branch(intValue, "entry", "entry")
                ))
        );

        IrValidationException exception = assertThrows(IrValidationException.class, () -> new IrMethodValidator().validate(method));

        assertEquals("Invalid IR for method broken: branch condition must be boolean but was int", exception.getMessage());
    }
}
