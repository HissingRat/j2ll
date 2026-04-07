package xyz.melodysky.ir.pass;

import xyz.melodysky.ir.model.IrMethod;

public interface IrMethodPass {

    String name();

    IrMethod apply(IrMethod method);
}
