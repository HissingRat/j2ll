package xyz.melodysky.analysis.field;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedProgram;

/** Program-level entry point for exact/owner/global dynamic field observations. */
public final class FieldDynamicObserverAnalyzer {
    public FieldDynamicObservationPlan analyze(
            ParsedProgram inputProgram,
            List<ParsedProgram> classpathPrograms) {
        Objects.requireNonNull(inputProgram, "inputProgram");
        classpathPrograms = List.copyOf(Objects.requireNonNull(
                classpathPrograms,
                "classpathPrograms"));
        FieldObserverDeclarationIndex declarations =
                FieldObserverDeclarationIndex.create(inputProgram, classpathPrograms);
        FieldObserverMethodAnalyzer methodAnalyzer = new FieldObserverMethodAnalyzer();
        ArrayList<FieldDynamicObservation> observations = new ArrayList<>();
        analyzeProgram(inputProgram, declarations, methodAnalyzer, observations);
        for (ParsedProgram classpathProgram : classpathPrograms) {
            analyzeProgram(classpathProgram, declarations, methodAnalyzer, observations);
        }
        return new FieldDynamicObservationPlan(observations);
    }

    private void analyzeProgram(
            ParsedProgram program,
            FieldObserverDeclarationIndex declarations,
            FieldObserverMethodAnalyzer methodAnalyzer,
            List<FieldDynamicObservation> observations) {
        for (ParsedClass parsedClass : program.classes()) {
            parsedClass.methods().forEach(method -> {
                if (method.accessFlags().isNative()) {
                    observations.add(FieldDynamicObservation.global(
                            FieldDynamicBoundaryKind.NATIVE_JNI,
                            method.methodKey(),
                            0));
                }
                observations.addAll(methodAnalyzer.analyze(method, declarations));
            });
        }
    }
}
