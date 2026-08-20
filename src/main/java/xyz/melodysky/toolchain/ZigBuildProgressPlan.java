package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

final class ZigBuildProgressPlan {
    static final int MAX_COMPILE_UNITS = 64;

    private final List<TargetPlan> targets;
    private final boolean detailedProgress;

    private ZigBuildProgressPlan(List<TargetPlan> targets, boolean detailedProgress) {
        this.targets = List.copyOf(targets);
        this.detailedProgress = detailedProgress;
    }

    static ZigBuildProgressPlan forSources(NativeBuildPlan buildPlan, ZigSourceSet sources) {
        return forInventory(buildPlan, inventory(sources));
    }

    static ZigBuildProgressPlan forInventory(
            NativeBuildPlan buildPlan,
            CompileInputInventory inventory) {
        Objects.requireNonNull(buildPlan, "buildPlan");
        Objects.requireNonNull(inventory, "inventory");
        return forUnits(buildPlan, balancedUnits(inventory.inputs()), true);
    }

    static CompileInputInventory inventory(ZigSourceSet sources) {
        Objects.requireNonNull(sources, "sources");
        ArrayList<CompileInput> compileInputs = new ArrayList<>();
        addInputs(compileInputs, CompileInputKind.C, sources.cSources());
        addInputs(compileInputs, CompileInputKind.LLVM, sources.llvmSources());
        return new CompileInputInventory(compileInputs);
    }

    static ZigBuildProgressPlan linkOnly(NativeBuildPlan buildPlan) {
        Objects.requireNonNull(buildPlan, "buildPlan");
        return forUnits(buildPlan, List.of(), false);
    }

    List<TargetPlan> targets() {
        return targets;
    }

    boolean detailedProgress() {
        return detailedProgress;
    }

    private static ZigBuildProgressPlan forUnits(
            NativeBuildPlan buildPlan,
            List<CompileUnit> compileUnits,
            boolean detailedProgress) {
        List<TargetPlan> targets = buildPlan.units().stream()
                .map(unit -> new TargetPlan(unit, compileUnits))
                .toList();
        return new ZigBuildProgressPlan(targets, detailedProgress);
    }

    private static void addInputs(
            List<CompileInput> target,
            CompileInputKind kind,
            List<Path> sources) {
        for (int index = 0; index < sources.size(); index++) {
            target.add(new CompileInput(kind.idPrefix() + "-" + index, kind, sources.get(index)));
        }
    }

    private static List<CompileUnit> balancedUnits(List<CompileInput> inputs) {
        if (inputs.size() <= MAX_COMPILE_UNITS) {
            return inputs.stream()
                    .map(input -> new CompileUnit(input.id(), List.of(input)))
                    .toList();
        }

        EnumMap<CompileInputKind, List<CompileInput>> inputsByKind =
                new EnumMap<>(CompileInputKind.class);
        for (CompileInputKind kind : CompileInputKind.values()) {
            List<CompileInput> matching = inputs.stream()
                    .filter(input -> input.kind() == kind)
                    .toList();
            if (!matching.isEmpty()) {
                inputsByKind.put(kind, matching);
            }
        }
        EnumMap<CompileInputKind, Integer> unitsByKind =
                allocateUnitCounts(inputsByKind);
        ArrayList<CompileUnit> units =
                new ArrayList<>(MAX_COMPILE_UNITS);
        for (CompileInputKind kind : CompileInputKind.values()) {
            List<CompileInput> matching = inputsByKind.get(kind);
            if (matching == null) {
                continue;
            }
            int unitCount = unitsByKind.get(kind);
            for (int unitIndex = 0;
                    unitIndex < unitCount;
                    unitIndex++) {
                int start =
                        unitIndex * matching.size() / unitCount;
                int end = (unitIndex + 1)
                        * matching.size()
                        / unitCount;
                units.add(new CompileUnit(
                        kind.idPrefix()
                                + "-batch-"
                                + unitIndex,
                        matching.subList(start, end)));
            }
        }
        return List.copyOf(units);
    }

    private static EnumMap<CompileInputKind, Integer>
            allocateUnitCounts(
                    EnumMap<CompileInputKind, List<CompileInput>>
                            inputsByKind) {
        EnumMap<CompileInputKind, Integer> result =
                new EnumMap<>(CompileInputKind.class);
        for (CompileInputKind kind : inputsByKind.keySet()) {
            result.put(kind, 1);
        }
        int remaining = MAX_COMPILE_UNITS - result.size();
        while (remaining > 0) {
            CompileInputKind selected = null;
            for (CompileInputKind candidate
                    : CompileInputKind.values()) {
                List<CompileInput> matching =
                        inputsByKind.get(candidate);
                if (matching == null
                        || result.get(candidate)
                                >= matching.size()) {
                    continue;
                }
                if (selected == null
                        || hasHigherCurrentLoad(
                                matching.size(),
                                result.get(candidate),
                                inputsByKind.get(selected).size(),
                                result.get(selected))) {
                    selected = candidate;
                }
            }
            if (selected == null) {
                break;
            }
            result.put(selected, result.get(selected) + 1);
            remaining--;
        }
        return result;
    }

    private static boolean hasHigherCurrentLoad(
            int leftInputs,
            int leftUnits,
            int rightInputs,
            int rightUnits) {
        return (long) leftInputs * rightUnits
                > (long) rightInputs * leftUnits;
    }

    record TargetPlan(NativeBuildUnit buildUnit, List<CompileUnit> compileUnits) {
        TargetPlan {
            Objects.requireNonNull(buildUnit, "buildUnit");
            compileUnits = List.copyOf(compileUnits);
        }

        TargetTriple target() {
            return buildUnit.target();
        }

        int totalUnits() {
            return compileUnits.size() + 1;
        }
    }

    record CompileUnit(String id, List<CompileInput> inputs) {
        CompileUnit {
            Objects.requireNonNull(id, "id");
            inputs = List.copyOf(inputs);
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("compile progress unit must contain an input");
            }
        }

        CompileInputKind kind() {
            CompileInputKind kind = inputs.get(0).kind();
            if (inputs.stream()
                    .anyMatch(input -> input.kind() != kind)) {
                throw new IllegalStateException(
                        "compile progress units must be homogeneous");
            }
            return kind;
        }
    }

    record CompileInput(String id, CompileInputKind kind, Path source) {
        CompileInput {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(source, "source");
        }
    }

    record CompileInputInventory(List<CompileInput> inputs) {
        CompileInputInventory {
            inputs = List.copyOf(inputs);
        }
    }

    enum CompileInputKind {
        C("c"),
        LLVM("llvm");

        private final String idPrefix;

        CompileInputKind(String idPrefix) {
            this.idPrefix = idPrefix;
        }

        String idPrefix() {
            return idPrefix;
        }
    }
}
