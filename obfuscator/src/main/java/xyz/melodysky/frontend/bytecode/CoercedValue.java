package xyz.melodysky.frontend.bytecode;

import xyz.melodysky.ir.model.IrValue;

record CoercedValue(IrValue value, int nextValueId) {
}
