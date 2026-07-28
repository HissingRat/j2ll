package xyz.melodysky.runtime.jni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.runtime.RuntimeTokenMapper;

final class RuntimeLocalAbiPlannerTest {
    private final RuntimeLocalAbiPlanner planner =
            new RuntimeLocalAbiPlanner();

    @Test
    void planIsBindingScopedReproducibleAndBuildDiverse() {
        RuntimeLocalAbiPlan first = plan(
                "build-one",
                RuntimeLocalAbiDomain.FIELD,
                "field_put_instance_i64",
                "pkg/Owner#secret!J",
                8);
        RuntimeLocalAbiPlan repeated = plan(
                "build-one",
                RuntimeLocalAbiDomain.FIELD,
                "field_put_instance_i64",
                "pkg/Owner#secret!J",
                8);
        RuntimeLocalAbiPlan anotherBinding = plan(
                "build-one",
                RuntimeLocalAbiDomain.FIELD,
                "field_put_instance_i64",
                "pkg/Owner#other!J",
                8);
        RuntimeLocalAbiPlan anotherBuild = plan(
                "build-two",
                RuntimeLocalAbiDomain.FIELD,
                "field_put_instance_i64",
                "pkg/Owner#secret!J",
                8);

        assertEquals(first, repeated);
        assertNotEquals(
                first.physicalSlots(),
                anotherBinding.physicalSlots());
        assertEquals(
                8,
                anotherBuild.physicalSlots().size());
        assertEquals(
                java.util.Set.copyOf(
                        java.util.stream.IntStream.range(0, 8)
                                .boxed()
                                .toList()),
                java.util.Set.copyOf(
                        anotherBuild.physicalSlots()));
    }

    @Test
    void domainSeparatesIdenticalBindingIdentity() {
        RuntimeLocalAbiPlan field = plan(
                "build",
                RuntimeLocalAbiDomain.FIELD,
                "lookup",
                "same",
                8);
        RuntimeLocalAbiPlan dispatch = plan(
                "build",
                RuntimeLocalAbiDomain.DISPATCH,
                "lookup",
                "same",
                8);

        assertNotEquals(
                field.physicalSlots(),
                dispatch.physicalSlots());
    }

    @Test
    void arrangementPreservesEveryLogicalValueWithoutSyntheticArguments() {
        RuntimeLocalAbiPlan plan = plan(
                "build",
                RuntimeLocalAbiDomain.REFLECTION,
                "reflection_lookup_method",
                "method:pkg/Owner#run!()V",
                1);

        List<String> arranged = plan.arrange(List.of("env"));

        assertEquals(List.of("env"), arranged);
    }

    @Test
    void rejectsInvalidOperationAndWrongLogicalArity() {
        RuntimeTokenMapper tokens = mapper("build");
        assertThrows(
                IllegalArgumentException.class,
                () -> planner.plan(
                        tokens,
                        RuntimeLocalAbiDomain.JDK,
                        "not-safe",
                        "identity",
                        1));
        RuntimeLocalAbiPlan plan = planner.plan(
                tokens,
                RuntimeLocalAbiDomain.JDK,
                "safe",
                "identity",
                1);
        assertThrows(
                IllegalArgumentException.class,
                () -> plan.arrange(List.of()));
    }

    private RuntimeLocalAbiPlan plan(
            String build,
            RuntimeLocalAbiDomain domain,
            String operation,
            String identity,
            int arity) {
        return planner.plan(
                mapper(build),
                domain,
                operation,
                identity,
                arity);
    }

    private RuntimeTokenMapper mapper(String build) {
        return RuntimeTokenMapper.fromBytes(
                build.getBytes(StandardCharsets.UTF_8));
    }
}
