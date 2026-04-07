package xyz.melodysky.backend;

import xyz.melodysky.ir.model.IrProgram;

public interface IrProgramBackend {

    String name();

    String emit(IrProgram program);
}
