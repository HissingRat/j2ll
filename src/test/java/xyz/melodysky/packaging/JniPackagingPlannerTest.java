package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.runtime.jni.JniPendingExceptionPolicy;
import xyz.melodysky.toolchain.CIdentifier;

class JniPackagingPlannerTest {
    @Test
    void emitsStableRegisterNativesTableGolden() {
        NativeRegistrationPlan plan = new NativeRegistrationPlan(List.of(
                new NativeRegistrationEntry("pkg/Foo", "run", "()V", "j2ll_pkg_Foo_run"),
                new NativeRegistrationEntry(
                        "pkg/Foo",
                        "abcdefghijklmnopabcdefghijklmnop",
                        "(I)V",
                        "j2ll_pkg_Foo_init")));

        String table = new RegisterNativesTableBuilder().emit(plan);
        String ownerToken = CIdentifier.forIdentity("pkg/Foo");

        assertEquals(("""
                static JNINativeMethod j2ll_natives_%s[] = {
                    {"abcdefghijklmnopabcdefghijklmnop", "(I)V", (void*)j2ll_pkg_Foo_init},
                    {"run", "()V", (void*)j2ll_pkg_Foo_run},
                };
                static const int j2ll_natives_%s_count = 2;

                """).formatted(ownerToken, ownerToken), table);
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
        String ownerToken = CIdentifier.forIdentity("pkg/Foo");
        assertEquals("pkg/Foo", wrapper.owner());
        assertEquals("j2ll_bootstrap_" + ownerToken, wrapper.wrapperSymbol());
        assertEquals("j2ll_register_" + ownerToken, wrapper.registerSymbol());
        assertTrue(wrapper.wrapperSymbol().matches("j2ll_bootstrap_h_[0-9a-f]{32}"));
        assertTrue(wrapper.registerSymbol().matches("j2ll_register_h_[0-9a-f]{32}"));
    }
}
