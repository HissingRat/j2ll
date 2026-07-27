package xyz.melodysky.analysis.hierarchy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedField;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.jvm.FieldSignature;
import xyz.melodysky.jvm.MethodSignature;
import xyz.melodysky.pipeline.StageResult;

public final class ClassHierarchyBuilder {
    public StageResult<ClassHierarchy> build(ParsedProgram program, AnalysisWorld worldModel) {
        LinkedHashMap<String, HierarchyClass> classes = new LinkedHashMap<>();
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();

        for (ParsedClass parsedClass : program.classes()) {
            HierarchyClass hierarchyClass = toHierarchyClass(parsedClass);
            if (classes.put(hierarchyClass.internalName(), hierarchyClass) != null) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.HIERARCHY,
                                HierarchyDiagnostics.DUPLICATE_HIERARCHY_CLASS,
                                "duplicate hierarchy class " + hierarchyClass.internalName())
                        .at(DiagnosticLocation.classLocation(hierarchyClass.internalName())));
            }
        }

        addExternalPlaceholders(classes, diagnostics);
        diagnostics.addAll(findCycles(classes));

        boolean hasErrors = diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().wireName().equals("error"));
        if (hasErrors) {
            return StageResult.failed(DiagnosticStage.HIERARCHY, diagnostics);
        }

        boolean complete = diagnostics.stream().noneMatch(diagnostic -> diagnostic.code().equals(HierarchyDiagnostics.MISSING_EXTERNAL_CLASS));
        ClassHierarchy hierarchy = new ClassHierarchy(worldModel, classes.values(), complete);
        return complete
                ? StageResult.complete(DiagnosticStage.HIERARCHY, hierarchy, diagnostics)
                : StageResult.conservative(DiagnosticStage.HIERARCHY, hierarchy, diagnostics);
    }

    private HierarchyClass toHierarchyClass(ParsedClass parsedClass) {
        ArrayList<HierarchyMethod> methods = new ArrayList<>();
        for (ParsedMethod method : parsedClass.methods()) {
            methods.add(new HierarchyMethod(
                    parsedClass.internalName(),
                    new MethodSignature(method.name(), method.descriptor()),
                    method.accessFlags(),
                    method.hasCode(),
                    false));
        }

        ArrayList<HierarchyField> fields = new ArrayList<>();
        for (ParsedField field : parsedClass.fields()) {
            fields.add(new HierarchyField(
                    parsedClass.internalName(),
                    new FieldSignature(field.name(), field.descriptor()),
                    field.accessFlags(),
                    false));
        }

        return new HierarchyClass(
                parsedClass.internalName(),
                parsedClass.accessFlags(),
                parsedClass.superName(),
                parsedClass.interfaces(),
                methods,
                fields,
                false);
    }

    private void addExternalPlaceholders(
            LinkedHashMap<String, HierarchyClass> classes,
            List<Diagnostic> diagnostics) {
        ArrayList<String> missingNames = new ArrayList<>();
        for (HierarchyClass hierarchyClass : List.copyOf(classes.values())) {
            addMissing(classes, missingNames, hierarchyClass.superName());
            for (String interfaceName : hierarchyClass.interfaces()) {
                addMissing(classes, missingNames, interfaceName);
            }
        }

        missingNames.stream().distinct().sorted().forEach(missingName -> {
            classes.put(missingName, HierarchyClass.externalPlaceholder(missingName));
            diagnostics.add(Diagnostic.warning(
                            DiagnosticStage.HIERARCHY,
                            HierarchyDiagnostics.MISSING_EXTERNAL_CLASS,
                            "created conservative external placeholder for " + missingName)
                    .at(DiagnosticLocation.classLocation(missingName)));
        });
    }

    private void addMissing(Map<String, HierarchyClass> classes, List<String> missingNames, String maybeMissing) {
        if (maybeMissing != null && !classes.containsKey(maybeMissing)) {
            missingNames.add(maybeMissing);
        }
    }

    private List<Diagnostic> findCycles(Map<String, HierarchyClass> classes) {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        HashSet<String> visited = new HashSet<>();
        HashSet<String> visiting = new HashSet<>();
        for (HierarchyClass hierarchyClass : classes.values()) {
            detectCycle(hierarchyClass.internalName(), classes, visited, visiting, diagnostics);
        }
        return diagnostics;
    }

    private void detectCycle(
            String className,
            Map<String, HierarchyClass> classes,
            Set<String> visited,
            Set<String> visiting,
            List<Diagnostic> diagnostics) {
        if (visited.contains(className)) {
            return;
        }
        if (!visiting.add(className)) {
            diagnostics.add(Diagnostic.error(
                            DiagnosticStage.HIERARCHY,
                            HierarchyDiagnostics.HIERARCHY_CYCLE,
                            "hierarchy cycle includes " + className)
                    .at(DiagnosticLocation.classLocation(className)));
            return;
        }

        HierarchyClass hierarchyClass = classes.get(className);
        if (hierarchyClass != null && !hierarchyClass.external()) {
            if (hierarchyClass.superName() != null) {
                detectCycle(hierarchyClass.superName(), classes, visited, visiting, diagnostics);
            }
            for (String interfaceName : hierarchyClass.interfaces()) {
                detectCycle(interfaceName, classes, visited, visiting, diagnostics);
            }
        }

        visiting.remove(className);
        visited.add(className);
    }
}
