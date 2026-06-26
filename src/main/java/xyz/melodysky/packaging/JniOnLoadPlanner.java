package xyz.melodysky.packaging;

import xyz.melodysky.runtime.jni.JniPendingExceptionPolicy;

public final class JniOnLoadPlanner {
    public JniOnLoadPlan plan(NativeRegistrationPlan registrationPlan) {
        return new JniOnLoadPlan(
                "JNI_OnLoad",
                "j2ll_register",
                "JNI_VERSION_1_8",
                JniPendingExceptionPolicy.PROPAGATE_TO_JVM,
                new BootstrapWrapperPlanner().plan(registrationPlan));
    }
}
