package xyz.melodysky.analysis.hierarchy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.pipeline.StageValidator;

public final class ClassHierarchyValidator implements StageValidator<ClassHierarchy> {
    @Override
    public DiagnosticStage stage() {
        return DiagnosticStage.VALIDATION;
    }

    @Override
    public List<Diagnostic> validate(ClassHierarchy artifact) {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (HierarchyClass hierarchyClass : artifact.classes()) {
            if (!seen.add(hierarchyClass.internalName())) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.VALIDATION,
                                HierarchyDiagnostics.DUPLICATE_HIERARCHY_CLASS,
                                "duplicate hierarchy class " + hierarchyClass.internalName())
                        .at(DiagnosticLocation.classLocation(hierarchyClass.internalName())));
            }
            for (String interfaceName : hierarchyClass.interfaces()) {
                if (artifact.lookupClass(interfaceName).isEmpty()) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.VALIDATION,
                                    HierarchyDiagnostics.MISSING_EXTERNAL_CLASS,
                                    "interface target is not present in hierarchy " + interfaceName)
                            .at(DiagnosticLocation.classLocation(hierarchyClass.internalName())));
                }
            }
        }
        return diagnostics;
    }
}
