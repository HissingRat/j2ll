package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.toolchain.localref.NativeLocalReferenceInstructionSite;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlan;
import xyz.melodysky.toolchain.localref.NativeLocalReferenceReleaseSchedule;

final class HostJniLocalReferenceRuntimeSourceTest {
    private static final String METHOD_KEY = "pkg/Example#work!()V";

    @Test
    void helperDeletesOnlyOwnedNonNullReferences() {
        String source =
                HostJniLocalReferenceRuntimeSource.helperSource();

        assertTrue(source.contains(
                "void j2ll_rt_release_local_ref(\n"
                        + "        JNIEnv* env, jobject value, int32_t owned)"));
        assertTrue(source.contains(
                "if (owned != 0 && value != NULL)"));
        assertTrue(source.contains(
                "(*env)->DeleteLocalRef(env, value);"));
    }

    @Test
    void generatorEmitsHelperOnlyForAPlanWithScheduledReleases() {
        NativeImplementationPlan noPlan =
                new NativeImplementationPlan(List.of());
        NativeImplementationPlan ownershipWithoutRelease =
                implementationPlan(planWithRelease(false));
        NativeImplementationPlan scheduledRelease =
                implementationPlan(planWithRelease(true));
        StringBuilder noPlanSource = new StringBuilder();
        StringBuilder ownershipOnlySource = new StringBuilder();
        StringBuilder scheduledSource = new StringBuilder();

        HostJniLocalReferenceRuntimeSource.appendIfNeeded(
                noPlanSource,
                noPlan);
        HostJniLocalReferenceRuntimeSource.appendIfNeeded(
                ownershipOnlySource,
                ownershipWithoutRelease);
        HostJniLocalReferenceRuntimeSource.appendIfNeeded(
                scheduledSource,
                scheduledRelease);

        assertFalse(noPlanSource.toString()
                .contains("j2ll_rt_release_local_ref"));
        assertFalse(ownershipOnlySource.toString()
                .contains("j2ll_rt_release_local_ref"));
        assertTrue(scheduledSource.toString()
                .contains("j2ll_rt_release_local_ref"));
    }

    private NativeImplementationPlan implementationPlan(
            NativeLocalReferencePlan localReferencePlan) {
        return new NativeImplementationPlan(
                List.of(),
                Map.of(),
                Map.of(METHOD_KEY, localReferencePlan));
    }

    private NativeLocalReferencePlan planWithRelease(
            boolean emitsRelease) {
        IrValue reference =
                new IrValue("%owned", IrType.REFERENCE);
        Map<NativeLocalReferenceInstructionSite,
                        NativeLocalReferenceReleaseSchedule>
                instructionReleases = emitsRelease
                        ? Map.of(
                                new NativeLocalReferenceInstructionSite(
                                        "entry",
                                        0),
                                new NativeLocalReferenceReleaseSchedule(
                                        List.of(reference),
                                        List.of()))
                        : Map.of();
        return new NativeLocalReferencePlan(
                METHOD_KEY,
                Map.of(),
                instructionReleases,
                Map.of(),
                Map.of());
    }
}
