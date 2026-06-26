package xyz.melodysky.ir.ssa;

import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

public final class ValueFactory {
    private int nextValueId;

    public IrValue next(IrType type) {
        return new IrValue("%v" + nextValueId++, type);
    }

    public IrValue parameter(int index, IrType type) {
        return new IrValue("%p" + index, type);
    }
}
