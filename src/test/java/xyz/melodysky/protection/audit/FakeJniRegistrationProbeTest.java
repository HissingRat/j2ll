package xyz.melodysky.protection.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FakeJniRegistrationProbeTest {
    private static final int JNI_VERSION_1_8 = 0x00010008;
    private final FakeJniRegistrationProbe probe =
            new FakeJniRegistrationProbe();

    @Test
    void fakeJavaVmCapturesBindingsOnlyThroughDynamicJniOnLoadObservation() {
        FakeJavaVm vm = new FakeJavaVm();

        RegistrationCaptureMetric metric = probe.observe(
                List.of("JNI_OnLoad"),
                observer -> fixtureJniOnLoad(vm.attach(observer)));
        assertTrue(metric.passed());
        assertEquals(
                FakeJniRegistrationProbe.CAPTURED_VIA_JNI_ONLOAD,
                metric.reasonCode());
        assertEquals(JNI_VERSION_1_8, metric.jniOnLoadResult());
        assertEquals(2, metric.capturedOwnerCount());
        assertEquals(3, metric.capturedBindingCount());
        assertTrue(metric.mappingAvailableOnlyAfterJniOnLoadObservation());
        assertFalse(metric.stableDirectRegistrationExportPresent());
    }

    @Test
    void stableLegacyRegistrationExportFailsTheMetric() {
        RegistrationCaptureMetric metric = probe.observe(
                List.of("JNI_OnLoad", "j2ll_register"),
                observer -> fixtureJniOnLoad(new FakeJniEnv(observer)));

        assertFalse(metric.passed());
        assertTrue(metric.stableDirectRegistrationExportPresent());
        assertFalse(metric.mappingAvailableOnlyAfterJniOnLoadObservation());
        assertEquals(
                FakeJniRegistrationProbe.STABLE_DIRECT_EXPORT_PRESENT,
                metric.reasonCode());
    }

    @Test
    void observerCannotBeReusedAsAStaticMappingOracle() {
        FakeJniRegistrationObserver[] escaped =
                new FakeJniRegistrationObserver[1];
        RegistrationCaptureMetric metric = probe.observe(
                List.of("JNI_OnLoad"),
                observer -> {
                    escaped[0] = observer;
                    return JNI_VERSION_1_8;
                });

        assertFalse(metric.passed());
        assertEquals(
                FakeJniRegistrationProbe.NO_BINDINGS_OBSERVED,
                metric.reasonCode());
        assertThrows(
                IllegalStateException.class,
                () -> escaped[0].registerNatives(
                        "app/Late",
                        List.of(new ObservedNativeBinding(
                                "late",
                                "()V",
                                "0x140099999"))));
    }

    @Test
    void capturedBindingsDoNotPassWhenJniOnLoadFailsOrReturnsWrongVersion() {
        RegistrationCaptureMetric failed = probe.observe(
                List.of("JNI_OnLoad"),
                observer -> {
                    fixtureJniOnLoad(new FakeJniEnv(observer));
                    return -1;
                });
        RegistrationCaptureMetric wrongVersion = probe.observe(
                List.of("JNI_OnLoad"),
                observer -> {
                    fixtureJniOnLoad(new FakeJniEnv(observer));
                    return 0x00010006;
                });

        assertFalse(failed.passed());
        assertTrue(failed.mappingAvailableOnlyAfterJniOnLoadObservation());
        assertEquals(
                FakeJniRegistrationProbe.JNI_ONLOAD_FAILED,
                failed.reasonCode());
        assertFalse(wrongVersion.passed());
        assertEquals(
                FakeJniRegistrationProbe.JNI_ONLOAD_UNEXPECTED_VERSION,
                wrongVersion.reasonCode());
    }

    private int fixtureJniOnLoad(FakeJniEnv env) {
        FakeJniClass first = env.findClass("app/First");
        FakeJniClass second = env.findClass("app/Second");
        env.registerNatives(
                first,
                List.of(
                        new ObservedNativeBinding(
                                "authenticate",
                                "(Ljava/lang/String;)Z",
                                "0x140010000"),
                        new ObservedNativeBinding(
                                "digest",
                                "([B)[B",
                                "0x140011000")));
        env.registerNatives(
                second,
                List.of(new ObservedNativeBinding(
                        "run",
                        "()V",
                        "0x140012000")));
        return JNI_VERSION_1_8;
    }

    private record FakeJniClass(String ownerInternalName) {}

    private static final class FakeJavaVm {
        private FakeJniEnv attach(FakeJniRegistrationObserver observer) {
            return new FakeJniEnv(observer);
        }
    }

    private static final class FakeJniEnv {
        private final FakeJniRegistrationObserver observer;

        private FakeJniEnv(FakeJniRegistrationObserver observer) {
            this.observer = observer;
        }

        private FakeJniClass findClass(String ownerInternalName) {
            return new FakeJniClass(ownerInternalName);
        }

        private void registerNatives(
                FakeJniClass owner,
                List<ObservedNativeBinding> bindings) {
            observer.registerNatives(owner.ownerInternalName(), bindings);
        }
    }
}
