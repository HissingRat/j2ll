package xyz.melodysky.analysis.field;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.frontend.classfile.ParsedField;
import xyz.melodysky.ir.pass.protection.ProtectionRandom;
import xyz.melodysky.jvm.AccessFlags;

public final class NativeFieldInternalizationPlanner {
    private static final String REFERENCE_SIDECAR_ORDER_DOMAIN =
            "FIELD_REFERENCE_SIDECAR_ORDER";

    public NativeFieldInternalizationPlan plan(
            FieldUseIndex useIndex,
            AnalysisWorld worldModel,
            boolean worldComplete,
            long protectionSeed,
            FieldAccessPathResolver pathResolver) {
        return plan(
                useIndex,
                worldModel == AnalysisWorld.CLOSED_WORLD
                        ? WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD
                        : WholeProgramAnalysisScope.UNAVAILABLE,
                worldComplete,
                protectionSeed,
                pathResolver);
    }

    public NativeFieldInternalizationPlan plan(
            FieldUseIndex useIndex,
            WholeProgramAnalysisScope analysisScope,
            boolean worldComplete,
            long protectionSeed,
            FieldAccessPathResolver pathResolver) {
        Objects.requireNonNull(useIndex, "useIndex");
        Objects.requireNonNull(analysisScope, "analysisScope");
        Objects.requireNonNull(pathResolver, "pathResolver");

        TreeMap<FieldId, ParsedField> declarations = new TreeMap<>();
        for (ParsedField field : useIndex.inputBaseFields()) {
            declarations.putIfAbsent(id(field), field);
        }

        ProtectionRandom random = new ProtectionRandom(protectionSeed);
        HashSet<String> allocatedSlots = new HashSet<>();
        ArrayList<NativeFieldInternalizationDecision> decisions = new ArrayList<>();
        for (var entry : declarations.entrySet()) {
            FieldId fieldId = entry.getKey();
            ParsedField field = entry.getValue();
            List<FieldAccessSite> accesses = useIndex.accessesFor(fieldId);
            EnumSet<FieldInternalizationReason> reasons = EnumSet.noneOf(FieldInternalizationReason.class);

            addWorldReasons(analysisScope, worldComplete, reasons);
            if (useIndex.hasUnresolvedReferenceForOwner(fieldId.owner())) {
                reasons.add(FieldInternalizationReason.UNRESOLVED_FIELD_REFERENCE);
            }
            addDeclarationReasons(useIndex, fieldId, field, reasons);
            addAccessReasons(fieldId, accesses, pathResolver, reasons);
            addDynamicBoundaryReasons(useIndex, fieldId.owner(), reasons);

            if (reasons.isEmpty()) {
                String slot = allocateSlot(random, fieldId, allocatedSlots);
                decisions.add(new NativeFieldInternalizationDecision(
                        fieldId,
                        FieldInternalizationStatus.INTERNALIZED,
                        java.util.Optional.of(slot),
                        accesses,
                        List.of(FieldInternalizationReason.FIELD_INTERNALIZATION_ELIGIBLE)));
            } else {
                decisions.add(new NativeFieldInternalizationDecision(
                        fieldId,
                        FieldInternalizationStatus.KEPT,
                        java.util.Optional.empty(),
                        accesses,
                        List.copyOf(reasons)));
            }
        }
        return new NativeFieldInternalizationPlan(
                decisions,
                allocateReferenceIndices(decisions, random));
    }

    private void addWorldReasons(
            WholeProgramAnalysisScope analysisScope,
            boolean worldComplete,
            Set<FieldInternalizationReason> reasons) {
        if (!analysisScope.permitsWholeProgramTransform()) {
            reasons.add(FieldInternalizationReason.WORLD_NOT_CLOSED);
        }
        if (analysisScope == WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD
                && !worldComplete) {
            reasons.add(FieldInternalizationReason.WORLD_INCOMPLETE);
        }
    }

    private void addDeclarationReasons(
            FieldUseIndex useIndex,
            FieldId fieldId,
            ParsedField field,
            Set<FieldInternalizationReason> reasons) {
        if (useIndex.hasAmbiguousInputBaseDeclaration(fieldId)) {
            reasons.add(FieldInternalizationReason.AMBIGUOUS_INPUT_DECLARATION);
        }
        if (useIndex.hasMultiReleaseCounterpart(field.owner())) {
            reasons.add(FieldInternalizationReason.MULTI_RELEASE_OWNER);
        }
        AccessFlags access = field.accessFlags();
        if (!access.isPrivate()) {
            reasons.add(FieldInternalizationReason.FIELD_NOT_PRIVATE);
        }
        if (!access.isStatic()) {
            reasons.add(FieldInternalizationReason.FIELD_NOT_STATIC);
        }
        if (NativeFieldStorageKind.fromDescriptor(field.descriptor()).isEmpty()) {
            reasons.add(FieldInternalizationReason.FIELD_TYPE_UNSUPPORTED);
        }
        if (access.isFinal()) {
            reasons.add(FieldInternalizationReason.FIELD_FINAL);
        }
        if (access.isVolatile()) {
            reasons.add(FieldInternalizationReason.FIELD_VOLATILE);
        }
        if (access.isSynthetic() || access.has(AccessFlags.ENUM)) {
            reasons.add(FieldInternalizationReason.FIELD_SYNTHETIC_OR_COMPILER_GENERATED);
        }
        if (field.hasConstantValue()) {
            reasons.add(FieldInternalizationReason.FIELD_HAS_CONSTANT_VALUE);
        }
        if (field.signature() != null) {
            reasons.add(FieldInternalizationReason.FIELD_HAS_SIGNATURE);
        }
        if (field.hasAnnotations()) {
            reasons.add(FieldInternalizationReason.FIELD_HAS_ANNOTATIONS);
        }
        if (useIndex.isSerializableOwner(field.owner())) {
            reasons.add(FieldInternalizationReason.OWNER_IS_SERIALIZABLE);
        }
    }

    private void addAccessReasons(
            FieldId field,
            List<FieldAccessSite> accesses,
            FieldAccessPathResolver pathResolver,
            Set<FieldInternalizationReason> reasons) {
        if (accesses.isEmpty()) {
            reasons.add(FieldInternalizationReason.FIELD_HAS_NO_ACCESS);
            return;
        }
        for (FieldAccessSite access : accesses) {
            if (access.methodName().equals("<clinit>")) {
                reasons.add(FieldInternalizationReason.OWNER_HAS_CLASS_INITIALIZER);
                reasons.add(FieldInternalizationReason.CLASS_INITIALIZER_ACCESS);
            }
            if (!access.methodStatic()) {
                reasons.add(FieldInternalizationReason.ACCESS_METHOD_NOT_STATIC);
            }
            if (!access.referenceKind().staticAccess()) {
                reasons.add(FieldInternalizationReason.INSTANCE_FIELD_REFERENCE);
            }
            if (access.referenceKind().methodHandle()) {
                reasons.add(FieldInternalizationReason.METHOD_HANDLE_FIELD_REFERENCE);
            }
            if (access.origin() == FieldCodeOrigin.CLASSPATH) {
                reasons.add(FieldInternalizationReason.CLASSPATH_FIELD_ACCESS);
            }
            if (!access.methodOwner().equals(field.owner())) {
                reasons.add(FieldInternalizationReason.CROSS_OWNER_FIELD_ACCESS);
            }
            FieldAccessImplementationPath path = pathResolver.finalPathFor(access.methodKey());
            if (path == null || path == FieldAccessImplementationPath.UNKNOWN) {
                reasons.add(FieldInternalizationReason.ACCESS_PATH_UNKNOWN);
            } else if (path != FieldAccessImplementationPath.LLVM_NATIVE_PATH) {
                reasons.add(FieldInternalizationReason.ACCESS_PATH_NOT_LLVM_NATIVE);
            }
        }
    }

    private void addDynamicBoundaryReasons(
            FieldUseIndex useIndex,
            String owner,
            Set<FieldInternalizationReason> reasons) {
        for (FieldDynamicBoundary boundary : useIndex.dynamicBoundariesForOwner(owner)) {
            if (boundary.kind() == FieldDynamicBoundaryKind.DYNAMIC_CLASS_LOADING) {
                // Loading or defining a class is not itself a field
                // observation. Actual direct/handle field references and
                // reflection/JNI/Unsafe surfaces remain separate fail-closed
                // evidence.
                continue;
            }
            reasons.add(switch (boundary.kind()) {
                case REFLECTION -> FieldInternalizationReason.REFLECTION_DYNAMIC_SURFACE;
                case UNSAFE -> FieldInternalizationReason.UNSAFE_DYNAMIC_SURFACE;
                case VAR_HANDLE -> FieldInternalizationReason.VAR_HANDLE_DYNAMIC_SURFACE;
                case METHOD_HANDLE -> FieldInternalizationReason.METHOD_HANDLE_DYNAMIC_SURFACE;
                case NATIVE_JNI -> FieldInternalizationReason.NATIVE_JNI_DYNAMIC_SURFACE;
                case SERIALIZATION -> FieldInternalizationReason.SERIALIZATION_DYNAMIC_SURFACE;
                case AGENT_INSTRUMENTATION -> FieldInternalizationReason.AGENT_INSTRUMENTATION_DYNAMIC_SURFACE;
                case DYNAMIC_CLASS_LOADING -> FieldInternalizationReason.DYNAMIC_CLASS_LOADING_SURFACE;
            });
        }
        if (useIndex.isSerializableOwner(owner)) {
            reasons.add(FieldInternalizationReason.OWNER_IS_SERIALIZABLE);
        }
    }

    private String allocateSlot(
            ProtectionRandom random,
            FieldId field,
            Set<String> allocatedSlots) {
        int collision = 0;
        while (true) {
            String stableInput = collision == 0 ? field.fieldKey() : field.fieldKey() + ":" + collision;
            String slot = "j2ll_nfs_" + random.token("FIELD_INTERNALIZATION_SLOT", stableInput, 32);
            if (allocatedSlots.add(slot)) {
                return slot;
            }
            collision++;
        }
    }

    private Map<String, Map<FieldId, Integer>> allocateReferenceIndices(
            List<NativeFieldInternalizationDecision> decisions,
            ProtectionRandom random) {
        TreeMap<String, ArrayList<FieldId>> fieldsByOwner = new TreeMap<>();
        decisions.stream()
                .filter(NativeFieldInternalizationDecision::internalized)
                .map(NativeFieldInternalizationDecision::field)
                .filter(field -> NativeFieldStorageKind.fromDescriptor(field.descriptor())
                        .filter(NativeFieldStorageKind::reference)
                        .isPresent())
                .forEach(field -> fieldsByOwner
                        .computeIfAbsent(field.owner(), ignored -> new ArrayList<>())
                        .add(field));

        Comparator<FieldId> diversifiedOrder = Comparator
                .comparing((FieldId field) -> random.token(
                        REFERENCE_SIDECAR_ORDER_DOMAIN,
                        field.fieldKey(),
                        64))
                .thenComparing(FieldId::fieldKey);
        LinkedHashMap<String, Map<FieldId, Integer>> result = new LinkedHashMap<>();
        fieldsByOwner.forEach((owner, fields) -> {
            fields.sort(diversifiedOrder);
            LinkedHashMap<FieldId, Integer> indices = new LinkedHashMap<>();
            for (int index = 0; index < fields.size(); index++) {
                indices.put(fields.get(index), index);
            }
            result.put(owner, indices);
        });
        return result;
    }

    private FieldId id(ParsedField field) {
        return new FieldId(field.owner(), field.name(), field.descriptor());
    }
}
