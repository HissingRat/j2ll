package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.runtime.jni.JniPendingExceptionPolicy;

class JniPackagingPlannerTest {
    @Test
    void emitsStableRegisterNativesTableGolden() {
        NativeRegistrationPlan plan = new NativeRegistrationPlan(List.of(
                new NativeRegistrationEntry("pkg/Foo", "run", "()V", "j2ll_pkg_Foo_run"),
                new NativeRegistrationEntry("pkg/Foo", "__j2ll_init_body$abc", "(I)V", "j2ll_pkg_Foo_init")));

        String table = new RegisterNativesTableBuilder().emit(plan);

        assertEquals("""
                static JNINativeMethod j2ll_natives_pkg_Foo[] = {
                    {"__j2ll_init_body$abc", "(I)V", (void*)j2ll_pkg_Foo_init},
                    {"run", "()V", (void*)j2ll_pkg_Foo_run},
                };
                static const int j2ll_natives_pkg_Foo_count = 2;

                """, table);
    }

    @Test
    void plansJniOnLoadAndBootstrapWrapperSymbols() {
        NativeRegistrationPlan plan = new NativeRegistrationPlan(List.of(
                new NativeRegistrationEntry("pkg/Foo", "run", "()V", "j2ll_pkg_Foo_run")));

        JniOnLoadPlan onLoad = new JniOnLoadPlanner().plan(plan);

        assertEquals("JNI_OnLoad", onLoad.onLoadSymbol());
        assertEquals("j2ll_register", onLoad.aggregateRegisterSymbol());
        assertEquals("JNI_VERSION_1_8", onLoad.minimumJniVersion());
        assertEquals(JniPendingExceptionPolicy.PROPAGATE_TO_JVM, onLoad.pendingExceptionPolicy());
        assertEquals(1, onLoad.bootstrapWrappers().size());
        BootstrapWrapperPlan wrapper = onLoad.bootstrapWrappers().get(0);
        assertEquals("pkg/Foo", wrapper.owner());
        assertEquals("j2ll_bootstrap_pkg_Foo", wrapper.wrapperSymbol());
        assertEquals("j2ll_register_pkg_Foo", wrapper.registerSymbol());
        assertTrue(wrapper.loaderClassInternalName().contains("NativeLoader"));
    }
}
