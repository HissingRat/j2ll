package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class NativeLibcRequirementPlanTest {
    @Test
    void exactGeneratedSourceWithoutLibraryCallsIsLibcFree() {
        NativeLibcRequirementPlan plan = NativeLibcRequirementPlan.inspect("""
                #include <stdlib.h>
                static int helper(int value) { return value + 1; }
                """);

        assertFalse(plan.required());
        assertTrue(plan.reasons().isEmpty());
    }

    @Test
    void heapMemoryAndStringCallsRetainLibcWithStableReasons() {
        NativeLibcRequirementPlan plan = NativeLibcRequirementPlan.inspect("""
                void f(const char* value) {
                    void* copy = malloc(strlen(value) + 1);
                    memcpy(copy, value, strlen(value) + 1);
                    free(copy);
                }
                """);

        assertTrue(plan.required());
        assertEquals(
                Set.of(
                        NativeLibcRequirementPlan.Reason.DYNAMIC_ALLOCATION,
                        NativeLibcRequirementPlan.Reason.MEMORY_ROUTINE,
                        NativeLibcRequirementPlan.Reason.STRING_ROUTINE),
                plan.reasons());
    }

    @Test
    void multipleCompileUnitsMergeRequirementsConservatively() {
        NativeLibcRequirementPlan plan = NativeLibcRequirementPlan.inspectAll(
                List.of("int a(void) { return 1; }", "void b(void) { free(0); }"));

        assertTrue(plan.required());
        assertEquals(
                Set.of(NativeLibcRequirementPlan.Reason.DYNAMIC_ALLOCATION),
                plan.reasons());
    }
}
