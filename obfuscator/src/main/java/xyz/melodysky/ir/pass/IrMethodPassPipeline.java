package xyz.melodysky.ir.pass;

import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.validate.IrMethodValidator;

import java.util.List;

public class IrMethodPassPipeline {

    private final List<IrMethodPass> passes;
    private final IrMethodValidator validator;

    public IrMethodPassPipeline(List<IrMethodPass> passes) {
        this(passes, new IrMethodValidator());
    }

    public IrMethodPassPipeline(List<IrMethodPass> passes, IrMethodValidator validator) {
        this.passes = List.copyOf(passes);
        this.validator = validator;
    }

    public IrMethod run(IrMethod method) {
        IrMethod current = method;
        validator.validate(current);
        for (IrMethodPass pass : passes) {
            current = pass.apply(current);
            validator.validate(current);
        }
        return current;
    }
}
