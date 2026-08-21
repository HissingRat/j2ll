package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.pass.protection.MethodSplittingPass;
import xyz.melodysky.ir.pass.protection.MethodSplittingStatus;
import xyz.melodysky.ir.pass.protection.ProtectionConfig;
import xyz.melodysky.ir.pass.protection.ProtectionPipeline;
import xyz.melodysky.ir.pass.OptimizationPipeline;
import xyz.melodysky.ir.pass.PassContext;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.testsupport.ExceptionFlowAsmFixtures;
import xyz.melodysky.testsupport.InterfaceMethodAsmFixtures;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlanner;

class NativeImplementationPlannerTest implements Opcodes {
    @Test
    void selectsLlvmNativePathForPrimitiveScalarIr() {
        ParsedClass parsedClass = parse("pkg/Adder.class", AsmFixtureBuilder.classWithAddMethod("pkg/Adder"));
        MethodRewriteDecision decision = decision(parsedClass, "add");
        IrMethod irMethod = irMethod(parsedClass, "add");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        assertEquals(1, plan.implementations().size());
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, plan.implementations().get(0).path());
        assertEquals("LLVM_PRIMITIVE_SCALAR_IR", plan.implementations().get(0).reasonCode());
    }

    @Test
    void selectsLlvmNativePathForCodeBearingDefaultInterfaceMethod() {
        ParsedClass parsedClass = parse(
                "pkg/Api.class",
                AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Api"));
        MethodRewriteDecision decision = decision(parsedClass, "answer");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(
                        decision.method().methodKey(),
                        irMethod(parsedClass, "answer")));

        NativeMethodImplementation implementation = plan
                .implementationFor(decision.method().methodKey())
                .orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("(Lpkg/Api;)I", implementation.entry().descriptor());
        assertEquals(
                xyz.melodysky.packaging.MethodRewriteStrategy.INTERFACE_METHOD_STUB,
                implementation.decision().strategy());
    }

    @Test
    void selectsLlvmNativePathForStaticAndPrivateInterfaceMethods() {
        ParsedClass parsedClass = parse(
                "pkg/CodeApi.class",
                InterfaceMethodAsmFixtures.interfaceWithDefaultStaticAndPrivate(
                        "pkg/CodeApi"));
        var decisions = List.of(
                decision(parsedClass, "staticAnswer"),
                decision(parsedClass, "privateAnswer"));
        Map<String, IrMethod> methods = new LinkedHashMap<>();
        decisions.forEach(decision -> methods.put(
                decision.method().methodKey(),
                irMethod(parsedClass, decision.method().name())));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                new NativeRegistrationPlanner().plan(decisions),
                decisions,
                methods);

        assertEquals(2, plan.implementations().size());
        assertEquals(
                "()I",
                plan.implementationFor(decisions.get(0).method().methodKey())
                        .orElseThrow()
                        .entry()
                        .descriptor());
        assertEquals(
                "(Lpkg/CodeApi;)I",
                plan.implementationFor(decisions.get(1).method().methodKey())
                        .orElseThrow()
                        .entry()
                        .descriptor());
    }

    @Test
    void referenceIdentityRequiresJniEnvButDirectNullComparisonDoesNot() {
        ParsedClass parsedClass = parse(
                "pkg/ReferenceIdentity.class",
                AsmFixtureBuilder.classWithReferenceBranchMethods(
                        "pkg/ReferenceIdentity"));
        MethodRewriteDecision same = decision(parsedClass, "same");
        MethodRewriteDecision isNull = decision(parsedClass, "isNull");
        IrMethod sameIr = irMethod(parsedClass, "same");
        IrMethod isNullIr = irMethod(parsedClass, "isNull");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(same, isNull));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(same, isNull),
                Map.of(
                        same.method().methodKey(), sameIr,
                        isNull.method().methodKey(), isNullIr));

        NativeMethodImplementation sameImplementation = plan
                .implementationFor(same.method().methodKey())
                .orElseThrow();
        NativeMethodImplementation nullImplementation = plan
                .implementationFor(isNull.method().methodKey())
                .orElseThrow();
        assertTrue(sameImplementation.passesJniEnv());
        assertFalse(nullImplementation.passesJniEnv());
        assertEquals(
                new xyz.melodysky.backend.llvm.LlvmFunctionAbi(true, false),
                sameImplementation.llvmFunctionAbi());
        assertEquals(
                new xyz.melodysky.backend.llvm.LlvmFunctionAbi(false, false),
                nullImplementation.llvmFunctionAbi());
    }

    @Test
    void classLiteralOnlyMethodsFreezeJniEnvAbiForStaticAndInstanceBodies() {
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
        Map<String, IrMethod> irMethods = Map.of(
                staticLiteral.method().methodKey(),
                irMethod(parsedClass, "staticLiteral"),
                instanceLiteral.method().methodKey(),
                irMethod(parsedClass, "instanceLiteral"));

        NativeImplementationPlan plan =
                xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                        registrationPlan,
                        List.of(staticLiteral, instanceLiteral),
                        irMethods);

        NativeMethodImplementation staticImplementation = plan
                .implementationFor(staticLiteral.method().methodKey())
                .orElseThrow();
        NativeMethodImplementation instanceImplementation = plan
                .implementationFor(instanceLiteral.method().methodKey())
                .orElseThrow();
        assertTrue(staticImplementation.passesJniEnv());
        assertTrue(instanceImplementation.passesJniEnv());
        assertFalse(staticImplementation.passesOwnerClass());
        assertFalse(instanceImplementation.passesOwnerClass());
        assertEquals(
                List.of("class:Ljava/lang/String;"),
                staticImplementation.classObjectKeys());
        assertEquals(
                List.of("class:Ljava/lang/String;"),
                instanceImplementation.classObjectKeys());
        assertEquals(
                staticImplementation.llvmFunctionAbi(),
                new xyz.melodysky.backend.llvm.LlvmFunctionAbi(true, false));
        assertEquals(
                instanceImplementation.llvmFunctionAbi(),
                new xyz.melodysky.backend.llvm.LlvmFunctionAbi(true, false));
    }

    @Test
    void doesNotInventTemplateImplementationWhenNoNativeIrExists() {
        ParsedClass parsedClass = parse("pkg/StringOps.class", stringEchoClass());
        MethodRewriteDecision decision = decision(parsedClass, "echo");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of());

        assertTrue(plan.implementations().isEmpty());
    }

    @Test
    void keepsOutlinedCompilerHelperInFinalLlvmImplementationClosure() {
        ParsedClass parsedClass = parse(
                "pkg/OutlinedAdder.class",
                AsmFixtureBuilder.classWithAddMethod("pkg/OutlinedAdder"));
        MethodRewriteDecision decision = decision(parsedClass, "add");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));
        long seed = Integer.toUnsignedLong("cli-seed".hashCode());
        IrMethod optimizedMethod = OptimizationPipeline.defaultPipeline()
                .run(irMethod(parsedClass, "add"), PassContext.empty())
                .artifact()
                .orElseThrow();
        IrMethod protectedMethod = ProtectionPipeline.defaultPipeline().run(
                optimizedMethod,
                ProtectionConfig.enabled(seed));
        NativeImplementationPlan preliminaryPlan =
                xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                        registrationPlan,
                        List.of(decision),
                        Map.of(decision.method().methodKey(), protectedMethod),
                        java.util.Set.of(decision.method().methodKey()));
        assertEquals(
                1,
                preliminaryPlan.implementations().size(),
                protectedMethod.toString());
        var split = new MethodSplittingPass().run(
                protectedMethod,
                seed,
                true);
        assertEquals(MethodSplittingStatus.RAN, split.status());
        String helperKey = split.helpers().get(0).methodKey();
        Map<String, IrMethod> finalIr = Map.of(
                decision.method().methodKey(), split.caller(),
                helperKey, split.helpers().get(0).body());

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                finalIr,
                finalIr.keySet(),
                java.util.Set.of(helperKey));

        NativeMethodImplementation implementation =
                plan.implementationFor(decision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals(List.of(helperKey), implementation.directCallTargets());
    }

    @Test
    void harmlessCffCycleDoesNotInvalidateSynchronizedCleanupReferencePlan() {
        ParsedClass parsedClass = parse(
                "pkg/RecoveringSync.class",
                ExceptionFlowAsmFixtures.classWithRecoveringSynchronizedCleanup(
                        "pkg/RecoveringSync"));
        MethodRewriteDecision decision = decision(parsedClass, "recover");
        IrMethod optimized = OptimizationPipeline.defaultPipeline()
                .run(irMethod(parsedClass, "recover"), PassContext.empty())
                .artifact()
                .orElseThrow();
        IrMethod protectedMethod = null;
        for (long seed = 0; seed < 256; seed++) {
            IrMethod candidate = ProtectionPipeline.defaultPipeline().run(
                    optimized,
                    ProtectionConfig.enabled(seed));
            if (candidate.blocks().stream()
                    .anyMatch(block -> block.name().startsWith("cff_d_"))) {
                protectedMethod = candidate;
                break;
            }
        }
        assertTrue(protectedMethod != null, "fixture must exercise a CFF region");
        var localReferences = new NativeLocalReferencePlanner().plan(protectedMethod);
        assertTrue(
                localReferences.plan().isPresent(),
                localReferences.failureReason().orElse("missing local-reference plan"));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                new NativeRegistrationPlanner().plan(List.of(decision)),
                List.of(decision),
                Map.of(decision.method().methodKey(), protectedMethod));

        assertTrue(
                plan.implementationFor(decision.method().methodKey()).isPresent(),
                plan.unavailableReasonCodeFor(decision.method().methodKey())
                        .orElse("missing implementation without a reason")
                        + "\n"
                        + protectedMethod);
        assertTrue(plan.localReferencePlanFor(
                        decision.method().methodKey())
                .isPresent());
    }

    @Test
    void selectsLlvmFieldHelperPathForStaticAndInstanceFields() {
        ParsedClass staticClass = parse("pkg/StaticFields.class", AsmFixtureBuilder.classWithStaticFieldRead("pkg/StaticFields"));
        MethodRewriteDecision staticDecision = decision(staticClass, "getValue");
        IrMethod staticIr = irMethod(staticClass, "getValue");
        NativeRegistrationPlan staticRegistration = new NativeRegistrationPlanner().plan(List.of(staticDecision));

        NativeImplementationPlan staticPlan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                staticRegistration,
                List.of(staticDecision),
                Map.of(staticDecision.method().methodKey(), staticIr));

        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, staticPlan.implementations().get(0).path());
        assertEquals("LLVM_FIELD_HELPER_IR", staticPlan.implementations().get(0).reasonCode());
        assertTrue(staticPlan.implementations().get(0).passesJniEnv());
        assertTrue(staticPlan.implementations().get(0).passesOwnerClass());
        assertEquals(List.of("pkg/StaticFields#VALUE!I"), staticPlan.implementations().get(0).fieldKeys());

        ParsedClass instanceClass = parse("pkg/Fields.class", AsmFixtureBuilder.classWithInstanceFieldRead("pkg/Fields"));
        MethodRewriteDecision instanceDecision = decision(instanceClass, "read");
        IrMethod instanceIr = irMethod(instanceClass, "read");
        NativeRegistrationPlan instanceRegistration = new NativeRegistrationPlanner().plan(List.of(instanceDecision));

        NativeImplementationPlan instancePlan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                instanceRegistration,
                List.of(instanceDecision),
                Map.of(instanceDecision.method().methodKey(), instanceIr));

        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, instancePlan.implementations().get(0).path());
        assertEquals("LLVM_FIELD_HELPER_IR", instancePlan.implementations().get(0).reasonCode());
        assertTrue(instancePlan.implementations().get(0).passesJniEnv());
        assertTrue(!instancePlan.implementations().get(0).passesOwnerClass());
        assertEquals(List.of("pkg/Fields#value!I"), instancePlan.implementations().get(0).fieldKeys());
    }

    @Test
    void selectsLlvmFieldHelperPathForVolatileFieldsWithJmmFences() {
        ParsedClass parsedClass = parse(
                "pkg/VolatileFields.class",
                AsmFixtureBuilder.classWithVolatileFieldMethods("pkg/VolatileFields"));
        MethodRewriteDecision read = decision(parsedClass, "read");
        MethodRewriteDecision write = decision(parsedClass, "write");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(read, write));
        Map<String, IrMethod> irMethods = new LinkedHashMap<>();
        irMethods.put(read.method().methodKey(), irMethod(parsedClass, "read"));
        irMethods.put(write.method().methodKey(), irMethod(parsedClass, "write"));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(read, write),
                irMethods);

        NativeMethodImplementation readImplementation = plan.implementationFor(read.method().methodKey()).orElseThrow();
        NativeMethodImplementation writeImplementation = plan.implementationFor(write.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, readImplementation.path());
        assertEquals("LLVM_FIELD_HELPER_IR", readImplementation.reasonCode());
        assertTrue(readImplementation.passesJniEnv());
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, writeImplementation.path());
        assertEquals("LLVM_FIELD_HELPER_IR", writeImplementation.reasonCode());
        assertTrue(writeImplementation.passesJniEnv());
    }

    @Test
    void selectsLlvmMonitorHelperPathForSynchronizedBlock() {
        ParsedClass parsedClass = parse(
                "pkg/Locks.class",
                AsmFixtureBuilder.classWithMonitorBlockMethod("pkg/Locks"));
        MethodRewriteDecision decision = decision(parsedClass, "locked");
        IrMethod irMethod = irMethod(parsedClass, "locked");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        NativeMethodImplementation implementation = plan.implementationFor(decision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_MONITOR_HELPER_IR", implementation.reasonCode());
        assertTrue(implementation.passesJniEnv());
    }

    @Test
    void selectsLlvmSynchronizedMethodHelperPath() {
        ParsedClass instanceClass = parse(
                "pkg/SyncInstance.class",
                AsmFixtureBuilder.classWithSynchronizedInstanceMethod("pkg/SyncInstance"));
        MethodRewriteDecision instanceDecision = decision(instanceClass, "syncInstance");
        IrMethod instanceIr = irMethod(instanceClass, "syncInstance");
        NativeRegistrationPlan instanceRegistration = new NativeRegistrationPlanner().plan(List.of(instanceDecision));

        NativeImplementationPlan instancePlan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                instanceRegistration,
                List.of(instanceDecision),
                Map.of(instanceDecision.method().methodKey(), instanceIr));

        NativeMethodImplementation instanceImplementation = instancePlan
                .implementationFor(instanceDecision.method().methodKey())
                .orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, instanceImplementation.path());
        assertEquals("LLVM_SYNCHRONIZED_METHOD_HELPER_IR", instanceImplementation.reasonCode());
        assertTrue(instanceImplementation.passesJniEnv());

        ParsedClass staticClass = parse(
                "pkg/SyncStatic.class",
                AsmFixtureBuilder.classWithSynchronizedMethod("pkg/SyncStatic"));
        MethodRewriteDecision staticDecision = decision(staticClass, "sync");
        IrMethod staticIr = irMethod(staticClass, "sync");
        NativeRegistrationPlan staticRegistration = new NativeRegistrationPlanner().plan(List.of(staticDecision));

        NativeImplementationPlan staticPlan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                staticRegistration,
                List.of(staticDecision),
                Map.of(staticDecision.method().methodKey(), staticIr));

        NativeMethodImplementation staticImplementation = staticPlan
                .implementationFor(staticDecision.method().methodKey())
                .orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, staticImplementation.path());
        assertEquals("LLVM_SYNCHRONIZED_METHOD_HELPER_IR", staticImplementation.reasonCode());
        assertTrue(staticImplementation.passesJniEnv());
        assertEquals(List.of("class:Lpkg/SyncStatic;"), staticImplementation.classObjectKeys());
    }

    @Test
    void selectsDirectLlvmCallPathWhenStaticCalleeIsAlsoLlvmNative() {
        ParsedClass parsedClass = parse("pkg/Calls.class", AsmFixtureBuilder.classWithStaticCall("pkg/Calls"));
        MethodRewriteDecision value = decision(parsedClass, "value");
        MethodRewriteDecision call = decision(parsedClass, "call");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(value, call));
        Map<String, IrMethod> irMethods = new LinkedHashMap<>();
        irMethods.put(value.method().methodKey(), irMethod(parsedClass, "value"));
        irMethods.put(call.method().methodKey(), irMethod(parsedClass, "call"));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(value, call),
                irMethods);

        NativeMethodImplementation callImplementation = plan.implementationFor(call.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, callImplementation.path());
        assertEquals("LLVM_DIRECT_CALL_IR", callImplementation.reasonCode());
        assertEquals(List.of(value.method().methodKey()), callImplementation.directCallTargets());
    }

    @Test
    void routesReferenceReturningStaticCalleeThroughJniInsteadOfAssumingOwnedReturn() {
        ParsedClass parsedClass = parse(
                "pkg/ReferenceCalls.class",
                referenceReturningStaticCallClass());
        MethodRewriteDecision identity =
                decision(parsedClass, "identity");
        MethodRewriteDecision call = decision(parsedClass, "call");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(
                        List.of(identity, call));
        Map<String, IrMethod> irMethods = Map.of(
                identity.method().methodKey(),
                irMethod(parsedClass, "identity"),
                call.method().methodKey(),
                irMethod(parsedClass, "call"));

        NativeImplementationPlan plan =
                xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                        registrationPlan,
                        List.of(identity, call),
                        irMethods);

        NativeMethodImplementation implementation = plan
                .implementationFor(call.method().methodKey())
                .orElseThrow();
        assertTrue(implementation.directCallTargets().isEmpty());
        assertEquals(
                List.of(identity.method().methodKey()),
                implementation.staticCallKeys());
        assertEquals(
                "LLVM_STATIC_CALL_HELPER_IR",
                implementation.reasonCode());
    }

    @Test
    void routesLoopCallToReferenceProducingPrimitiveCalleeThroughJni() {
        ParsedClass parsedClass = parse(
                "pkg/ReferenceCallLoop.class",
                AsmFixtureBuilder.classWithStaticCall(
                        "pkg/ReferenceCallLoop"));
        MethodRewriteDecision leaf = decision(parsedClass, "value");
        MethodRewriteDecision caller = decision(parsedClass, "call");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(
                        List.of(leaf, caller));
        IrValue text = new IrValue("%text", IrType.REFERENCE);
        IrValue leafPending = new IrValue(
                "%leafPending",
                IrType.REFERENCE);
        IrValue leafValue = new IrValue("%leafValue", IrType.I32);
        IrMethod leafIr = new IrMethod(
                leaf.method().owner(),
                leaf.method().name(),
                leaf.method().descriptor(),
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.symbolicConstant(
                                                text,
                                                IrOpcode.CONST_STRING,
                                                "plain:v1:owned-local")
                                        .withExceptionSite(
                                                new IrExceptionSite(
                                                        IrExceptionSiteKind
                                                                .JVM_PENDING_EXCEPTION,
                                                        List.of(),
                                                        java.util.Optional.of(
                                                                leafPending))),
                                IrInstruction.constInt(leafValue, 7)),
                        IrTerminator.returnValue(leafValue))));
        IrValue callValue = new IrValue("%callValue", IrType.I32);
        IrValue callPending = new IrValue(
                "%callPending",
                IrType.REFERENCE);
        IrMethod callerIr = new IrMethod(
                caller.method().owner(),
                caller.method().name(),
                caller.method().descriptor(),
                IrType.I32,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.gotoBlock("loop")),
                        new IrBlock(
                                "loop",
                                List.of(IrInstruction.call(
                                                java.util.Optional.of(
                                                        callValue),
                                                IrOpcode.CALL_STATIC,
                                                List.of(),
                                                leaf.method().methodKey())
                                        .withExceptionSite(
                                                new IrExceptionSite(
                                                        IrExceptionSiteKind
                                                                .JVM_PENDING_EXCEPTION,
                                                        List.of(),
                                                        java.util.Optional.of(
                                                                callPending)))),
                                IrTerminator.gotoBlock("loop"))));
        Map<String, IrMethod> irMethods = Map.of(
                leaf.method().methodKey(),
                leafIr,
                caller.method().methodKey(),
                callerIr);

        NativeImplementationPlan plan =
                xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                        registrationPlan,
                        List.of(leaf, caller),
                        irMethods);

        assertTrue(plan.implementationFor(leaf.method().methodKey()).isPresent());
        NativeMethodImplementation callerImplementation = plan
                .implementationFor(caller.method().methodKey())
                .orElseThrow();
        assertTrue(callerImplementation.directCallTargets().isEmpty());
        assertEquals(
                List.of(leaf.method().methodKey()),
                callerImplementation.staticCallKeys());
    }

    @Test
    void selectsDirectLlvmCallPathWhenPrivateSpecialCalleeIsAlsoLlvmNative() {
        ParsedClass parsedClass = parse("pkg/SpecialCalls.class", privateSpecialCallClass());
        MethodRewriteDecision helper = decision(parsedClass, "helper");
        MethodRewriteDecision call = decision(parsedClass, "call");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(helper, call));
        Map<String, IrMethod> irMethods = new LinkedHashMap<>();
        irMethods.put(helper.method().methodKey(), irMethod(parsedClass, "helper"));
        irMethods.put(call.method().methodKey(), irMethod(parsedClass, "call"));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(helper, call),
                irMethods);

        NativeMethodImplementation callImplementation = plan.implementationFor(call.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, callImplementation.path());
        assertEquals("LLVM_DIRECT_CALL_IR", callImplementation.reasonCode());
        assertEquals(List.of(helper.method().methodKey()), callImplementation.directCallTargets());
    }

    @Test
    void selectsLlvmPathForIntegerDivisionThroughArithmeticExceptionHelper() {
        ParsedClass parsedClass = parse("pkg/DivOps.class", divClass());
        MethodRewriteDecision decision = decision(parsedClass, "div");
        IrMethod irMethod = irMethod(parsedClass, "div");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        assertEquals(1, plan.implementations().size());
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, plan.implementations().get(0).path());
        assertEquals("LLVM_DIV_REM_EXCEPTION_HELPER_IR", plan.implementations().get(0).reasonCode());
        assertTrue(plan.implementations().get(0).passesJniEnv());
    }

    @Test
    void reflectionMetadataHelperPathAllowsStaticallyResolvedParameterizedMember() {
        ParsedClass parsedClass = parse("pkg/ReflectionPlan.class", reflectionPlanClass());
        MethodRewriteDecision decision = decision(parsedClass, "member");
        NativeImplementationPlanner planner = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner();

        assertTrue(planner.supportsLlvmNativePath(decision, reflectionMemberIr(
                "j2ll_rt_get_declared_method|method:pkg/Target#value!()I")));
        assertTrue(planner.supportsLlvmNativePath(decision, reflectionMemberIr(
                "j2ll_rt_get_declared_method|method:pkg/Target#value!(I)I")));
    }

    @Test
    void selectsLlvmPathForIntArrayHelperSubset() {
        ParsedClass parsedClass = parse("pkg/Arrays.class", AsmFixtureBuilder.classWithArrayOperationMethods("pkg/Arrays"));
        MethodRewriteDecision decision = decision(parsedClass, "firstPlusLength");
        IrMethod irMethod = irMethod(parsedClass, "firstPlusLength");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        assertEquals(1, plan.implementations().size());
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, plan.implementations().get(0).path());
        assertEquals("LLVM_ARRAY_HELPER_IR", plan.implementations().get(0).reasonCode());
        assertTrue(plan.implementations().get(0).passesJniEnv());
    }

    @Test
    void selectsLlvmPathForSystemArraycopyHelper() {
        ParsedClass parsedClass = parse(
                "pkg/Arraycopy.class",
                AsmFixtureBuilder.classWithJdkSystemArraycopy("pkg/Arraycopy"));
        MethodRewriteDecision decision = decision(parsedClass, "copy");
        IrMethod irMethod = irMethod(parsedClass, "copy");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        assertEquals(1, plan.implementations().size());
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, plan.implementations().get(0).path());
        assertEquals("LLVM_ARRAYCOPY_HELPER_IR", plan.implementations().get(0).reasonCode());
        assertTrue(plan.implementations().get(0).passesJniEnv());
    }

    @Test
    void selectsLlvmPathForByteAndReferenceArrayHelperSubset() {
        ParsedClass parsedClass = parse("pkg/ReferenceArrays.class", referenceArrayClass());
        MethodRewriteDecision bytes = decision(parsedClass, "byteAt");
        MethodRewriteDecision refs = decision(parsedClass, "stringRoundtrip");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(bytes, refs));
        Map<String, IrMethod> irMethods = new LinkedHashMap<>();
        irMethods.put(bytes.method().methodKey(), irMethod(parsedClass, "byteAt"));
        irMethods.put(refs.method().methodKey(), irMethod(parsedClass, "stringRoundtrip"));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(bytes, refs),
                irMethods);

        NativeMethodImplementation byteImplementation = plan.implementationFor(bytes.method().methodKey()).orElseThrow();
        NativeMethodImplementation refImplementation = plan.implementationFor(refs.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, byteImplementation.path());
        assertEquals("LLVM_ARRAY_HELPER_IR", byteImplementation.reasonCode());
        assertTrue(byteImplementation.passesJniEnv());
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, refImplementation.path());
        assertEquals("LLVM_ARRAY_HELPER_IR", refImplementation.reasonCode());
        assertTrue(refImplementation.passesJniEnv());
    }

    @Test
    void selectsLlvmPathForBroadPrimitiveArrayHelperSubset() {
        ParsedClass parsedClass = parse("pkg/WideArrays.class", widePrimitiveArrayClass());
        MethodRewriteDecision longs = decision(parsedClass, "longRoundtrip");
        MethodRewriteDecision doubles = decision(parsedClass, "doubleRoundtrip");
        MethodRewriteDecision makeLongs = decision(parsedClass, "makeLongs");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(longs, doubles, makeLongs));
        Map<String, IrMethod> irMethods = new LinkedHashMap<>();
        irMethods.put(longs.method().methodKey(), irMethod(parsedClass, "longRoundtrip"));
        irMethods.put(doubles.method().methodKey(), irMethod(parsedClass, "doubleRoundtrip"));
        irMethods.put(makeLongs.method().methodKey(), irMethod(parsedClass, "makeLongs"));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(longs, doubles, makeLongs),
                irMethods);

        NativeMethodImplementation longImplementation = plan.implementationFor(longs.method().methodKey()).orElseThrow();
        NativeMethodImplementation doubleImplementation = plan.implementationFor(doubles.method().methodKey()).orElseThrow();
        NativeMethodImplementation makeLongsImplementation = plan.implementationFor(makeLongs.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, longImplementation.path());
        assertEquals("LLVM_ARRAY_HELPER_IR", longImplementation.reasonCode());
        assertTrue(longImplementation.passesJniEnv());
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, doubleImplementation.path());
        assertEquals("LLVM_ARRAY_HELPER_IR", doubleImplementation.reasonCode());
        assertTrue(doubleImplementation.passesJniEnv());
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, makeLongsImplementation.path());
        assertEquals("LLVM_ALLOCATION_HELPER_IR", makeLongsImplementation.reasonCode());
        assertTrue(makeLongsImplementation.passesJniEnv());
        assertEquals(List.of(), makeLongsImplementation.allocationKeys());
    }

    @Test
    void selectsLlvmPathForAllocationHelperSubset() {
        ParsedClass parsedClass = parse("pkg/Arrays.class", AsmFixtureBuilder.classWithArrayOperationMethods("pkg/Arrays"));
        MethodRewriteDecision decision = decision(parsedClass, "makeInts");
        IrMethod irMethod = irMethod(parsedClass, "makeInts");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        NativeMethodImplementation implementation = plan.implementationFor(decision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_ALLOCATION_HELPER_IR", implementation.reasonCode());
        assertTrue(implementation.passesJniEnv());
    }

    @Test
    void reportsMultianewarrayAsTheStructuredUnsupportedShape() {
        ParsedClass parsedClass = parse(
                "pkg/Arrays.class",
                AsmFixtureBuilder.classWithArrayOperationMethods(
                        "pkg/Arrays"));
        MethodRewriteDecision decision = decision(parsedClass, "multi");
        IrMethod irMethod = irMethod(parsedClass, "multi");

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner()
                .plan(
                        new NativeRegistrationPlanner().plan(
                                List.of(decision)),
                        List.of(decision),
                        Map.of(decision.method().methodKey(), irMethod));

        assertTrue(plan.implementations().isEmpty());
        assertEquals(
                NativeImplementationUnavailableReasonClassifier
                        .MULTIANEWARRAY_UNSUPPORTED,
                plan.unavailableReasonCodeFor(
                                decision.method().methodKey())
                        .orElseThrow());
    }

    @Test
    void selectsLlvmPathForArrayComponentReferenceAllocation() {
        ParsedClass parsedClass = parse(
                "pkg/ByteMatrices.class",
                AsmFixtureBuilder.classWithReferenceArrayAllocation(
                        "pkg/ByteMatrices",
                        "[B"));
        MethodRewriteDecision decision = decision(parsedClass, "array");
        IrMethod irMethod = irMethod(parsedClass, "array");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        NativeMethodImplementation implementation =
                plan.implementationFor(decision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_ALLOCATION_HELPER_IR", implementation.reasonCode());
        assertEquals(List.of("referenceArray:[B"), implementation.allocationKeys());
        assertTrue(implementation.passesJniEnv());
    }

    @Test
    void selectsLlvmPathWhenNestedArrayExceptionCanReachJavaHandler() {
        ParsedClass parsedClass = parse(
                "pkg/ProtectedByteMatrices.class",
                AsmFixtureBuilder.classWithProtectedReferenceArrayAllocation(
                        "pkg/ProtectedByteMatrices",
                        "[B"));
        MethodRewriteDecision decision = decision(parsedClass, "array");
        IrMethod irMethod = irMethod(parsedClass, "array");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        NativeMethodImplementation implementation =
                plan.implementationFor(decision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_ALLOCATION_HELPER_IR", implementation.reasonCode());
        assertEquals(List.of("referenceArray:[B"), implementation.allocationKeys());
        assertEquals(
                List.of("instanceof:java/lang/NegativeArraySizeException"),
                implementation.typeCheckKeys());
        assertTrue(implementation.passesJniEnv());
    }

    @Test
    void selectsLlvmPathForObjectAllocationAndConstructorHelperSubset() {
        ParsedClass parsedClass = parse("pkg/Alloc.class", AsmFixtureBuilder.classWithAllocation("pkg/Alloc", "pkg/Thing"));
        MethodRewriteDecision decision = decision(parsedClass, "make");
        IrMethod irMethod = irMethod(parsedClass, "make");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        NativeMethodImplementation implementation = plan.implementationFor(decision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_DISPATCH_HELPER_IR", implementation.reasonCode());
        assertEquals(List.of("object:pkg/Thing"), implementation.allocationKeys());
        assertEquals(List.of("pkg/Thing#<init>!()V"), implementation.constructorCallKeys());
        assertTrue(implementation.passesJniEnv());
    }

    @Test
    void selectsLlvmPathForTypeHelperSubset() {
        ParsedClass parsedClass = parse("pkg/TypeOps.class", AsmFixtureBuilder.classWithTypeOperationMethods("pkg/TypeOps"));
        MethodRewriteDecision cast = decision(parsedClass, "castString");
        MethodRewriteDecision instanceOf = decision(parsedClass, "isString");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(cast, instanceOf));
        Map<String, IrMethod> irMethods = new LinkedHashMap<>();
        irMethods.put(cast.method().methodKey(), irMethod(parsedClass, "castString"));
        irMethods.put(instanceOf.method().methodKey(), irMethod(parsedClass, "isString"));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(cast, instanceOf),
                irMethods);

        NativeMethodImplementation castImplementation = plan.implementationFor(cast.method().methodKey()).orElseThrow();
        NativeMethodImplementation instanceOfImplementation = plan.implementationFor(instanceOf.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, castImplementation.path());
        assertEquals("LLVM_TYPE_HELPER_IR", castImplementation.reasonCode());
        assertEquals(List.of("checkcast:java/lang/String"), castImplementation.typeCheckKeys());
        assertTrue(castImplementation.passesJniEnv());
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, instanceOfImplementation.path());
        assertEquals(List.of("instanceof:java/lang/String"), instanceOfImplementation.typeCheckKeys());
    }

    @Test
    void includesStableDeduplicatedTypedCatchMetadataForProtectedJvmExceptions() {
        ParsedClass parsedClass = parse("pkg/StringHelpers.class", stringHelperClass());
        MethodRewriteDecision decision = decision(parsedClass, "length");
        IrValue text = new IrValue("p0", IrType.REFERENCE);
        IrValue length = new IrValue("length", IrType.I32);
        IrValue exception = new IrValue("pending", IrType.REFERENCE);
        IrValue caught = new IrValue("caught", IrType.REFERENCE);
        IrValue fallback = new IrValue("fallback", IrType.I32);
        IrExceptionSite exceptionSite = new IrExceptionSite(
                IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                List.of(
                        new IrExceptionEdge(
                                "handler",
                                "java/lang/RuntimeException",
                                List.of(exception)),
                        new IrExceptionEdge(
                                "handler",
                                "java/lang/NullPointerException",
                                List.of(exception)),
                        new IrExceptionEdge(
                                "handler",
                                "java/lang/RuntimeException",
                                List.of(exception)),
                        new IrExceptionEdge("handler", "<any>", List.of(exception))),
                java.util.Optional.of(exception));
        IrMethod irMethod = new IrMethod(
                "pkg/StringHelpers",
                "length",
                "(Ljava/lang/String;)I",
                IrType.I32,
                List.of(text),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.operation(
                                                java.util.Optional.of(length),
                                                IrOpcode.CALL_RUNTIME_HELPER,
                                                List.of(text),
                                                "j2ll_rt_string_length")
                                        .withExceptionSite(exceptionSite)),
                                IrTerminator.returnValue(length)),
                        new IrBlock(
                                "handler",
                                List.of(caught),
                                List.of("java/lang/RuntimeException"),
                                List.of(IrInstruction.constInt(fallback, -1)),
                                IrTerminator.returnValue(fallback))));
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));

        NativeMethodImplementation implementation = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner()
                .plan(
                        registrationPlan,
                        List.of(decision),
                        Map.of(decision.method().methodKey(), irMethod))
                .implementationFor(decision.method().methodKey())
                .orElseThrow();

        assertEquals(
                List.of(
                        "instanceof:java/lang/NullPointerException",
                        "instanceof:java/lang/RuntimeException"),
                implementation.typeCheckKeys());
    }

    @Test
    void selectsLlvmPathForStringLengthAndEqualsHelpers() {
        ParsedClass parsedClass = parse("pkg/StringHelpers.class", stringHelperClass());
        MethodRewriteDecision length = decision(parsedClass, "length");
        MethodRewriteDecision same = decision(parsedClass, "same");
        MethodRewriteDecision charAt = decision(parsedClass, "charAt");
        MethodRewriteDecision starts = decision(parsedClass, "starts");
        MethodRewriteDecision substring = decision(parsedClass, "substring");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(
                length,
                same,
                charAt,
                starts,
                substring));
        Map<String, IrMethod> irMethods = new LinkedHashMap<>();
        irMethods.put(length.method().methodKey(), irMethod(parsedClass, "length"));
        irMethods.put(same.method().methodKey(), irMethod(parsedClass, "same"));
        irMethods.put(charAt.method().methodKey(), irMethod(parsedClass, "charAt"));
        irMethods.put(starts.method().methodKey(), irMethod(parsedClass, "starts"));
        irMethods.put(substring.method().methodKey(), irMethod(parsedClass, "substring"));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(length, same, charAt, starts, substring),
                irMethods);

        NativeMethodImplementation lengthImplementation = plan.implementationFor(length.method().methodKey()).orElseThrow();
        NativeMethodImplementation sameImplementation = plan.implementationFor(same.method().methodKey()).orElseThrow();
        NativeMethodImplementation charAtImplementation = plan.implementationFor(charAt.method().methodKey()).orElseThrow();
        NativeMethodImplementation startsImplementation = plan.implementationFor(starts.method().methodKey()).orElseThrow();
        NativeMethodImplementation substringImplementation =
                plan.implementationFor(substring.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, lengthImplementation.path());
        assertEquals("LLVM_STRING_HELPER_IR", lengthImplementation.reasonCode());
        assertEquals(List.of("j2ll_rt_string_length"), lengthImplementation.stringHelperSymbols());
        assertTrue(lengthImplementation.passesJniEnv());
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, sameImplementation.path());
        assertEquals(List.of("j2ll_rt_string_equals"), sameImplementation.stringHelperSymbols());
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, charAtImplementation.path());
        assertEquals(List.of("j2ll_rt_string_char_at"), charAtImplementation.stringHelperSymbols());
        assertEquals(List.of("j2ll_rt_string_starts_with"), startsImplementation.stringHelperSymbols());
        assertEquals(List.of("j2ll_rt_string_substring_range"), substringImplementation.stringHelperSymbols());
    }

    @Test
    void selectsLlvmStringConstantHelperPathForStringConcatRecipe() {
        ParsedClass parsedClass = parse(
                "pkg/StringConcatRecipe.class",
                AsmFixtureBuilder.classWithStringConcatWithConstants("pkg/StringConcatRecipe"));
        MethodRewriteDecision decision = decision(parsedClass, "concatRecipe");
        IrMethod irMethod = irMethod(parsedClass, "concatRecipe");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        NativeMethodImplementation implementation = plan.implementationFor(decision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_STRING_CONCAT_CONSTANTS_HELPER_IR", implementation.reasonCode());
        assertTrue(implementation.passesJniEnv());
        assertTrue(implementation.stringHelperSymbols().stream()
                .anyMatch(symbol -> symbol.startsWith(
                        "j2ll_rt_string_constant_")));
        assertFalse(implementation.stringHelperSymbols().contains(
                "j2ll_rt_string_constant"));
        assertTrue(implementation.stringHelperSymbols().contains("j2ll_rt_string_builder_append_i32"));
        assertTrue(implementation.templateIrMethod().isPresent());
    }

    @Test
    void selectsLlvmLambdaMetafactoryHelperPathForCommonLambdaShape() {
        ParsedClass parsedClass = parse(
                "pkg/LambdaShapes.class",
                AsmFixtureBuilder.classWithLambdaMetafactoryMethods("pkg/LambdaShapes"));
        MethodRewriteDecision decision = decision(parsedClass, "staticReference");
        IrMethod irMethod = irMethod(parsedClass, "staticReference");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        NativeMethodImplementation implementation = plan.implementationFor(decision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_LAMBDA_METAFACTORY_HELPER_IR", implementation.reasonCode());
        assertTrue(implementation.passesJniEnv());
        assertTrue(implementation.templateIrMethod().isPresent());
    }

    @Test
    void selectsLlvmPathForMathScalarJdkHelpers() {
        ParsedClass parsedClass = parse("pkg/MathHelpers.class", mathHelperClass());
        MethodRewriteDecision ints = decision(parsedClass, "ints");
        MethodRewriteDecision longs = decision(parsedClass, "longs");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(ints, longs));
        Map<String, IrMethod> irMethods = new LinkedHashMap<>();
        irMethods.put(ints.method().methodKey(), irMethod(parsedClass, "ints"));
        irMethods.put(longs.method().methodKey(), irMethod(parsedClass, "longs"));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(ints, longs),
                irMethods);

        NativeMethodImplementation intImplementation = plan.implementationFor(ints.method().methodKey()).orElseThrow();
        NativeMethodImplementation longImplementation = plan.implementationFor(longs.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, intImplementation.path());
        assertEquals("LLVM_JDK_INTRINSIC_HELPER_IR", intImplementation.reasonCode());
        assertTrue(intImplementation.passesJniEnv());
        assertTrue(intImplementation.classObjectKeys().contains("class:Ljava/lang/Math;"));
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, longImplementation.path());
        assertEquals("LLVM_JDK_INTRINSIC_HELPER_IR", longImplementation.reasonCode());
        assertTrue(longImplementation.passesJniEnv());
        assertTrue(longImplementation.classObjectKeys().contains("class:Ljava/lang/Math;"));
    }

    @Test
    void selectsLlvmPathForNoArgIntVirtualDispatchHelper() {
        ParsedClass parsedClass = parse("pkg/DispatchOps.class", dispatchOpsClass());
        MethodRewriteDecision decision = decision(parsedClass, "virtualValue");
        IrMethod irMethod = irMethod(parsedClass, "virtualValue");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        NativeMethodImplementation implementation = plan.implementationFor(decision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_DISPATCH_HELPER_IR", implementation.reasonCode());
        assertEquals(List.of("pkg/Base#value!()I"), implementation.dispatchKeys());
        assertTrue(implementation.passesJniEnv());
    }

    @Test
    void selectsLlvmPathForVirtualDispatchHelperWithPrimitiveArgument() {
        ParsedClass parsedClass = parse("pkg/DispatchOps.class", dispatchOpsClass());
        MethodRewriteDecision decision = decision(parsedClass, "virtualAdd");
        IrMethod irMethod = irMethod(parsedClass, "virtualAdd");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        NativeMethodImplementation implementation = plan.implementationFor(decision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_DISPATCH_HELPER_IR", implementation.reasonCode());
        assertEquals(List.of("pkg/Base#add!(I)I"), implementation.dispatchKeys());
        assertTrue(implementation.passesJniEnv());
    }

    @Test
    void selectsLlvmPathForUnsafeIntFieldTokenHelpers() {
        ParsedClass parsedClass = parse("pkg/UnsafePlan.class", unsafePlanClass());
        MethodRewriteDecision decision = decision(parsedClass, "read");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), unsafeGetIntIr()));

        NativeMethodImplementation implementation = plan.implementationFor(decision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_UNSAFE_HELPER_IR", implementation.reasonCode());
        assertTrue(implementation.passesJniEnv());
    }

    @Test
    void selectsLlvmPathForTypedVarHandleIntHelpers() {
        ParsedClass parsedClass = parse(
                "pkg/TypedVarHandles.class",
                AsmFixtureBuilder.classWithTypedIntVarHandleMethods("pkg/TypedVarHandles", "pkg/Target"));
        MethodRewriteDecision get = decision(parsedClass, "getInt");
        MethodRewriteDecision set = decision(parsedClass, "setInt");
        MethodRewriteDecision cas = decision(parsedClass, "compareAndSetInt");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(get, set, cas));
        Map<String, IrMethod> irMethods = new LinkedHashMap<>();
        irMethods.put(get.method().methodKey(), irMethod(parsedClass, "getInt"));
        irMethods.put(set.method().methodKey(), irMethod(parsedClass, "setInt"));
        irMethods.put(cas.method().methodKey(), irMethod(parsedClass, "compareAndSetInt"));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(get, set, cas),
                irMethods);

        NativeMethodImplementation getImplementation = plan.implementationFor(get.method().methodKey()).orElseThrow();
        NativeMethodImplementation setImplementation = plan.implementationFor(set.method().methodKey()).orElseThrow();
        NativeMethodImplementation casImplementation = plan.implementationFor(cas.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, getImplementation.path());
        assertEquals("LLVM_VARHANDLE_HELPER_IR", getImplementation.reasonCode());
        assertTrue(getImplementation.passesJniEnv());
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, setImplementation.path());
        assertEquals("LLVM_VARHANDLE_HELPER_IR", setImplementation.reasonCode());
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, casImplementation.path());
        assertEquals("LLVM_VARHANDLE_HELPER_IR", casImplementation.reasonCode());
    }

    @Test
    void selectsLlvmPathForVirtualOrInterfaceDispatchHelpers() {
        ParsedClass virtualClass = parse(
                "pkg/VirtualCalls.class",
                AsmFixtureBuilder.classWithVirtualCall("pkg/VirtualCalls", "pkg/RunnableThing"));
        MethodRewriteDecision virtualDecision = decision(virtualClass, "call");
        NativeRegistrationPlan virtualRegistration = new NativeRegistrationPlanner().plan(List.of(virtualDecision));

        NativeImplementationPlan virtualPlan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                virtualRegistration,
                List.of(virtualDecision),
                Map.of(virtualDecision.method().methodKey(), irMethod(virtualClass, "call")));

        NativeMethodImplementation virtualImplementation =
                virtualPlan.implementationFor(virtualDecision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, virtualImplementation.path());
        assertEquals("LLVM_DISPATCH_HELPER_IR", virtualImplementation.reasonCode());
        assertTrue(virtualImplementation.passesJniEnv());

        ParsedClass interfaceClass = parse(
                "pkg/InterfaceCalls.class",
                AsmFixtureBuilder.classWithInterfaceCall("pkg/InterfaceCalls", "pkg/Task"));
        MethodRewriteDecision interfaceDecision = decision(interfaceClass, "call");
        NativeRegistrationPlan interfaceRegistration = new NativeRegistrationPlanner().plan(List.of(interfaceDecision));

        NativeImplementationPlan interfacePlan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                interfaceRegistration,
                List.of(interfaceDecision),
                Map.of(interfaceDecision.method().methodKey(), irMethod(interfaceClass, "call")));

        NativeMethodImplementation interfaceImplementation =
                interfacePlan.implementationFor(interfaceDecision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, interfaceImplementation.path());
        assertEquals("LLVM_DISPATCH_HELPER_IR", interfaceImplementation.reasonCode());
        assertTrue(interfaceImplementation.passesJniEnv());
    }

    @Test
    void selectsLlvmSplitBodyForSimpleBranchingConstructorShape() {
        ParsedClass parsedClass = parse("pkg/BranchingCtor.class", branchingConstructorClass());
        MethodRewriteDecision decision = decision(parsedClass, "<init>");
        IrMethod constructorIr = irMethod(parsedClass, "<init>");
        assertTrue(
                xyz.melodysky.testsupport.TestProtectionMaterials
                        .initializerPlanner()
                        .plan(decision, constructorIr)
                        .isPresent(),
                () -> "initializer plan missing for " + constructorIr);
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));
        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), constructorIr));

        NativeMethodImplementation implementation = plan.implementationFor(decision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_CONSTRUCTOR_SPLIT_BODY_IR", implementation.reasonCode());
        assertTrue(implementation.templateIrMethod().isPresent());
        assertTrue(implementation.initializerPlan().isPresent());
        assertTrue(implementation.initializerPlan().orElseThrow().nativeBody().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .noneMatch(instruction -> instruction.symbol()
                        .map(symbol -> symbol.equals("java/lang/Object#<init>!()V"))
                        .orElse(false)));
    }

    @Test
    void selectsLlvmPathForClassInitializerLifecycleHelpers() {
        ParsedClass parsedClass = parse(
                "pkg/StaticLifecycle.class",
                classInitializerLifecycleClass());
        MethodRewriteDecision decision = decision(parsedClass, "<clinit>");
        IrMethod initializerIr = irMethod(parsedClass, "<clinit>");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), initializerIr));

        NativeMethodImplementation implementation =
                plan.implementationFor(decision.method().methodKey()).orElseThrow();
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_CLASS_INITIALIZER_BODY_IR", implementation.reasonCode());
        assertTrue(implementation.initializerPlan().isPresent());
        assertTrue(initializerIr.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.CLASS_INIT_BEGIN));
        assertTrue(initializerIr.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.CLASS_INIT_END));
        assertTrue(initializerIr.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.CLASS_INIT_FAILED));
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

    private byte[] stringEchoClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/StringOps", null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor echo = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "echo",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        echo.visitCode();
        echo.visitVarInsn(ALOAD, 0);
        echo.visitInsn(ARETURN);
        echo.visitMaxs(0, 0);
        echo.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] divClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/DivOps", null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor div = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "div", "(II)I", null, null);
        div.visitCode();
        div.visitVarInsn(ILOAD, 0);
        div.visitVarInsn(ILOAD, 1);
        div.visitInsn(IDIV);
        div.visitInsn(IRETURN);
        div.visitMaxs(0, 0);
        div.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] privateSpecialCallClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/SpecialCalls", null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor helper = writer.visitMethod(ACC_PRIVATE, "helper", "(I)I", null, null);
        helper.visitCode();
        helper.visitVarInsn(ILOAD, 1);
        helper.visitInsn(ICONST_2);
        helper.visitInsn(IMUL);
        helper.visitInsn(IRETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
        MethodVisitor call = writer.visitMethod(ACC_PUBLIC, "call", "(I)I", null, null);
        call.visitCode();
        call.visitVarInsn(ALOAD, 0);
        call.visitVarInsn(ILOAD, 1);
        call.visitMethodInsn(INVOKESPECIAL, "pkg/SpecialCalls", "helper", "(I)I", false);
        call.visitInsn(ICONST_1);
        call.visitInsn(IADD);
        call.visitInsn(IRETURN);
        call.visitMaxs(0, 0);
        call.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] widePrimitiveArrayClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/WideArrays", null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor longs = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "longRoundtrip", "([J)J", null, null);
        longs.visitCode();
        longs.visitVarInsn(ALOAD, 0);
        longs.visitInsn(ICONST_0);
        longs.visitInsn(LALOAD);
        longs.visitLdcInsn(3L);
        longs.visitInsn(LADD);
        longs.visitVarInsn(LSTORE, 1);
        longs.visitVarInsn(ALOAD, 0);
        longs.visitInsn(ICONST_1);
        longs.visitVarInsn(LLOAD, 1);
        longs.visitInsn(LASTORE);
        longs.visitVarInsn(ALOAD, 0);
        longs.visitInsn(ICONST_1);
        longs.visitInsn(LALOAD);
        longs.visitInsn(LRETURN);
        longs.visitMaxs(0, 0);
        longs.visitEnd();

        MethodVisitor doubles = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "doubleRoundtrip", "([D)D", null, null);
        doubles.visitCode();
        doubles.visitVarInsn(ALOAD, 0);
        doubles.visitInsn(ICONST_0);
        doubles.visitInsn(DALOAD);
        doubles.visitLdcInsn(2.0D);
        doubles.visitInsn(DMUL);
        doubles.visitVarInsn(DSTORE, 1);
        doubles.visitVarInsn(ALOAD, 0);
        doubles.visitInsn(ICONST_1);
        doubles.visitVarInsn(DLOAD, 1);
        doubles.visitInsn(DASTORE);
        doubles.visitVarInsn(ALOAD, 0);
        doubles.visitInsn(ICONST_1);
        doubles.visitInsn(DALOAD);
        doubles.visitInsn(DRETURN);
        doubles.visitMaxs(0, 0);
        doubles.visitEnd();

        MethodVisitor makeLongs = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "makeLongs", "(I)[J", null, null);
        makeLongs.visitCode();
        makeLongs.visitVarInsn(ILOAD, 0);
        makeLongs.visitIntInsn(NEWARRAY, T_LONG);
        makeLongs.visitInsn(ARETURN);
        makeLongs.visitMaxs(0, 0);
        makeLongs.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] stringHelperClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/StringHelpers", null, "java/lang/Object", null);
        MethodVisitor length = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "length", "(Ljava/lang/String;)I", null, null);
        length.visitCode();
        length.visitVarInsn(ALOAD, 0);
        length.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        length.visitInsn(IRETURN);
        length.visitMaxs(0, 0);
        length.visitEnd();
        MethodVisitor same = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "same",
                "(Ljava/lang/String;Ljava/lang/String;)Z",
                null,
                null);
        same.visitCode();
        same.visitVarInsn(ALOAD, 0);
        same.visitVarInsn(ALOAD, 1);
        same.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
        same.visitInsn(IRETURN);
        same.visitMaxs(0, 0);
        same.visitEnd();
        MethodVisitor charAt = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "charAt",
                "(Ljava/lang/String;I)I",
                null,
                null);
        charAt.visitCode();
        charAt.visitVarInsn(ALOAD, 0);
        charAt.visitVarInsn(ILOAD, 1);
        charAt.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        charAt.visitInsn(IRETURN);
        charAt.visitMaxs(0, 0);
        charAt.visitEnd();
        MethodVisitor starts = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "starts",
                "(Ljava/lang/String;Ljava/lang/String;)Z",
                null,
                null);
        starts.visitCode();
        starts.visitVarInsn(ALOAD, 0);
        starts.visitVarInsn(ALOAD, 1);
        starts.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false);
        starts.visitInsn(IRETURN);
        starts.visitMaxs(0, 0);
        starts.visitEnd();
        MethodVisitor substring = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "substring",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        substring.visitCode();
        substring.visitVarInsn(ALOAD, 0);
        substring.visitInsn(ICONST_1);
        substring.visitInsn(ICONST_2);
        substring.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
        substring.visitInsn(ARETURN);
        substring.visitMaxs(0, 0);
        substring.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] referenceReturningStaticCallClass() {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES
                        | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                "pkg/ReferenceCalls",
                null,
                "java/lang/Object",
                null);
        MethodVisitor identity = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "identity",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
        identity.visitCode();
        identity.visitVarInsn(ALOAD, 0);
        identity.visitInsn(ARETURN);
        identity.visitMaxs(0, 0);
        identity.visitEnd();
        MethodVisitor call = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "call",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
        call.visitCode();
        call.visitVarInsn(ALOAD, 0);
        call.visitMethodInsn(
                INVOKESTATIC,
                "pkg/ReferenceCalls",
                "identity",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        call.visitInsn(ARETURN);
        call.visitMaxs(0, 0);
        call.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] mathHelperClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/MathHelpers", null, "java/lang/Object", null);
        MethodVisitor ints = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "ints", "(II)I", null, null);
        ints.visitCode();
        ints.visitVarInsn(ILOAD, 0);
        ints.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false);
        ints.visitVarInsn(ILOAD, 0);
        ints.visitVarInsn(ILOAD, 1);
        ints.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
        ints.visitInsn(IADD);
        ints.visitVarInsn(ILOAD, 0);
        ints.visitVarInsn(ILOAD, 1);
        ints.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(II)I", false);
        ints.visitInsn(IADD);
        ints.visitInsn(IRETURN);
        ints.visitMaxs(0, 0);
        ints.visitEnd();
        MethodVisitor longs = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "longs", "(JJ)J", null, null);
        longs.visitCode();
        longs.visitVarInsn(LLOAD, 0);
        longs.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(J)J", false);
        longs.visitVarInsn(LLOAD, 0);
        longs.visitVarInsn(LLOAD, 2);
        longs.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(JJ)J", false);
        longs.visitInsn(LADD);
        longs.visitVarInsn(LLOAD, 0);
        longs.visitVarInsn(LLOAD, 2);
        longs.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(JJ)J", false);
        longs.visitInsn(LADD);
        longs.visitInsn(LRETURN);
        longs.visitMaxs(0, 0);
        longs.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] reflectionPlanClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ReflectionPlan", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "member",
                "()Ljava/lang/Object;",
                null,
                null);
        method.visitCode();
        method.visitInsn(ACONST_NULL);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] unsafePlanClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/UnsafePlan", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "read",
                "(Lsun/misc/Unsafe;Ljava/lang/Object;J)I",
                null,
                null);
        method.visitCode();
        method.visitInsn(ICONST_0);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        MethodVisitor virtualAdd = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "virtualAdd",
                "(Lpkg/Base;I)I",
                null,
                null);
        virtualAdd.visitCode();
        virtualAdd.visitVarInsn(ALOAD, 0);
        virtualAdd.visitVarInsn(ILOAD, 1);
        virtualAdd.visitMethodInsn(INVOKEVIRTUAL, "pkg/Base", "add", "(I)I", false);
        virtualAdd.visitInsn(IRETURN);
        virtualAdd.visitMaxs(0, 0);
        virtualAdd.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private IrMethod reflectionMemberIr(String helperSymbol) {
        IrValue token = new IrValue("token", IrType.I64);
        IrValue member = new IrValue("member", IrType.REFERENCE);
        IrValue pending = new IrValue("reflectionPending", IrType.REFERENCE);
        return new IrMethod(
                "pkg/ReflectionPlan",
                "member",
                "()Ljava/lang/Object;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constLong(token, 1L),
                                IrInstruction.operation(
                                                java.util.Optional.of(member),
                                                IrOpcode.CALL_RUNTIME_HELPER,
                                                List.of(token),
                                                helperSymbol)
                                        .withExceptionSite(new IrExceptionSite(
                                                IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                                                List.of(),
                                                java.util.Optional.of(pending)))),
                        IrTerminator.returnValue(member))));
    }

    private IrMethod unsafeGetIntIr() {
        IrValue unsafe = new IrValue("unsafe", IrType.REFERENCE);
        IrValue target = new IrValue("target", IrType.REFERENCE);
        IrValue token = new IrValue("token", IrType.I64);
        IrValue value = new IrValue("value", IrType.I32);
        IrValue pending = new IrValue("unsafePending", IrType.REFERENCE);
        return new IrMethod(
                "pkg/UnsafePlan",
                "read",
                "(Lsun/misc/Unsafe;Ljava/lang/Object;J)I",
                IrType.I32,
                List.of(unsafe, target, token),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.operation(
                                        java.util.Optional.of(value),
                                        IrOpcode.CALL_RUNTIME_HELPER,
                                        List.of(target, token),
                                        "j2ll_rt_unsafe_get_int|field:pkg/UnsafeTarget#value!I")
                                .withExceptionSite(new IrExceptionSite(
                                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                                        List.of(),
                                        java.util.Optional.of(pending)))),
                        IrTerminator.returnValue(value))));
    }

    private byte[] dispatchOpsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/DispatchOps", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "virtualValue",
                "(Lpkg/Base;)I",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKEVIRTUAL, "pkg/Base", "value", "()I", false);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        MethodVisitor virtualAdd = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "virtualAdd",
                "(Lpkg/Base;I)I",
                null,
                null);
        virtualAdd.visitCode();
        virtualAdd.visitVarInsn(ALOAD, 0);
        virtualAdd.visitVarInsn(ILOAD, 1);
        virtualAdd.visitMethodInsn(INVOKEVIRTUAL, "pkg/Base", "add", "(I)I", false);
        virtualAdd.visitInsn(IRETURN);
        virtualAdd.visitMaxs(0, 0);
        virtualAdd.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] referenceArrayClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ReferenceArrays", null, "java/lang/Object", null);
        MethodVisitor byteAt = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "byteAt", "([BI)I", null, null);
        byteAt.visitCode();
        byteAt.visitVarInsn(ALOAD, 0);
        byteAt.visitVarInsn(ILOAD, 1);
        byteAt.visitInsn(BALOAD);
        byteAt.visitInsn(IRETURN);
        byteAt.visitMaxs(0, 0);
        byteAt.visitEnd();
        MethodVisitor stringRoundtrip = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "stringRoundtrip",
                "([Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        stringRoundtrip.visitCode();
        stringRoundtrip.visitVarInsn(ALOAD, 0);
        stringRoundtrip.visitInsn(ICONST_0);
        stringRoundtrip.visitVarInsn(ALOAD, 1);
        stringRoundtrip.visitInsn(AASTORE);
        stringRoundtrip.visitVarInsn(ALOAD, 0);
        stringRoundtrip.visitInsn(ICONST_0);
        stringRoundtrip.visitInsn(AALOAD);
        stringRoundtrip.visitInsn(ARETURN);
        stringRoundtrip.visitMaxs(0, 0);
        stringRoundtrip.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] branchingConstructorClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/BranchingCtor", null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE, "x", "I", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "(II)V", null, null);
        org.objectweb.asm.Label useSecond = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitJumpInsn(IFLE, useSecond);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitFieldInsn(PUTFIELD, "pkg/BranchingCtor", "x", "I");
        constructor.visitJumpInsn(GOTO, done);
        constructor.visitLabel(useSecond);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 2);
        constructor.visitFieldInsn(PUTFIELD, "pkg/BranchingCtor", "x", "I");
        constructor.visitLabel(done);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] classInitializerLifecycleClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/StaticLifecycle", null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE | ACC_STATIC, "value", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor initializer = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        initializer.visitCode();
        initializer.visitTypeInsn(NEW, "java/lang/Object");
        initializer.visitInsn(DUP);
        initializer.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        initializer.visitFieldInsn(PUTSTATIC, "pkg/StaticLifecycle", "value", "Ljava/lang/Object;");
        initializer.visitInsn(RETURN);
        initializer.visitMaxs(0, 0);
        initializer.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
