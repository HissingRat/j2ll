package xyz.melodysky.frontend.classfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.pipeline.StageValidator;

public final class ParsedProgramValidator implements StageValidator<ClassParseResult> {
    @Override
    public DiagnosticStage stage() {
        return DiagnosticStage.VALIDATION;
    }

    @Override
    public List<Diagnostic> validate(ClassParseResult artifact) {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        HashSet<String> classNames = new HashSet<>();
        for (ParsedClass parsedClass : artifact.program().classes()) {
            if (!classNames.add(parsedClass.internalName())) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.VALIDATION,
                                ClassParseDiagnostics.DUPLICATE_CLASS,
                                "duplicate parsed class " + parsedClass.internalName())
                        .at(DiagnosticLocation.classLocation(parsedClass.internalName())));
            }

            HashSet<String> methodKeys = new HashSet<>();
            for (ParsedMethod method : parsedClass.methods()) {
                String key = method.name() + "!" + method.descriptor();
                if (!methodKeys.add(key)) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.VALIDATION,
                                    ClassParseDiagnostics.DUPLICATE_METHOD,
                                    "duplicate parsed method " + method.methodKey())
                            .at(DiagnosticLocation.methodLocation(
                                    parsedClass.internalName(),
                                    method.name(),
                                    method.descriptor())));
                }
            }
        }
        return diagnostics;
    }
}
