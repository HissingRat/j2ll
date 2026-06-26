package xyz.melodysky.runtime.metadata;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;

public final class RuntimeMetadataValidator {
    public List<Diagnostic> validate(RuntimeMetadataIndex index) {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        HashSet<String> classNames = new HashSet<>();
        for (ClassMetadata classMetadata : index.classes()) {
            if (!classNames.add(classMetadata.internalName())) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.VALIDATION,
                                RuntimeMetadataDiagnostics.DUPLICATE_CLASS_METADATA,
                                "duplicate runtime metadata for class " + classMetadata.internalName())
                        .at(DiagnosticLocation.classLocation(classMetadata.internalName())));
            }
            validateClass(classMetadata, diagnostics);
        }
        return diagnostics;
    }

    private void validateClass(ClassMetadata classMetadata, List<Diagnostic> diagnostics) {
        HashSet<String> methodKeys = new HashSet<>();
        for (MethodMetadata method : classMetadata.methods()) {
            if (!methodKeys.add(method.name() + "!" + method.descriptor())) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.VALIDATION,
                                RuntimeMetadataDiagnostics.DUPLICATE_METHOD_METADATA,
                                "duplicate runtime metadata for method " + method.methodKey())
                        .at(DiagnosticLocation.methodLocation(method.owner(), method.name(), method.descriptor())));
            }
        }

        HashSet<String> fieldKeys = new HashSet<>();
        for (FieldMetadata field : classMetadata.fields()) {
            if (!fieldKeys.add(field.name() + "!" + field.descriptor())) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.VALIDATION,
                                RuntimeMetadataDiagnostics.DUPLICATE_FIELD_METADATA,
                                "duplicate runtime metadata for field " + field.fieldKey())
                        .at(DiagnosticLocation.classLocation(field.owner())));
            }
        }

        if (classMetadata.classInitMetadata().classObjectHandle().isBlank()
                || classMetadata.classInitMetadata().initStateHandle().isBlank()) {
            diagnostics.add(Diagnostic.error(
                            DiagnosticStage.VALIDATION,
                            RuntimeMetadataDiagnostics.INVALID_CLASS_INIT_METADATA,
                            "class init metadata has blank handles for " + classMetadata.internalName())
                    .at(DiagnosticLocation.classLocation(classMetadata.internalName())));
        }
    }
}
