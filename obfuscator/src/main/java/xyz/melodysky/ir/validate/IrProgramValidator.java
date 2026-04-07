package xyz.melodysky.ir.validate;

import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrProgram;

public class IrProgramValidator {

    private final IrMethodValidator methodValidator = new IrMethodValidator();

    public void validate(IrProgram program) {
        for (IrClass irClass : program.classes()) {
            irClass.methods().forEach(methodValidator::validate);
        }
    }
}
