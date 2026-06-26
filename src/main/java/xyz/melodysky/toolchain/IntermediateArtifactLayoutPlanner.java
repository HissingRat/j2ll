package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public final class IntermediateArtifactLayoutPlanner {
    private static final int INITIAL_PREFIX_LENGTH = 16;
    private static final int PREFIX_STEP = 8;

    private final ClassArtifactPath path;

    public IntermediateArtifactLayoutPlanner() {
        this(new ClassArtifactPath());
    }

    IntermediateArtifactLayoutPlanner(Function<String, String> hashFunction) {
        this(new ClassArtifactPath(hashFunction));
    }

    private IntermediateArtifactLayoutPlanner(ClassArtifactPath path) {
        this.path = path;
    }

    public IntermediateArtifactLayout plan(List<ClassArtifactInput> inputs) {
        List<ClassArtifactInput> sortedInputs = inputs.stream()
                .sorted(Comparator.comparing(ClassArtifactInput::internalName))
                .toList();
        List<ClassPlan> classPlans = sortedInputs.stream()
                .map(input -> new ClassPlan(
                        input,
                        path.safeInternalName(input.internalName()),
                        path.fullHash(input.internalName()),
                        INITIAL_PREFIX_LENGTH))
                .toList();
        expandClassCollisions(classPlans);

        ArrayList<ClassArtifact> classes = new ArrayList<>();
        LinkedHashMap<String, List<MethodArtifact>> methodsByClass = new LinkedHashMap<>();
        for (ClassPlan plan : classPlans) {
            ClassArtifact artifact = new ClassArtifact(
                    plan.input.internalName(),
                    plan.hash,
                    plan.prefixLength,
                    classDirectory(plan),
                    plan.input.sourceEntry(),
                    plan.safeName);
            classes.add(artifact);
            methodsByClass.put(artifact.internalName(), planMethods(plan.input.methods()));
        }
        return new IntermediateArtifactLayout(classes, methodsByClass);
    }

    private List<MethodArtifact> planMethods(List<MethodArtifactInput> inputs) {
        List<MethodPlan> plans = inputs.stream()
                .sorted(Comparator
                        .comparing(MethodArtifactInput::name)
                        .thenComparing(MethodArtifactInput::descriptor))
                .map(input -> new MethodPlan(
                        input,
                        path.safeMethodName(input.name()),
                        path.methodHash(input.owner(), input.name(), input.descriptor()),
                        INITIAL_PREFIX_LENGTH))
                .toList();
        expandMethodCollisions(plans);
        return plans.stream()
                .map(plan -> new MethodArtifact(
                        plan.input.owner(),
                        plan.input.name(),
                        plan.input.descriptor(),
                        plan.hash,
                        plan.prefixLength,
                        methodId(plan),
                        plan.safeName,
                        plan.input.status()))
                .toList();
    }

    private void expandClassCollisions(List<ClassPlan> plans) {
        expandCollisions(
                plans,
                plan -> collisionKey(classDirectory(plan)),
                plan -> plan.prefixLength,
                (plan, length) -> plan.prefixLength = length);
    }

    private void expandMethodCollisions(List<MethodPlan> plans) {
        expandCollisions(
                plans,
                plan -> collisionKey(methodId(plan)),
                plan -> plan.prefixLength,
                (plan, length) -> plan.prefixLength = length);
    }

    private <T> void expandCollisions(
            List<T> plans,
            Function<T, String> collisionKey,
            Function<T, Integer> prefixLength,
            PrefixUpdater<T> updater) {
        boolean changed;
        do {
            changed = false;
            Map<String, List<T>> groups = new HashMap<>();
            for (T plan : plans) {
                groups.computeIfAbsent(collisionKey.apply(plan), ignored -> new ArrayList<>()).add(plan);
            }
            for (List<T> group : groups.values()) {
                if (group.size() < 2) {
                    continue;
                }
                for (T plan : group) {
                    int next = Math.min(64, prefixLength.apply(plan) + PREFIX_STEP);
                    if (next != prefixLength.apply(plan)) {
                        updater.update(plan, next);
                        changed = true;
                    }
                }
            }
        } while (changed);
    }

    private String classDirectory(ClassPlan plan) {
        return plan.safeName + "__" + plan.hash.substring(0, plan.prefixLength);
    }

    private String methodId(MethodPlan plan) {
        return plan.safeName + "__" + plan.hash.substring(0, plan.prefixLength);
    }

    private String collisionKey(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private interface PrefixUpdater<T> {
        void update(T plan, int prefixLength);
    }

    private static final class ClassPlan {
        private final ClassArtifactInput input;
        private final String safeName;
        private final String hash;
        private int prefixLength;

        private ClassPlan(ClassArtifactInput input, String safeName, String hash, int prefixLength) {
            this.input = input;
            this.safeName = safeName;
            this.hash = hash;
            this.prefixLength = prefixLength;
        }
    }

    private static final class MethodPlan {
        private final MethodArtifactInput input;
        private final String safeName;
        private final String hash;
        private int prefixLength;

        private MethodPlan(MethodArtifactInput input, String safeName, String hash, int prefixLength) {
            this.input = input;
            this.safeName = safeName;
            this.hash = hash;
            this.prefixLength = prefixLength;
        }
    }
}
