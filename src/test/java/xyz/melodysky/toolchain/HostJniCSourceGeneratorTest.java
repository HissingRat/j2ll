package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.ir.model.BusinessStringConstantRef;
import xyz.melodysky.ir.model.BusinessStringSymbolMapper;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.progress.NativePreparationProgress;
import xyz.melodysky.progress.NativePreparationStep;
import xyz.melodysky.runtime.jni.JniTypeMapper;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.toolchain.nativetext.GeneratedNativeHardeningAudit;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

class HostJniCSourceGeneratorTest {
    @Test
    void buildGenerationReportsSourceAndHardeningAsSeparateRealWork() {
        ParsedClass parsedClass = parse(
                "pkg/Progress.class",
                AsmFixtureBuilder.classWithStaticFieldRead("pkg/Progress"));
        MethodRewriteDecision decision = decision(parsedClass, "getValue");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));
        NativeImplementationPlan implementationPlan =
                xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                        registrationPlan,
                        List.of(decision),
                        Map.of(
                                decision.method().methodKey(),
                                irMethod(parsedClass, "getValue")));
        ArrayList<NativePreparationProgress> events = new ArrayList<>();
        NativeBuildProgressListener progress = new NativeBuildProgressListener() {
            @Override
            public void targetCompleted(
                    TargetTriple target,
                    int completedTargets,
                    int totalTargets) {
            }

            @Override
            public void preparationProgress(NativePreparationProgress progress) {
                events.add(progress);
            }
        };
        NativeTextBuildKey key = NativeTextBuildKey.fromUtf8(
                "native-generation-progress-test");

        String source = new HostJniCSourceGenerator().generate(
                implementationPlan,
                RuntimeLoaderPlan.create("native0", 0),
                MethodTableHidingPlan.disabled(),
                key,
                key,
                key,
                RuntimeHelperReachabilityPlan.conservative(),
                progress);

        assertFalse(source.isBlank());
        List<NativePreparationProgress> generation = events.stream()
                .filter(event -> event.step()
                        == NativePreparationStep.GENERATE_NATIVE_C)
                .toList();
        assertEquals(List.of(0L, 1L), generation.stream()
                .map(NativePreparationProgress::completed)
                .toList());
        assertTrue(generation.stream().allMatch(event -> event.total() == 1L));
        List<NativePreparationProgress> audit = events.stream()
                .filter(event -> event.step()
                        == NativePreparationStep.AUDIT_NATIVE_C)
                .toList();
        assertFalse(audit.isEmpty());
        assertEquals(0L, audit.getFirst().completed());
        assertEquals(audit.getFirst().total(), audit.getLast().completed());
        assertEquals(audit.getFirst().total(), audit.getLast().total());
        assertTrue(audit.stream().allMatch(event ->
                event.total() == audit.getFirst().total()));
    }

    @Test
    void runtimeHelperFamiliesKeepDependencyOrder() {
        ParsedClass parsedClass = parse(
                "pkg/StaticFields.class",
                AsmFixtureBuilder.classWithStaticFieldRead("pkg/StaticFields"));
        MethodRewriteDecision decision = decision(parsedClass, "getValue");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));
        NativeImplementationPlan implementationPlan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod(parsedClass, "getValue")));

        String source = xyz.melodysky.testsupport.TestProtectionMaterials
                .hostJniSource(implementationPlan);
        List<String> orderedMarkers = List.of(
                "static void j2ll_throw_new",
                "int32_t j2ll_rt_div_i32",
                "static jobject j2ll_call_static_box",
                "void j2ll_rt_monitor_enter",
                "int32_t j2ll_rt_array_length_i32",
                "int32_t j2ll_rt_string_length",
                "static jobject j2ll_var_handle_method_handle");

        for (int index = 1; index < orderedMarkers.size(); index++) {
            assertAppearsBefore(source, orderedMarkers.get(index - 1), orderedMarkers.get(index));
        }
        assertFalse(source.contains("j2ll_try_define_hidden_fallback"));
        assertFalse(source.contains("j2ll_verify_sha256_hex"));
        assertFalse(source.contains("defineHiddenFallback"));
        assertFalse(source.contains("j2ll_class_table"));
        assertFalse(source.contains("j2ll_method_table"));
        assertFalse(source.contains("j2ll_field_table"));
        assertFalse(source.contains("j2ll_reflection_method_table"));
        assertFalse(source.contains("j2ll_reflection_field_table"));
        assertFalse(source.contains("j2ll_lambda_table"));
    }

    @Test
    void genericReferenceComparisonsUseJniIdentityInsteadOfHandleAddresses() {
        ParsedClass parsedClass = parse(
                "pkg/ReferenceConstructor.class",
                AsmFixtureBuilder.classWithReferenceComparingConstructor(
                        "pkg/ReferenceConstructor"));
        MethodRewriteDecision decision = decision(parsedClass, "<init>");
        IrMethod method = irMethod(parsedClass, "<init>");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));
        NativeMethodImplementation implementation = new NativeMethodImplementation(
                registrationPlan.entries().getFirst(),
                decision,
                NativeImplementationPath.TEMPLATE_JNI_PATH,
                Optional.empty(),
                "TEST_GENERIC_REFERENCE_IDENTITY",
                true,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Optional.of(method));

        String source = xyz.melodysky.testsupport.TestProtectionMaterials
                .hostJniSource(
                        new NativeImplementationPlan(List.of(implementation)));

        assertEquals(
                2,
                countOccurrences(
                        source,
                        "(*env)->IsSameObject(env, arg1, arg2)"));
        assertFalse(source.contains("arg1 == arg2"));
        assertFalse(source.contains("arg1 != arg2"));
    }

    @Test
    void classLiteralOnlyWrappersPassFrozenJniEnvAbiThroughLocalBridge() {
        ParsedClass parsedClass = parse(
                "pkg/ClassLiteralOps.class",
                AsmFixtureBuilder
                        .classWithStaticAndInstanceClassLiteralMethods(
                                "pkg/ClassLiteralOps",
                                "java/lang/String"));
        MethodRewriteDecision staticLiteral =
                decision(parsedClass, "staticLiteral");
        MethodRewriteDecision instanceLiteral =
                decision(parsedClass, "instanceLiteral");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(
                        List.of(staticLiteral, instanceLiteral));
        NativeImplementationPlan implementationPlan =
                xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                        registrationPlan,
                        List.of(staticLiteral, instanceLiteral),
                        Map.of(
                                staticLiteral.method().methodKey(),
                                irMethod(parsedClass, "staticLiteral"),
                                instanceLiteral.method().methodKey(),
                                irMethod(parsedClass, "instanceLiteral")));

        String source = new HostJniCSourceGenerator().generate(
                implementationPlan,
                RuntimeLoaderPlan.create("native0", 0),
                false,
                0L,
                NativeTextBuildKey.fromUtf8(
                        "class-literal-abi-source-test"));
        NativeMethodImplementation staticImplementation =
                implementationPlan
                        .implementationFor(
                                staticLiteral.method().methodKey())
                        .orElseThrow();
        NativeMethodImplementation instanceImplementation =
                implementationPlan
                        .implementationFor(
                                instanceLiteral.method().methodKey())
                        .orElseThrow();
        String staticSymbol =
                staticImplementation.llvmFunctionSymbol().orElseThrow();
        String instanceSymbol =
                instanceImplementation.llvmFunctionSymbol().orElseThrow();

        assertTrue(source.contains(
                "extern jobject "
                        + staticSymbol
                        + "(JNIEnv* env);"));
        assertTrue(source.contains(
                "extern jobject "
                        + instanceSymbol
                        + "(JNIEnv* env, jobject self);"));
        assertTrue(source.contains(
                "return " + staticSymbol + "(env);"));
        assertTrue(source.contains(
                "return "
                        + instanceSymbol
                        + "(env, self);"));
        assertFalse(source.contains(staticSymbol + "(void)"));
        assertFalse(source.contains(instanceSymbol + "(jobject self)"));
        assertTrue(source.contains(
                "__attribute__((noinline, used))"));
        assertTrue(source.contains("volatile uintptr_t"));
        assertTrue(source.contains(" ? j2ll_lab_bridge_"));
        assertFalse(source.contains("optnone"));
    }

    @Test
    void registrationRuntimeDoesNotContainABytecodeDefinitionPath() {
        String source = HostJniRegistrationRuntimeSource.helperSource();

        assertTrue(source.contains("j2ll_class_for_registration"));
        assertFalse(source.contains("DefineClass"));
        assertFalse(source.contains("defineHiddenFallback"));
        assertFalse(source.contains("MethodHandles"));
        assertFalse(source.contains("fallback"));
    }

    @Test
    void fieldHelperSourceUsesJniHandlesAndTokensOnly() {
        ParsedClass staticClass = parse("pkg/StaticFields.class", AsmFixtureBuilder.classWithStaticFieldRead("pkg/StaticFields"));
        ParsedClass instanceClass = parse("pkg/Fields.class", AsmFixtureBuilder.classWithInstanceFieldRead("pkg/Fields"));
        MethodRewriteDecision staticDecision = decision(staticClass, "getValue");
        MethodRewriteDecision instanceDecision = decision(instanceClass, "read");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(staticDecision, instanceDecision));
        NativeImplementationPlan implementationPlan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(staticDecision, instanceDecision),
                Map.of(
                        staticDecision.method().methodKey(), irMethod(staticClass, "getValue"),
                        instanceDecision.method().methodKey(), irMethod(instanceClass, "read")));

        String source = xyz.melodysky.testsupport.TestProtectionMaterials
                .hostJniSource(implementationPlan);
        NativeMethodImplementation staticImplementation = implementationPlan
                .implementationFor(staticDecision.method().methodKey())
                .orElseThrow();
        NativeMethodImplementation instanceImplementation = implementationPlan
                .implementationFor(instanceDecision.method().methodKey())
                .orElseThrow();

        assertFalse(source.contains("j2ll_field_table"));
        assertTrue(source.contains("GetStaticFieldID"));
        assertTrue(source.contains("GetStaticIntField"));
        assertFalse(source.contains("GetObjectClass(env, self)"));
        assertTrue(source.contains("GetFieldID"));
        assertTrue(source.contains("GetIntField"));
        assertTrue(source.contains("j2ll_rt_div_i32"));
        assertFalse(source.contains("java/lang/ArithmeticException"));
        assertFalse(source.contains("\"/ by zero\""));
        assertTrue(source.contains("j2ll_rt_array_load_i32"));
        assertTrue(source.contains("GetIntArrayRegion"));
        assertTrue(source.contains("SetIntArrayRegion"));
        assertFalse(source.contains("ArrayIndexOutOfBoundsException"));
        assertFalse(source.contains("j2ll_decode_metadata_strings"));
        assertFalse(source.contains("j2ll_encoded_metadata_strings"));
        assertTrue(source.contains("void j2ll_rt_monitor_enter(JNIEnv* env, jobject monitor)"));
        assertTrue(source.contains("(*env)->MonitorEnter(env, monitor)"));
        assertTrue(source.contains("void j2ll_rt_monitor_exit(JNIEnv* env, jobject monitor)"));
        assertTrue(source.contains("(*env)->MonitorExit(env, monitor)"));
        assertTrue(source.contains("void j2ll_rt_throw(JNIEnv* env, jobject throwable)"));
        assertTrue(source.contains("(*env)->Throw(env, (jthrowable)throwable)"));
        assertTrue(source.contains("extern jint "
                + staticImplementation.llvmFunctionSymbol().orElseThrow()
                + "(JNIEnv* env, jclass owner);"));
        assertTrue(source.contains("return "
                + staticImplementation.llvmFunctionSymbol().orElseThrow()
                + "(env, owner);"));
        assertFalse(source.contains("jint result = (jint)"
                + staticImplementation.llvmFunctionSymbol().orElseThrow()));
        assertTrue(source.contains("extern jint "
                + instanceImplementation.llvmFunctionSymbol().orElseThrow()
                + "(JNIEnv* env, jobject self);"));
        assertTrue(source.contains("return "
                + instanceImplementation.llvmFunctionSymbol().orElseThrow()
                + "(env, self);"));
        assertFalse(source.contains("jint result = (jint)"
                + instanceImplementation.llvmFunctionSymbol().orElseThrow()));
        assertFalse(source.contains("j2ll_lab_slot_"));
        assertFalse(source.contains(" volatile j2ll_lab_"));
        assertTrue(source.contains("return result;"));
        assertFalse(source.contains("j2ll_get_field_pkg_Fields_value_I"));
        assertFalse(source.contains("j2ll_get_static_pkg_StaticFields_VALUE_I"));
        assertFalse(source.contains("self->"));
        assertFalse(source.contains("offsetof("));
    }

    @Test
    void instanceLlvmOwnerOperandUsesDefiningClassInsteadOfReceiverRuntimeClass() {
        ParsedClass parsedClass = parse(
                "pkg/Base.class",
                AsmFixtureBuilder.classWithInstanceFieldRead("pkg/Base"));
        MethodRewriteDecision decision = decision(parsedClass, "read");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));
        NativeMethodImplementation planned =
                xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                                registrationPlan,
                                List.of(decision),
                                Map.of(
                                        decision.method().methodKey(),
                                        irMethod(parsedClass, "read")))
                        .implementations()
                        .get(0);
        NativeMethodImplementation withDefiningOwner =
                new NativeMethodImplementation(
                        planned.entry(),
                        planned.decision(),
                        planned.path(),
                        planned.llvmFunctionSymbol(),
                        planned.reasonCode(),
                        true,
                        true,
                        planned.fieldKeys(),
                        planned.directCallTargets(),
                        planned.allocationKeys(),
                        planned.typeCheckKeys(),
                        planned.classObjectKeys(),
                        planned.runtimeMetadataKeys(),
                        planned.constructorCallKeys(),
                        planned.staticCallKeys(),
                        planned.dispatchKeys(),
                        planned.stringHelperSymbols(),
                        planned.templateIrMethod(),
                        planned.initializerPlan());

        String source = new HostJniCSourceGenerator().generate(
                new NativeImplementationPlan(List.of(withDefiningOwner)),
                RuntimeLoaderPlan.create("native0", 0),
                false,
                0L,
                NativeTextBuildKey.fromUtf8("defining-owner-test"));

        assertFalse(source.contains("GetObjectClass(env, self)"));
        assertTrue(source.contains("(*env)->FindClass(env, "));
        assertFalse(source.contains("\"pkg/Base\""));
        assertTrue(source.contains("(*env)->DeleteLocalRef(env, owner)"));
    }

    @Test
    void generatedTextUsesIndependentScopesAndOneBuildKey() {
        ParsedClass parsedClass = parse(
                "pkg/StaticFields.class",
                AsmFixtureBuilder.classWithStaticFieldRead(
                        "pkg/StaticFields"));
        MethodRewriteDecision decision = decision(parsedClass, "getValue");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));
        NativeImplementationPlan implementationPlan =
                xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                        registrationPlan,
                        List.of(decision),
                        Map.of(
                                decision.method().methodKey(),
                                irMethod(parsedClass, "getValue")));
        HostJniCSourceGenerator generator = new HostJniCSourceGenerator();
        RuntimeLoaderPlan loaderPlan = RuntimeLoaderPlan.create("native0", 0);

        String first = generator.generate(
                implementationPlan,
                loaderPlan,
                false,
                0L,
                NativeTextBuildKey.fromUtf8("host-jni-build-one"));
        String second = generator.generate(
                implementationPlan,
                loaderPlan,
                false,
                0L,
                NativeTextBuildKey.fromUtf8("host-jni-build-two"));

        long scratchDefinitions = Pattern.compile(
                        "j2ll_nt_local_[A-Za-z0-9_]+")
                .matcher(first)
                .results()
                .count();
        assertTrue(
                scratchDefinitions >= 5,
                () -> "expected multiple call-local text scopes, found "
                        + scratchDefinitions);
        assertFalse(first.contains("j2ll_encoded_metadata_strings"));
        assertFalse(first.contains("j2ll_decode_metadata_strings"));
        var audit = new GeneratedNativeHardeningAudit().audit(first);
        assertTrue(audit.passed());
        assertTrue(audit.evidence().contains(
                GeneratedNativeHardeningAudit
                        .EVIDENCE_CALL_LOCAL_TEXT_CLEANUP));
        assertTrue(audit.evidence().contains(
                GeneratedNativeHardeningAudit
                        .EVIDENCE_AFFINE_CIPHERTEXT_STORAGE));
        assertNotEquals(first, second);
    }

    @Test
    void businessAndRegistrationTextKeysAffectOnlyTheirOwnedPlans() {
        ParsedClass parsedClass = parse(
                "pkg/StringValues.class",
                AsmFixtureBuilder.classWithSymbolicLdcMethods(
                        "pkg/StringValues"));
        MethodRewriteDecision decision =
                decision(parsedClass, "stringConst");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));
        IrMethod method = irMethod(parsedClass, "stringConst");
        Map<String, IrMethod> methods =
                Map.of(decision.method().methodKey(), method);
        NativeTextBuildKey generalKey =
                NativeTextBuildKey.fromUtf8("runtime-domain");
        NativeTextBuildKey businessA =
                NativeTextBuildKey.fromUtf8("business-domain-a");
        NativeTextBuildKey businessB =
                NativeTextBuildKey.fromUtf8("business-domain-b");
        NativeTextBuildKey registrationA =
                NativeTextBuildKey.fromUtf8("registration-domain-a");
        NativeTextBuildKey registrationB =
                NativeTextBuildKey.fromUtf8("registration-domain-b");
        NativeImplementationPlan planA = new NativeImplementationPlanner(
                new LlvmNameMangler(),
                BusinessStringSymbolMapper.fromBytes(businessA.bytes()),
                xyz.melodysky.runtime.RuntimeTokenMapper.fromBytes(
                        generalKey.bytes()))
                .plan(registrationPlan, List.of(decision), methods);
        NativeImplementationPlan planB = new NativeImplementationPlanner(
                new LlvmNameMangler(),
                BusinessStringSymbolMapper.fromBytes(businessB.bytes()),
                xyz.melodysky.runtime.RuntimeTokenMapper.fromBytes(
                        generalKey.bytes()))
                .plan(registrationPlan, List.of(decision), methods);
        MethodTableHidingPlan methodTablePlan =
                new MethodTableHidingPlanner().plan(
                        registrationPlan,
                        false,
                        17L);
        RuntimeLoaderPlan loaderPlan = RuntimeLoaderPlan.create("native0", 0);
        HostJniCSourceGenerator generator = new HostJniCSourceGenerator();

        String first = generator.generate(
                planA,
                loaderPlan,
                methodTablePlan,
                generalKey,
                businessA,
                registrationA);
        String businessChanged = generator.generate(
                planB,
                loaderPlan,
                methodTablePlan,
                generalKey,
                businessB,
                registrationA);
        String registrationChanged = generator.generate(
                planA,
                loaderPlan,
                methodTablePlan,
                generalKey,
                businessA,
                registrationB);
        String helperA = BusinessStringConstantRef.of("secret-value")
                .helperSymbol(BusinessStringSymbolMapper.fromBytes(
                        businessA.bytes()));
        String helperB = BusinessStringConstantRef.of("secret-value")
                .helperSymbol(BusinessStringSymbolMapper.fromBytes(
                        businessB.bytes()));
        String registrationSourceA =
                new HostNativeRegistrationSource().emit(
                        registrationPlan,
                        methodTablePlan,
                        registrationA);
        String registrationSourceB =
                new HostNativeRegistrationSource().emit(
                        registrationPlan,
                        methodTablePlan,
                        registrationB);

        assertNotEquals(helperA, helperB);
        assertTrue(first.contains("jobject " + helperA + "(JNIEnv* env)"));
        assertFalse(first.contains("jobject " + helperB + "(JNIEnv* env)"));
        assertTrue(businessChanged.contains(
                "jobject " + helperB + "(JNIEnv* env)"));
        assertTrue(first.endsWith(registrationSourceA));
        assertTrue(businessChanged.endsWith(registrationSourceA));
        assertTrue(registrationChanged.contains(
                "jobject " + helperA + "(JNIEnv* env)"));
        assertTrue(registrationChanged.endsWith(registrationSourceB));
        assertNotEquals(first, businessChanged);
        assertNotEquals(first, registrationChanged);
    }

    @Test
    void finalGeneratedSourceBoundaryRejectsBulkRecoveryStructures() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> HostJniCSourceGenerator.requireHardenedGeneratedSource("""
                        static unsigned char j2ll_encoded_metadata_strings[] = {
                            0x01
                        };
                        JNIEXPORT jint JNICALL j2ll_register(JavaVM* vm) {
                            return JNI_VERSION_1_8;
                        }
                        """));

        assertTrue(failure.getMessage().contains(
                "LEGACY_GLOBAL_METADATA_DIRECTORY"));
        assertTrue(failure.getMessage().contains(
                "EXPORTED_AGGREGATE_REGISTRATION"));
        assertFalse(failure.getMessage().contains("0x01"));
    }

    @Test
    void arrayComponentReferenceAllocationUsesDescriptorComponentToken() {
        ParsedClass parsedClass = parse(
                "pkg/ByteMatrices.class",
                AsmFixtureBuilder.classWithReferenceArrayAllocation(
                        "pkg/ByteMatrices",
                        "[B"));
        MethodRewriteDecision decision = decision(parsedClass, "array");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));
        NativeImplementationPlan implementationPlan =
                xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                        registrationPlan,
                        List.of(decision),
                        Map.of(
                                decision.method().methodKey(),
                                irMethod(parsedClass, "array")));
        NativeMethodImplementation implementation =
                implementationPlan.implementationFor(decision.method().methodKey()).orElseThrow();
        StringBuilder source = new StringBuilder();

        HostJniAllocationRuntimeSource.append(
                source,
                List.of(binding(implementation)),
                xyz.melodysky.testsupport.TestProtectionMaterials
                        .runtimeTokens());

        assertTrue(source.toString().contains("\"[B\""));
        assertTrue(source.toString().contains(
                "(*env)->FindClass(env, \"[B\")"));
        assertTrue(source.toString().contains(
                "(*env)->NewObjectArray(env, (jsize)length, component, NULL)"));
        assertFalse(source.toString().contains("j2ll_class_table"));
        assertFalse(source.toString().contains("component_token"));
    }

    private ParsedClass parse(String entry, byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry(entry, bytes, "fixture"))
                .artifact()
                .orElseThrow();
    }

    private MethodRewriteDecision decision(ParsedClass parsedClass, String name) {
        return new MethodRewritePlanner().planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(item -> item.method().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private IrMethod irMethod(ParsedClass parsedClass, String name) {
        ParsedMethod method = parsedClass.methods().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
        return xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                .lower(new MethodCfgBuilder().build(method).artifact().orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
    }

    private HostJniCSourceGenerator.Binding binding(
            NativeMethodImplementation implementation) {
        MethodRewriteDecision decision = implementation.decision();
        return new HostJniCSourceGenerator.Binding(
                implementation.entry(),
                decision,
                implementation.path(),
                implementation.llvmFunctionSymbol(),
                implementation.passesJniEnv(),
                implementation.passesOwnerClass(),
                implementation.fieldKeys(),
                implementation.directCallTargets(),
                implementation.allocationKeys(),
                implementation.typeCheckKeys(),
                implementation.classObjectKeys(),
                implementation.runtimeMetadataKeys(),
                implementation.constructorCallKeys(),
                implementation.staticCallKeys(),
                implementation.dispatchKeys(),
                implementation.stringHelperSymbols(),
                implementation.templateIrMethod(),
                implementation.reasonCode(),
                new JniTypeMapper().methodDescriptor(
                        decision.method().owner(),
                        decision.method().name(),
                        decision.method().descriptor(),
                        decision.method().accessFlags().isStatic()));
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private void assertAppearsBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue(firstIndex >= 0, () -> "missing generated C marker: " + first);
        assertTrue(secondIndex >= 0, () -> "missing generated C marker: " + second);
        assertTrue(firstIndex < secondIndex, () -> first + " must appear before " + second);
    }
}
