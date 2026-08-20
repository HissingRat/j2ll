package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class NativeRegistrationControlRootConditionalMutationTest {
    @Test
    void rejectsConstantPredicateAndSequentializedRouteBranches() {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        11,
                        "registration-root-conditional-mutations");
        NativeRegistrationControlRoutePlan routes =
                emission.topologyPlan().routePlan();
        String root = NativeRegistrationControlTestFixture.functionAtHeader(
                emission.source(),
                "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)");
        String condition = "    if ((((witness >> "
                + routes.rootSelectorShift()
                + "u) ^ guard) & (uintptr_t)1u) == (uintptr_t)0u) {\n";

        assertRejected(
                emission,
                replaceRoot(
                        emission.source(),
                        root,
                        replaceOnce(
                                root,
                                condition,
                                "    if ((uintptr_t)1u) {\n")));
        assertRejected(
                emission,
                replaceRoot(
                        emission.source(),
                        root,
                        replaceOnce(
                                root,
                                "    } else {\n",
                                "    }\n    {\n")));
    }

    private String replaceRoot(
            String source,
            String root,
            String replacement) {
        return replaceOnce(source, root, replacement);
    }

    private String replaceOnce(
            String source,
            String before,
            String after) {
        assertEquals(
                1,
                NativeRegistrationControlTestFixture.occurrences(
                        source,
                        before));
        return source.replace(before, after);
    }

    private void assertRejected(
            HostNativeRegistrationSource.Emission emission,
            String source) {
        assertThrows(
                IllegalStateException.class,
                () -> new NativeRegistrationControlSourceVerifier().verify(
                        source,
                        emission.topologyPlan()));
    }
}
