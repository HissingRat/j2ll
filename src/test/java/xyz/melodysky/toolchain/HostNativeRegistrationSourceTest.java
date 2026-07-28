package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class HostNativeRegistrationSourceTest {
    private final NativeRegistrationPlan registrations = new NativeRegistrationPlan(List.of(
            new NativeRegistrationEntry("sample/Owner", "first", "(I)I", "j2ll_fn_a"),
            new NativeRegistrationEntry("sample/Owner", "second", "(J)J", "j2ll_fn_b")));

    @Test
    void hiddenModeBuildsOnlyATransientStraightLineOwnerTable() {
        String source = new HostNativeRegistrationSource().emit(
                registrations,
                new MethodTableHidingPlanner().plan(registrations, true, 77L));

        assertFalse(source.contains("static const uint64_t j2ll_hmt_"));
        assertFalse(source.contains("j2ll_hidden_method_function"));
        assertFalse(source.contains("masked_token"));
        assertFalse(source.contains("metadata_index"));
        assertFalse(source.contains("function_index"));
        assertFalse(source.contains("join_scratch"));
        assertTrue(source.contains("RegisterNatives(env, owner_class, methods, count)"));
        assertTrue(source.contains("methods[0].fnPtr = (void*)"));
        assertTrue(source.contains("methods[1].fnPtr = (void*)"));
        assertTrue(source.contains("goto cleanup;"));
        assertTrue(source.contains(
                "j2ll_native_text_zero(methods, (size_t)count * sizeof(JNINativeMethod));"));
        assertTrue(source.contains("j2ll_native_text_zero(text_scratch, UINT64_C("));
        assertFalse(source.contains("static JNINativeMethod j2ll_natives_"));
        assertRegistrationPlaintextAbsent(source);
    }

    @Test
    void methodTablePlanDiversifiesTheStraightLinePhysicalBindingOrder() {
        NativeRegistrationPlan multiMethod = new NativeRegistrationPlan(List.of(
                new NativeRegistrationEntry("sample/Owner", "alpha", "()V", "j2ll_fn_alpha"),
                new NativeRegistrationEntry("sample/Owner", "beta", "(I)V", "j2ll_fn_beta"),
                new NativeRegistrationEntry("sample/Owner", "gamma", "(J)V", "j2ll_fn_gamma"),
                new NativeRegistrationEntry("sample/Owner", "delta", "(D)V", "j2ll_fn_delta")));
        HostNativeRegistrationSource emitter = new HostNativeRegistrationSource();
        NativeTextBuildKey registrationKey =
                NativeTextBuildKey.fromUtf8("fixed-registration-text-key");

        long distinctOrders = IntStream.range(0, 24)
                .mapToObj(index -> ordinaryFunctionOrder(emitter.emit(
                        multiMethod,
                        new MethodTableHidingPlanner().plan(
                                multiMethod,
                                true,
                                index),
                        registrationKey)))
                .distinct()
                .count();

        assertTrue(distinctOrders > 1);
    }

    @Test
    void disabledModeAlsoBuildsAndScrubsAnOwnerLocalTemporaryTable() {
        String source = new HostNativeRegistrationSource().emit(
                registrations,
                new MethodTableHidingPlanner().plan(registrations, false, 77L));

        assertFalse(source.contains("static JNINativeMethod j2ll_natives_"));
        assertFalse(source.contains("j2ll_hidden_method_metadata"));
        assertFalse(source.contains("j2ll_hidden_method_function"));
        assertTrue(source.contains("JNINativeMethod methods_storage[2] = {{0}};"));
        assertTrue(source.contains("unsigned char text_scratch_storage["));
        assertTrue(source.contains("JNINativeMethod* methods = methods_storage;"));
        assertFalse(source.contains(
                "methods = (JNINativeMethod*)calloc"));
        assertFalse(source.contains(
                "text_scratch = (unsigned char*)calloc"));
        assertTrue(source.contains("].fnPtr = (void*)j2ll_fn_a;"));
        assertTrue(source.contains("].fnPtr = (void*)j2ll_fn_b;"));
        assertTrue(source.contains("goto cleanup;"));
        assertTrue(source.contains("j2ll_native_text_zero(owner_text, sizeof(owner_text));"));
        int ownerLookup = source.indexOf(
                "owner_class = j2ll_class_for_registration(env, owner_text);");
        int immediateOwnerClear = source.indexOf(
                "j2ll_native_text_zero(owner_text, sizeof(owner_text));",
                ownerLookup);
        int methodDecode = source.indexOf(
                "methods[",
                immediateOwnerClear);
        assertTrue(ownerLookup >= 0);
        assertTrue(immediateOwnerClear > ownerLookup);
        assertTrue(methodDecode > immediateOwnerClear);
        assertRegistrationPlaintextAbsent(source);
    }

    @Test
    void repeatedOwnerLocalNamesAndDescriptorsShareOnlyDecodedScratchSlots() {
        NativeRegistrationPlan repeatedText =
                new NativeRegistrationPlan(List.of(
                        new NativeRegistrationEntry(
                                "sample/Owner",
                                "alpha",
                                "()V",
                                "j2ll_fn_alpha"),
                        new NativeRegistrationEntry(
                                "sample/Owner",
                                "beta",
                                "()V",
                                "j2ll_fn_beta"),
                        new NativeRegistrationEntry(
                                "sample/Owner",
                                "gamma",
                                "(I)V",
                                "j2ll_fn_gamma")));

        String source = new HostNativeRegistrationSource().emit(
                repeatedText,
                new MethodTableHidingPlanner().plan(
                        repeatedText,
                        false,
                        77L),
                NativeTextBuildKey.fromUtf8(
                        "owner-local-registration-text-dedup"));

        assertEquals(10, occurrences(source, "_cipher[] = {"));
        java.util.regex.Matcher signatures = java.util.regex.Pattern
                .compile("\\.signature = \\(char\\*\\)"
                        + "\\(text_scratch \\+ ([0-9]+)\\)")
                .matcher(source);
        java.util.ArrayList<String> offsets =
                new java.util.ArrayList<>();
        while (signatures.find()) {
            offsets.add(signatures.group(1));
        }
        assertEquals(3, offsets.size());
        assertEquals(2, offsets.stream().distinct().count());
        assertRegistrationPlaintextAbsent(source);
    }

    @Test
    void equalTextInDifferentOwnersKeepsDistinctSiteBoundEncodings() {
        NativeRegistrationPlan repeatedAcrossOwners =
                new NativeRegistrationPlan(List.of(
                        new NativeRegistrationEntry(
                                "sample/FirstOwner",
                                "run",
                                "()V",
                                "j2ll_fn_first"),
                        new NativeRegistrationEntry(
                                "sample/SecondOwner",
                                "run",
                                "()V",
                                "j2ll_fn_second")));

        List<NativeRegistrationTextPlan.Owner> owners =
                NativeRegistrationTextPlan.ordinary(
                        repeatedAcrossOwners,
                        NativeTextBuildKey.fromUtf8(
                                "cross-owner-registration-text"));

        assertEquals(2, owners.size());
        assertNotEquals(
                owners.get(0).bindings().get(0).nameText().symbol(),
                owners.get(1).bindings().get(0).nameText().symbol());
        assertNotEquals(
                owners.get(0).bindings().get(0)
                        .descriptorText().symbol(),
                owners.get(1).bindings().get(0)
                        .descriptorText().symbol());
    }

    @Test
    void registrationStackStorageIsBoundedByCountAndTextBytes() {
        assertTrue(NativeRegistrationStoragePlan.plan(
                NativeRegistrationStoragePlan.MAX_STACK_BINDINGS,
                NativeRegistrationStoragePlan.MAX_STACK_TEXT_BYTES)
                .usesStack());
        assertFalse(NativeRegistrationStoragePlan.plan(
                NativeRegistrationStoragePlan.MAX_STACK_BINDINGS + 1,
                NativeRegistrationStoragePlan.MAX_STACK_TEXT_BYTES)
                .usesStack());
        assertFalse(NativeRegistrationStoragePlan.plan(
                NativeRegistrationStoragePlan.MAX_STACK_BINDINGS,
                NativeRegistrationStoragePlan.MAX_STACK_TEXT_BYTES + 1)
                .usesStack());
    }

    @Test
    void oversizedOwnerUsesHeapInsteadOfGrowingNativeStackWithoutBound() {
        NativeRegistrationPlan largePlan = new NativeRegistrationPlan(
                IntStream.range(
                                0,
                                NativeRegistrationStoragePlan.MAX_STACK_BINDINGS + 1)
                        .mapToObj(index -> new NativeRegistrationEntry(
                                "sample/LargeOwner",
                                "method" + index,
                                "()V",
                                "j2ll_fn_" + index))
                        .toList());

        String source = new HostNativeRegistrationSource().emit(
                largePlan,
                new MethodTableHidingPlanner().plan(
                        largePlan,
                        false,
                        77L));

        assertTrue(source.contains(
                "methods = (JNINativeMethod*)calloc"));
        assertTrue(source.contains(
                "text_scratch = (unsigned char*)calloc"));
        assertTrue(source.contains("free(text_scratch);"));
        assertTrue(source.contains("free(methods);"));
        assertFalse(source.contains("methods_storage["));
    }

    @Test
    void failedOwnerRegistrationRollsBackPartialBindingsBeforeReturning() {
        String source = new HostNativeRegistrationSource().emit(
                registrations,
                new MethodTableHidingPlanner().plan(
                        registrations,
                        false,
                        77L),
                NativeTextBuildKey.fromUtf8(
                        "owner-partial-registration-rollback"));

        int register = source.indexOf(
                "register_status = (*env)->RegisterNatives");
        int capture = source.indexOf(
                "registration_exception = (*env)->ExceptionOccurred",
                register);
        int unregister = source.indexOf(
                "unregister_status = (*env)->UnregisterNatives(env, owner_class)",
                register);
        int cleanup = source.indexOf("cleanup:", register);
        int restore = source.indexOf(
                "throw_status = (*env)->Throw(env, registration_exception)",
                cleanup);
        assertTrue(register >= 0);
        assertTrue(capture > register);
        assertTrue(unregister > capture);
        assertTrue(cleanup > unregister);
        assertTrue(restore > cleanup);
        assertTrue(source.contains(
                "if (unregister_status != JNI_OK)"));
        assertTrue(source.contains(
                "rollback_exception = (*env)->ExceptionOccurred(env);"));
        assertTrue(source.contains(
                "(*env)->FatalError(env, rollback_failure_text)"));
        assertTrue(source.contains(
                "(*env)->FatalError(env, exception_restore_failure_text)"));
        assertTrue(source.contains(
                "j2ll_native_text_zero(rollback_failure_text, sizeof(rollback_failure_text))"));
        assertTrue(source.contains(
                "j2ll_native_text_zero(exception_restore_failure_text, sizeof(exception_restore_failure_text))"));
        assertFalse(source.contains("native owner registration rollback failed"));
        assertFalse(source.contains("native owner registration exception restore failed"));
    }

    @Test
    void explicitBuildKeyDiversifiesRegistrationCiphertextWhileOldOverloadIsStable() {
        var hiding = new MethodTableHidingPlanner().plan(registrations, true, 77L);
        HostNativeRegistrationSource emitter = new HostNativeRegistrationSource();

        String first = emitter.emit(
                registrations,
                hiding,
                NativeTextBuildKey.fromUtf8("registration-build-one"));
        String second = emitter.emit(
                registrations,
                hiding,
                NativeTextBuildKey.fromUtf8("registration-build-two"));

        assertNotEquals(first, second);
        assertEquals(
                emitter.emit(registrations, hiding),
                emitter.emit(registrations, hiding));
        assertRegistrationPlaintextAbsent(first);
        assertRegistrationPlaintextAbsent(second);
    }

    @Test
    void buildKeyDiversifiesPhysicalOwnerRegistrationOrder() {
        NativeRegistrationPlan multiOwner = new NativeRegistrationPlan(List.of(
                new NativeRegistrationEntry("sample/Alpha", "run", "()V", "j2ll_fn_alpha"),
                new NativeRegistrationEntry("sample/Beta", "run", "()V", "j2ll_fn_beta"),
                new NativeRegistrationEntry("sample/Gamma", "run", "()V", "j2ll_fn_gamma")));
        var hiding = new MethodTableHidingPlanner().plan(multiOwner, true, 77L);
        HostNativeRegistrationSource emitter = new HostNativeRegistrationSource();

        long distinctOrders = IntStream.range(0, 16)
                .mapToObj(index -> registrationCallOrder(emitter.emit(
                        multiOwner,
                        hiding,
                        NativeTextBuildKey.fromUtf8("owner-order-" + index))))
                .distinct()
                .count();

        assertTrue(distinctOrders > 1);
    }

    @Test
    void ordinaryModeDiversifiesOwnerLocalMethodOrderPerBuild() {
        NativeRegistrationPlan multiMethod = new NativeRegistrationPlan(List.of(
                new NativeRegistrationEntry("sample/Owner", "alpha", "()V", "j2ll_fn_alpha"),
                new NativeRegistrationEntry("sample/Owner", "beta", "(I)V", "j2ll_fn_beta"),
                new NativeRegistrationEntry("sample/Owner", "gamma", "(J)V", "j2ll_fn_gamma"),
                new NativeRegistrationEntry("sample/Owner", "delta", "(D)V", "j2ll_fn_delta")));
        HostNativeRegistrationSource emitter = new HostNativeRegistrationSource();
        var disabledHiding = new MethodTableHidingPlanner().plan(
                multiMethod,
                false,
                77L);

        String stableFirst = ordinaryFunctionOrder(emitter.emit(
                multiMethod,
                disabledHiding,
                NativeTextBuildKey.fromUtf8("ordinary-method-order-stable")));
        String stableSecond = ordinaryFunctionOrder(emitter.emit(
                multiMethod,
                disabledHiding,
                NativeTextBuildKey.fromUtf8("ordinary-method-order-stable")));
        long distinctOrders = IntStream.range(0, 24)
                .mapToObj(index -> ordinaryFunctionOrder(emitter.emit(
                        multiMethod,
                        disabledHiding,
                        NativeTextBuildKey.fromUtf8(
                                "ordinary-method-order-" + index))))
                .distinct()
                .count();

        assertEquals(stableFirst, stableSecond);
        assertTrue(distinctOrders > 1);
    }

    @Test
    void laterOwnerFailureRollsBackEarlierRegistrationsInReverseOrder() {
        NativeRegistrationPlan multiOwner = new NativeRegistrationPlan(List.of(
                new NativeRegistrationEntry(
                        "sample/Alpha",
                        "run",
                        "()V",
                        "j2ll_fn_alpha"),
                new NativeRegistrationEntry(
                        "sample/Beta",
                        "run",
                        "()V",
                        "j2ll_fn_beta"),
                new NativeRegistrationEntry(
                        "sample/Gamma",
                        "run",
                        "()V",
                        "j2ll_fn_gamma")));
        String source = new HostNativeRegistrationSource().emit(
                multiOwner,
                new MethodTableHidingPlanner().plan(
                        multiOwner,
                        true,
                        77L),
                NativeTextBuildKey.fromUtf8("atomic-owner-registration"));

        assertTrue(source.contains(
                "(JNIEnv* env, jclass* registered_owner)"));
        assertTrue(source.contains("*registered_owner = owner_class;"));
        assertTrue(source.contains("owner_class = NULL;"));
        assertTrue(source.contains(
                "(env, &registered_owner_0) != JNI_OK"));
        assertTrue(source.contains(
                "(env, &registered_owner_1) != JNI_OK"));
        assertTrue(source.contains(
                "(env, &registered_owner_2) != JNI_OK"));
        assertEquals(3, occurrences(source, "goto rollback;"));

        int rollback = source.indexOf("rollback:");
        int unregisterTwo = source.indexOf(
                "UnregisterNatives(env, registered_owner_2)",
                rollback);
        int unregisterOne = source.indexOf(
                "UnregisterNatives(env, registered_owner_1)",
                rollback);
        int unregisterZero = source.indexOf(
                "UnregisterNatives(env, registered_owner_0)",
                rollback);
        assertTrue(rollback >= 0);
        assertTrue(unregisterTwo > rollback);
        assertTrue(unregisterOne > unregisterTwo);
        assertTrue(unregisterZero > unregisterOne);
        assertTrue(source.contains(
                "failure_exception = (*env)->ExceptionOccurred(env);"));
        assertTrue(source.contains("(*env)->ExceptionClear(env);"));
        assertTrue(source.contains(
                "unregister_status = (*env)->UnregisterNatives"));
        assertTrue(source.contains(
                "if (unregister_status != JNI_OK)"));
        assertTrue(source.contains(
                "(*env)->FatalError(env, rollback_failure_text)"));
        assertTrue(source.contains(
                "j2ll_native_text_zero(rollback_failure_text, sizeof(rollback_failure_text))"));
        assertFalse(source.contains("native registration rollback failed"));
        assertTrue(source.contains(
                "throw_status = (*env)->Throw(env, failure_exception);"));
        assertTrue(source.contains(
                "if (throw_status != JNI_OK || !(*env)->ExceptionCheck(env))"));
        assertTrue(source.contains(
                "(*env)->FatalError(env, exception_restore_failure_text)"));
        assertTrue(source.contains(
                "j2ll_native_text_zero(exception_restore_failure_text, sizeof(exception_restore_failure_text))"));
        assertFalse(source.contains(
                "native registration exception restore failed"));
        assertEquals(
                1,
                occurrences(
                        source,
                        "JNIEXPORT jint JNICALL JNI_OnLoad"));
        assertFalse(source.contains(
                "JNIEXPORT jint JNICALL j2ll_register"));
        assertRegistrationPlaintextAbsent(source);
    }

    private String registrationCallOrder(String source) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("if \\(j2ll_register_(h_[0-9a-f]{32})"
                        + "\\(env, &registered_owner_[0-9]+\\)")
                .matcher(source);
        StringBuilder order = new StringBuilder();
        while (matcher.find()) {
            order.append(matcher.group(1)).append(';');
        }
        return order.toString();
    }

    private String ordinaryFunctionOrder(String source) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("methods\\[[0-9]+\\]\\.fnPtr = \\(void\\*\\)"
                        + "(j2ll_fn_[a-z]+);")
                .matcher(source);
        StringBuilder order = new StringBuilder();
        while (matcher.find()) {
            order.append(matcher.group(1)).append(';');
        }
        return order.toString();
    }

    private int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private void assertRegistrationPlaintextAbsent(String source) {
        assertFalse(source.contains("sample/Owner"));
        assertFalse(source.contains("\"first\""));
        assertFalse(source.contains("\"second\""));
        assertFalse(source.contains("\"(I)I\""));
        assertFalse(source.contains("\"(J)J\""));
        assertFalse(source.contains("native registration rollback failed"));
        assertFalse(source.contains("native registration exception restore failed"));
        assertFalse(source.contains("native owner registration rollback failed"));
        assertFalse(source.contains(
                "native owner registration exception restore failed"));
        assertFalse(source.contains("const char* name"));
        assertFalse(source.contains("const char* descriptor"));
    }
}
