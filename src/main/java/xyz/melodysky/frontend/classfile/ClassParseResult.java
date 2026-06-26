package xyz.melodysky.frontend.classfile;

import java.util.Objects;

public record ClassParseResult(ParsedProgram program) {
    public ClassParseResult {
        Objects.requireNonNull(program, "program");
    }
}
