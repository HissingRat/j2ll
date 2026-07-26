package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.ArrayList;
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
        Objects.requireNonNull(buildPlan, "buildPlan");
        Objects.requireNonNull(sources, "sources");
        ArrayList<CompileInput> compileInputs = new ArrayList<>();
        addInputs(compileInputs, CompileInputKind.C, sources.cSources());
        addInputs(compileInputs, CompileInputKind.LLVM, sources.llvmSources());
        return forUnits(buildPlan, balancedUnits(compileInputs), true);
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
        int unitCount = MAX_COMPILE_UNITS;
        ArrayList<CompileUnit> units = new ArrayList<>(unitCount);
        for (int unitIndex = 0; unitIndex < unitCount; unitIndex++) {
            int start = unitIndex * inputs.size() / unitCount;
            int end = (unitIndex + 1) * inputs.size() / unitCount;
            units.add(new CompileUnit(
                    "batch-" + unitIndex,
                    inputs.subList(start, end)));
        }
        return List.copyOf(units);
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
    }

    record CompileInput(String id, CompileInputKind kind, Path source) {
        CompileInput {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(source, "source");
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
