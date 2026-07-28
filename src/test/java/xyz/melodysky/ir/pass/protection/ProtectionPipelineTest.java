package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.pass.PassDiagnostics;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.protection.audit.ProtectionApplicability;

class ProtectionPipelineTest {
    @Test
    void recordsExplicitPerMethodCoverageWithoutChangingSkippedPassMethodOutcome() {
        IrMethod method = splittableMethod();

        var result = splitAndFakePipeline().runDetailed(
                method,
                splitAndFakeConfig(true, false));
        var splitting = result.reports().stream()
                .filter(report -> report.passName().equals("BASIC_BLOCK_SPLITTING"))
                .findFirst()
                .orElseThrow()
                .coverageFacts()
                .get(0);
        var fakeBranches = result.reports().stream()
                .filter(report -> report.passName().equals("FAKE_BRANCHES"))
                .findFirst()
                .orElseThrow()
                .coverageFacts()
                .get(0);

        assertTrue(splitting.requested());
        assertEquals(ProtectionApplicability.APPLICABLE, splitting.applicability());
        assertTrue(splitting.affected());
        assertEquals("RAN", splitting.status());
        assertFalse(fakeBranches.requested());
        assertEquals(ProtectionApplicability.UNKNOWN, fakeBranches.applicability());
        assertFalse(fakeBranches.affected());
        assertEquals("SKIPPED", fakeBranches.status());
        assertEquals(
                splitting.subjectIdentityHash(),
                fakeBranches.subjectIdentityHash());
        assertEquals(64, splitting.subjectIdentityHash().length());
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void recordsNotApplicableInsteadOfInferringItFromSkippedStatus() {
        IrMethod method = method();

        var result = new ProtectionPipeline(List.of(new StringEncryptionPass()))
                .runDetailed(method, ProtectionConfig.enabled(7));
        var fact = result.reports().get(0).coverageFacts().get(0);

        assertEquals(method, result.method());
        assertTrue(fact.requested());
        assertEquals(ProtectionApplicability.NOT_APPLICABLE, fact.applicability());
        assertFalse(fact.affected());
        assertEquals("SKIPPED", fact.status());
        assertEquals("NO_STRING_CONSTANT_CARRIER", fact.reasonCode());
    }

    @Test
    void disabledProtectionIsNoOp() {
        IrMethod method = method();

        IrMethod protectedMethod = ProtectionPipeline.defaultPipeline().run(method, ProtectionConfig.disabled(7));

        assertEquals("entry", protectedMethod.blocks().get(0).name());
    }

    @Test
    void blockNamesAreDeterministicBySeed() {
        IrMethod method = method();

        IrMethod first = ProtectionPipeline.defaultPipeline().run(method, ProtectionConfig.enabled(7));
        IrMethod second = ProtectionPipeline.defaultPipeline().run(method, ProtectionConfig.enabled(7));
        IrMethod differentSeed = ProtectionPipeline.defaultPipeline().run(method, ProtectionConfig.enabled(8));

        assertEquals(first.blocks().get(0).name(), second.blocks().get(0).name());
        assertNotEquals(first.blocks().get(0).name(), differentSeed.blocks().get(0).name());
        assertTrue(new IrMethodValidator().validate(first).isEmpty());
    }

    @Test
    void enabledProtectionSkipsMonitorSensitiveMethodWithWarning() {
        IrValue monitor = new IrValue("%p0", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Locky",
                "run",
                "(Ljava/lang/Object;)V",
                IrType.VOID,
                List.of(monitor),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.operation(
                                java.util.Optional.empty(),
                                IrOpcode.MONITOR_ENTER,
                                List.of(monitor),
                                "monitor")),
                        IrTerminator.returnVoid())));

        var result = ProtectionPipeline.defaultPipeline().runWithDiagnostics(method, ProtectionConfig.enabled(7));

        assertEquals("entry", result.artifact().orElseThrow().blocks().get(0).name());
        assertEquals(PassDiagnostics.PROTECTION_MONITOR_SENSITIVE_SKIP, result.diagnostics().get(0).code());
    }

    @Test
    void stringEncryptionRewritesOnlyStringConstantCarrier() {
        IrValue token = new IrValue("%token", IrType.I64);
        IrValue value = new IrValue("%value", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Strings",
                "recipe",
                "()Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constLong(token, 123L),
                                IrInstruction.call(
                                        java.util.Optional.of(value),
                                        IrOpcode.CALL_RUNTIME_HELPER,
                                        List.of(token),
                                        "j2ll_rt_string_constant|string:value=")),
                        IrTerminator.returnValue(value))));

        var result = ProtectionPipeline.defaultPipeline().runDetailed(method, ProtectionConfig.enabled(11));
        String symbol = result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER)
                .findFirst()
                .orElseThrow()
                .symbol()
                .orElseThrow();

        assertTrue(symbol.startsWith("j2ll_rt_string_constant|enc:v2:"));
        assertFalse(symbol.contains("value="));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("STRING_ENCRYPTION")
                && report.status().equals("RAN")));
    }

    @Test
    void stringEncryptionLowersOrdinaryConstStringToEncryptedRuntimeHelper() {
        IrValue value = new IrValue("%value", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Strings",
                "literal",
                "()Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.symbolicConstant(value, IrOpcode.CONST_STRING, "string:plain-secret")),
                        IrTerminator.returnValue(value))));

        var result = new ProtectionPipeline(List.of(new StringEncryptionPass()))
                .runDetailed(method, ProtectionConfig.enabled(11));
        List<IrInstruction> instructions = result.method().blocks().get(0).instructions();

        assertEquals(2, instructions.size());
        assertEquals(IrOpcode.CONST_LONG, instructions.get(0).opcode());
        String tokenValueName = instructions.get(0).result().orElseThrow().name();
        assertTrue(tokenValueName.matches("%j2ll_v_[0-9a-f]{24}"));
        assertFalse(tokenValueName.startsWith("%j2ll_str_token_"));
        assertEquals(IrOpcode.CALL_RUNTIME_HELPER, instructions.get(1).opcode());
        assertEquals(value, instructions.get(1).result().orElseThrow());
        String symbol = instructions.get(1).symbol().orElseThrow();
        assertTrue(symbol.startsWith("j2ll_rt_string_constant|enc:v2:"));
        assertFalse(symbol.contains("plain-secret"));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
        var repeated = new ProtectionPipeline(List.of(new StringEncryptionPass()))
                .runDetailed(method, ProtectionConfig.enabled(11));
        var anotherBuild = new ProtectionPipeline(List.of(new StringEncryptionPass()))
                .runDetailed(method, ProtectionConfig.enabled(12));
        assertEquals(
                tokenValueName,
                repeated.method().blocks().get(0).instructions().get(0)
                        .result().orElseThrow().name());
        assertEquals(
                instructions.get(0).longLiteral(),
                repeated.method().blocks().get(0).instructions().get(0)
                        .longLiteral());
        assertNotEquals(
                tokenValueName,
                anotherBuild.method().blocks().get(0).instructions().get(0)
                        .result().orElseThrow().name());
        assertNotEquals(
                instructions.get(0).longLiteral(),
                anotherBuild.method().blocks().get(0).instructions().get(0)
                        .longLiteral());
    }

    @Test
    void stringEncryptionAcceptsRawConstStringSymbolFromTemplateIr() {
        IrValue value = new IrValue("%value", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Strings",
                "rawLiteral",
                "()Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.symbolicConstant(value, IrOpcode.CONST_STRING, "raw-secret")),
                        IrTerminator.returnValue(value))));

        var result = new ProtectionPipeline(List.of(new StringEncryptionPass()))
                .runDetailed(method, ProtectionConfig.enabled(11));
        String symbol = result.method().blocks().get(0).instructions().get(1).symbol().orElseThrow();

        assertTrue(symbol.startsWith("j2ll_rt_string_constant|enc:v2:"));
        assertFalse(symbol.contains("raw-secret"));
    }

    @Test
    void stringEncryptionSkipsReflectionSensitiveConstStringWithReason() {
        IrValue value = new IrValue("%value", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Reflective",
                "literal",
                "()Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.symbolicConstant(value, IrOpcode.CONST_STRING, "string:pkg.Reflective"),
                                IrInstruction.call(
                                        java.util.Optional.empty(),
                                        IrOpcode.CALL_RUNTIME_HELPER,
                                        List.of(),
                                        "j2ll_rt_class_for_name_static")),
                        IrTerminator.returnValue(value))));

        var result = new ProtectionPipeline(List.of(new StringEncryptionPass()))
                .runDetailed(method, ProtectionConfig.enabled(11));

        assertEquals(IrOpcode.CONST_STRING, result.method().blocks().get(0).instructions().get(0).opcode());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("STRING_ENCRYPTION")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("STRING_ENCRYPTION_REFLECTION_SENSITIVE")));
    }

    @Test
    void primitiveConstantEncryptionEmitsXorShape() {
        IrValue constant = new IrValue("%c", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Constants",
                "answer",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constInt(constant, 42)),
                        IrTerminator.returnValue(constant))));

        var result = ProtectionPipeline.defaultPipeline().runDetailed(method, ProtectionConfig.enabled(13));

        assertTrue(result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.XOR_I32));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void controlFlowFlatteningBuildsDispatcherSwitchForSafeBranchShape() {
        IrMethod method = branchingMethod(false);

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.terminator().kind() == xyz.melodysky.ir.model.IrTerminatorKind.SWITCH));
        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.parameters().stream().anyMatch(parameter -> parameter.name().contains("_state"))));
        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.name().startsWith("cff_true_")));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("RAN")
                && report.reasonCode().equals("CONTROL_FLOW_FLATTENING")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void controlFlowFlatteningPermutesDenseDispatcherStatesPerBuild() {
        IrMethod method = branchingMethod(false);
        ControlFlowFlatteningPass pass = new ControlFlowFlatteningPass();

        IrMethod first = pass.run(method, ProtectionConfig.enabled(19));
        IrMethod repeated = pass.run(method, ProtectionConfig.enabled(19));
        Map<String, Integer> firstStates = flattenedStates(first);

        assertEquals(first, repeated);
        assertEquals(
                IntStream.range(0, method.blocks().size())
                        .boxed()
                        .collect(java.util.stream.Collectors.toSet()),
                new HashSet<>(firstStates.values()));
        assertTrue(IntStream.range(20, 32)
                .mapToObj(seed -> pass.run(
                        method,
                        ProtectionConfig.enabled(seed)))
                .map(this::flattenedStates)
                .anyMatch(states -> !states.equals(firstStates)));
        assertTrue(new IrMethodValidator().validate(first).isEmpty());
    }

    @Test
    void controlFlowFlatteningSupportsHelperCallShape() {
        IrMethod method = branchingMethod(true);
        IrInstruction helper = method.blocks().get(0).instructions().get(0);

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("RAN")
                && report.reasonCode().equals("CONTROL_FLOW_FLATTENING")));
        assertTrue(result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction == helper));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void controlFlowFlatteningSkipsCrossBlockSsaValueInsteadOfBreakingDominance() {
        IrValue value = new IrValue("%value", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/CrossBlock",
                "value",
                "()I",
                IrType.I32,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.constInt(value, 7)),
                                IrTerminator.gotoBlock("exit")),
                        new IrBlock(
                                "exit",
                                List.of(),
                                IrTerminator.returnValue(value))));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method, result.method());
        assertTrue(result.reports().stream().anyMatch(report ->
                report.passName().equals("CONTROL_FLOW_FLATTENING")
                        && report.status().equals("SKIPPED")
                        && report.reasonCode().equals(
                                "CONTROL_FLOW_FLATTENING_CROSS_BLOCK_SSA_VALUE")));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                "CONTROL_FLOW_FLATTENING_CROSS_BLOCK_SSA_VALUE".equals(
                        diagnostic.decision())));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void validationFailureRollsBackProtectionPassOutput() {
        IrMethod method = method();
        IrValue undefined = new IrValue("%undefined", IrType.I32);
        ProtectionPass invalidPass = new ProtectionPass() {
            @Override
            public String name() {
                return "INVALID_TEST_PASS";
            }

            @Override
            public IrMethod run(IrMethod input, ProtectionConfig config) {
                return new IrMethod(
                        input.owner(),
                        input.name(),
                        input.descriptor(),
                        IrType.I32,
                        input.parameters(),
                        List.of(new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.returnValue(undefined))));
            }
        };

        var result = new ProtectionPipeline(List.of(invalidPass))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method, result.method());
        assertTrue(result.reports().stream().anyMatch(report ->
                report.passName().equals("INVALID_TEST_PASS")
                        && report.status().equals("FAILED")
                        && report.reasonCode().equals("PASS_VALIDATION_FAILED")));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().value().equals("PASS_VALIDATION_FAILED")
                        && diagnostic.severity().wireName().equals("warning")
                        && diagnostic.message().contains("IR_USE_BEFORE_DEF")
                        && "rollbackToPassInput".equals(
                                diagnostic.decision())));
        assertTrue(result.diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.severity().wireName().equals("error")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void invalidProtectionInputRemainsABuildLevelValidationError() {
        IrValue undefined = new IrValue("%undefined", IrType.I32);
        IrMethod invalid = new IrMethod(
                "pkg/Invalid",
                "value",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(),
                        IrTerminator.returnValue(undefined))));
        boolean[] invoked = {false};
        ProtectionPass pass = new ProtectionPass() {
            @Override
            public String name() {
                return "SHOULD_NOT_RUN";
            }

            @Override
            public IrMethod run(IrMethod input, ProtectionConfig config) {
                invoked[0] = true;
                return input;
            }
        };

        var result = new ProtectionPipeline(List.of(pass))
                .runDetailed(invalid, ProtectionConfig.enabled(19));

        assertFalse(invoked[0]);
        assertEquals(invalid, result.method());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().value().equals("IR_USE_BEFORE_DEF")
                        && diagnostic.severity().wireName().equals("error")));
        assertTrue(result.reports().stream().anyMatch(report ->
                report.passName().equals("SHOULD_NOT_RUN")
                        && report.status().equals("FAILED")
                        && report.reasonCode().equals(
                                "PROTECTION_INPUT_VALIDATION_FAILED")));
    }

    @Test
    void controlFlowFlatteningSkipsExceptionEdgeShapeWithReason() {
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue caught = new IrValue("%caught", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Exceptional",
                "choose",
                "()I",
                IrType.I32,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                List.of(),
                                List.of(new IrExceptionEdge("handler", "java/lang/Throwable")),
                                List.of(IrInstruction.constInt(zero, 0)),
                                IrTerminator.gotoBlock("exit")),
                        new IrBlock("exit", List.of(), IrTerminator.returnValue(zero)),
                        new IrBlock(
                                "handler",
                                List.of(caught),
                                List.of("java/lang/Throwable"),
                                List.of(),
                                IrTerminator.returnValue(zero))));

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE")));
    }

    @Test
    void controlFlowFlatteningSkipsOwnedReferenceCallsFieldsAndPendingExceptions() {
        IrValue receiver = new IrValue("%p0", IrType.REFERENCE);
        IrValue fieldValue = new IrValue("%field", IrType.REFERENCE);
        IrValue helperValue = new IrValue("%helper", IrType.REFERENCE);
        IrValue callValue = new IrValue("%call", IrType.REFERENCE);
        IrValue fieldException = new IrValue("%fieldException", IrType.REFERENCE);
        IrValue helperException = new IrValue("%helperException", IrType.REFERENCE);
        IrValue callException = new IrValue("%callException", IrType.REFERENCE);
        IrValue nullValue = new IrValue("%null", IrType.REFERENCE);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrInstruction fieldGet = IrInstruction.fieldGet(
                        fieldValue,
                        IrOpcode.GET_FIELD,
                        List.of(receiver),
                        "pkg/Cff#value!Ljava/lang/Object;")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(),
                        java.util.Optional.of(fieldException)));
        IrInstruction helperCall = IrInstruction.call(
                        java.util.Optional.of(helperValue),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(fieldValue),
                        "j2ll_rt_reference_helper")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(),
                        java.util.Optional.of(helperException)));
        IrInstruction javaCall = IrInstruction.call(
                        java.util.Optional.of(callValue),
                        IrOpcode.CALL_VIRTUAL,
                        List.of(helperValue),
                        "java/lang/Object#toString!()Ljava/lang/String;")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(),
                        java.util.Optional.of(callException)));
        IrMethod method = new IrMethod(
                "pkg/Cff",
                "choose",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                IrType.REFERENCE,
                List.of(receiver),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        fieldGet,
                                        helperCall,
                                        javaCall,
                                        IrInstruction.constNull(nullValue),
                                        IrInstruction.binary(
                                                condition,
                                                IrOpcode.CMP_EQ_REF,
                                                callValue,
                                                nullValue)),
                                IrTerminator.branch(condition, "nil", "value")),
                        new IrBlock("nil", List.of(IrInstruction.constNull(
                                new IrValue("%nil", IrType.REFERENCE))),
                                IrTerminator.returnValue(receiver)),
                        new IrBlock("value", List.of(), IrTerminator.returnValue(receiver))));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method, result.method());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals(
                        "CONTROL_FLOW_FLATTENING_OWNED_LOCAL_REFERENCE")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void controlFlowFlatteningAllowsNullAndBorrowedReferenceResults() {
        IrValue input = new IrValue("%p0", IrType.REFERENCE);
        IrValue nullValue = new IrValue("%null", IrType.REFERENCE);
        IrValue borrowed = new IrValue("%borrowed", IrType.REFERENCE);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrInstruction nullConstant = IrInstruction.constNull(nullValue);
        IrInstruction checkcast = IrInstruction.operation(
                java.util.Optional.of(borrowed),
                IrOpcode.CHECKCAST,
                List.of(input),
                "checkcast:java/lang/Object");
        IrMethod method = new IrMethod(
                "pkg/CffBorrowed",
                "choose",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                IrType.REFERENCE,
                List.of(input),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        nullConstant,
                                        checkcast,
                                        IrInstruction.binary(
                                                condition,
                                                IrOpcode.CMP_EQ_REF,
                                                borrowed,
                                                nullValue)),
                                IrTerminator.branch(condition, "nil", "value")),
                        new IrBlock("nil", List.of(), IrTerminator.returnValue(input)),
                        new IrBlock("value", List.of(), IrTerminator.returnValue(input))));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertTrue(result.reports().stream().anyMatch(report ->
                report.passName().equals("CONTROL_FLOW_FLATTENING")
                        && report.status().equals("RAN")
                        && report.reasonCode().equals(
                                "CONTROL_FLOW_FLATTENING")));
        assertTrue(result.method().blocks().stream()
                .anyMatch(block ->
                        block.terminator().kind() == IrTerminatorKind.SWITCH));
        assertTrue(result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction == nullConstant));
        assertTrue(result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction == checkcast));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void controlFlowFlatteningSkipsProtectedPendingExceptionSite() {
        IrValue resultValue = new IrValue("%result", IrType.I32);
        IrValue pending = new IrValue("%pending", IrType.REFERENCE);
        IrValue caught = new IrValue("%caught", IrType.REFERENCE);
        IrValue handlerValue = new IrValue("%handlerValue", IrType.I32);
        IrExceptionEdge handler =
                new IrExceptionEdge("handler", "java/lang/RuntimeException", List.of(pending));
        IrInstruction helper = IrInstruction.call(
                        java.util.Optional.of(resultValue),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(),
                        "j2ll_rt_int_helper")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(handler),
                        java.util.Optional.of(pending)));
        IrMethod method = new IrMethod(
                "pkg/Cff",
                "protectedCall",
                "()I",
                IrType.I32,
                List.of(),
                List.of(
                        new IrBlock("entry", List.of(helper), IrTerminator.gotoBlock("exit")),
                        new IrBlock("exit", List.of(), IrTerminator.returnValue(resultValue)),
                        new IrBlock(
                                "handler",
                                List.of(caught),
                                List.of("java/lang/RuntimeException"),
                                List.of(IrInstruction.constInt(handlerValue, 0)),
                                IrTerminator.returnValue(handlerValue))));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE")));
    }

    @Test
    void controlFlowFlatteningSkipsOwnedClassInitGuardReferenceWithoutReordering() {
        IrValue receiver = new IrValue("%p0", IrType.REFERENCE);
        IrValue classId = new IrValue("%classId", IrType.I64);
        IrValue classObject = new IrValue("%class", IrType.REFERENCE);
        IrValue classException = new IrValue("%classException", IrType.REFERENCE);
        IrValue guardException = new IrValue("%guardException", IrType.REFERENCE);
        IrValue nullValue = new IrValue("%null", IrType.REFERENCE);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrInstruction object = IrInstruction.operation(
                        java.util.Optional.of(classObject),
                        IrOpcode.CLASS_OBJECT,
                        List.of(classId),
                        "class:Lpkg/Target;")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(),
                        java.util.Optional.of(classException)));
        IrInstruction guard = IrInstruction.operation(
                        java.util.Optional.empty(),
                        IrOpcode.CLASS_INIT_GUARD,
                        List.of(classObject),
                        "class:Lpkg/Target;")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(),
                        java.util.Optional.of(guardException)));
        IrInstruction happensBefore = IrInstruction.operation(
                java.util.Optional.empty(),
                IrOpcode.CLASS_INIT_HAPPENS_BEFORE,
                List.of(classObject),
                "classInitGuard");
        IrMethod method = new IrMethod(
                "pkg/Cff",
                "guarded",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                IrType.REFERENCE,
                List.of(receiver),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        IrInstruction.constLong(classId, 7L),
                                        object,
                                        guard,
                                        happensBefore,
                                        IrInstruction.constNull(nullValue),
                                        IrInstruction.binary(
                                                condition,
                                                IrOpcode.CMP_EQ_REF,
                                                receiver,
                                                nullValue)),
                                IrTerminator.branch(condition, "nil", "value")),
                        new IrBlock("nil", List.of(), IrTerminator.returnValue(receiver)),
                        new IrBlock("value", List.of(), IrTerminator.returnValue(receiver))));

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method, result.method());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals(
                        "CONTROL_FLOW_FLATTENING_OWNED_LOCAL_REFERENCE")));
        assertEquals(
                List.of(object, guard, happensBefore),
                result.method().blocks().get(0).instructions().subList(1, 4));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void controlFlowFlatteningSkipsUnprovenClassInitOrdering() {
        IrValue classObject = new IrValue("%class", IrType.REFERENCE);
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrMethod method = new IrMethod(
                "pkg/Cff",
                "badGuard",
                "(Ljava/lang/Class;)I",
                IrType.I32,
                List.of(classObject),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        IrInstruction.operation(
                                                java.util.Optional.empty(),
                                                IrOpcode.CLASS_INIT_GUARD,
                                                List.of(classObject),
                                                "class:Lpkg/Target;"),
                                        IrInstruction.constInt(zero, 0),
                                        IrInstruction.binary(condition, IrOpcode.CMP_EQ_I32, zero, zero)),
                                IrTerminator.branch(condition, "yes", "no")),
                        new IrBlock("yes", List.of(), IrTerminator.returnValue(zero)),
                        new IrBlock("no", List.of(), IrTerminator.returnValue(zero))));

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE")));
    }

    @Test
    void controlFlowFlatteningSkipsMonitorSensitiveShapeBeforeRewrite() {
        IrValue monitor = new IrValue("%monitor", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Locky",
                "guarded",
                "(Ljava/lang/Object;)V",
                IrType.VOID,
                List.of(monitor),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.operation(
                                        java.util.Optional.empty(),
                                        IrOpcode.MONITOR_ENTER,
                                        List.of(monitor),
                                        "monitor")),
                                IrTerminator.gotoBlock("exit")),
                        new IrBlock("exit", List.of(), IrTerminator.returnVoid())));

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("PROTECTION_MONITOR_SENSITIVE_SKIP")));
    }

    @Test
    void controlFlowFlatteningSkipsVolatileJmmShapeWithReason() {
        IrValue input = new IrValue("%p0", IrType.I32);
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrMethod method = new IrMethod(
                "pkg/Jmm",
                "guarded",
                "(I)I",
                IrType.I32,
                List.of(input),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        IrInstruction.operation(
                                                java.util.Optional.empty(),
                                                IrOpcode.VOLATILE_READ_BARRIER,
                                                List.of(),
                                                "volatile"),
                                        IrInstruction.constInt(zero, 0),
                                        IrInstruction.binary(condition, IrOpcode.CMP_GE_I32, input, zero)),
                                IrTerminator.branch(condition, "yes", "no")),
                        new IrBlock("yes", List.of(), IrTerminator.returnValue(input)),
                        new IrBlock("no", List.of(), IrTerminator.returnValue(zero))));

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE")));
    }

    @Test
    void controlFlowFlatteningSupportsReferenceSignatureAndReturns() {
        IrValue input = new IrValue("%p0", IrType.REFERENCE);
        IrValue nullValue = new IrValue("%null", IrType.REFERENCE);
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrMethod method = new IrMethod(
                "pkg/References",
                "choose",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                IrType.REFERENCE,
                List.of(input),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        IrInstruction.constInt(zero, 0),
                                        IrInstruction.binary(condition, IrOpcode.CMP_EQ_I32, zero, zero)),
                                IrTerminator.branch(condition, "value", "nil")),
                        new IrBlock("value", List.of(), IrTerminator.returnValue(input)),
                        new IrBlock("nil", List.of(IrInstruction.constNull(nullValue)), IrTerminator.returnValue(nullValue))));

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(19));

        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONTROL_FLOW_FLATTENING")
                && report.status().equals("RAN")
                && report.reasonCode().equals("CONTROL_FLOW_FLATTENING")));
        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.terminator().kind() == IrTerminatorKind.SWITCH));
        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.terminator().value().filter(input::equals).isPresent()));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void floatConstantEncryptionPreservesBitsThroughIntegerXorAndBitcast() {
        IrValue constant = new IrValue("%c", IrType.F32);
        IrMethod method = new IrMethod(
                "pkg/Constants",
                "floatBits",
                "()F",
                IrType.F32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constFloat(constant, -0.0F)),
                        IrTerminator.returnValue(constant))));

        var result = ProtectionPipeline.defaultPipeline().runDetailed(method, ProtectionConfig.enabled(13));

        List<IrInstruction> instructions = result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .toList();
        assertTrue(instructions.stream().noneMatch(instruction -> instruction.opcode() == IrOpcode.CONST_FLOAT));
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.XOR_I32));
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.BITCAST_I32_TO_F32
                && instruction.result().orElseThrow().equals(constant)));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONSTANT_ENCRYPTION")
                && report.status().equals("RAN")
                && report.reasonCode().equals("FLOAT_CONSTANT_ENCRYPTION")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void doubleConstantEncryptionPreservesBitsThroughIntegerXorAndBitcast() {
        IrValue constant = new IrValue("%c", IrType.F64);
        IrMethod method = new IrMethod(
                "pkg/Constants",
                "doubleBits",
                "()D",
                IrType.F64,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constDouble(constant, Double.NaN)),
                        IrTerminator.returnValue(constant))));

        var result = ProtectionPipeline.defaultPipeline().runDetailed(method, ProtectionConfig.enabled(13));

        List<IrInstruction> instructions = result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .toList();
        assertTrue(instructions.stream().noneMatch(instruction -> instruction.opcode() == IrOpcode.CONST_DOUBLE));
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.XOR_I64));
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.opcode() == IrOpcode.BITCAST_I64_TO_F64
                && instruction.result().orElseThrow().equals(constant)));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("CONSTANT_ENCRYPTION")
                && report.status().equals("RAN")
                && report.reasonCode().equals("DOUBLE_CONSTANT_ENCRYPTION")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void basicBlockSplittingOnlySplitsInstructionsWhenEnabledAlone() {
        IrMethod method = splittableMethod();

        var result = splitAndFakePipeline().runDetailed(method, splitAndFakeConfig(true, false));

        assertEquals(2, result.method().blocks().size());
        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.terminator().kind() == xyz.melodysky.ir.model.IrTerminatorKind.GOTO));
        assertFalse(result.method().blocks().stream()
                .anyMatch(block -> block.terminator().kind() == xyz.melodysky.ir.model.IrTerminatorKind.BRANCH));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("BASIC_BLOCK_SPLITTING")
                && report.status().equals("RAN")));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("FAKE_BRANCHES")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("PROTECTION_PASS_DISABLED")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void basicBlockSplittingPreservesCallFieldReferenceAndExceptionSitesOnBothSides() {
        IrValue receiver = new IrValue("%p0", IrType.REFERENCE);
        IrValue fieldValue = new IrValue("%field", IrType.REFERENCE);
        IrValue helperValue = new IrValue("%helper", IrType.REFERENCE);
        IrValue fieldException = new IrValue("%fieldException", IrType.REFERENCE);
        IrValue helperException = new IrValue("%helperException", IrType.REFERENCE);
        IrInstruction fieldGet = IrInstruction.fieldGet(
                        fieldValue,
                        IrOpcode.GET_FIELD,
                        List.of(receiver),
                        "pkg/Split#value!Ljava/lang/Object;")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(),
                        java.util.Optional.of(fieldException)));
        IrInstruction helperCall = IrInstruction.call(
                        java.util.Optional.of(helperValue),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(fieldValue),
                        "j2ll_rt_reference_helper")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(),
                        java.util.Optional.of(helperException)));
        IrMethod method = new IrMethod(
                "pkg/Split",
                "read",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                IrType.REFERENCE,
                List.of(receiver),
                List.of(new IrBlock(
                        "entry",
                        List.of(fieldGet, helperCall),
                        IrTerminator.returnValue(helperValue))));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());

        var result = new ProtectionPipeline(List.of(new BasicBlockSplittingPass()))
                .runDetailed(method, splitAndFakeConfig(true, false));

        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("BASIC_BLOCK_SPLITTING")
                && report.status().equals("RAN")));
        assertEquals(2, result.method().blocks().size());
        IrBlock prefix = result.method().blocks().get(0);
        IrBlock suffix = result.method().blocks().get(1);
        assertEquals(List.of(fieldGet), prefix.instructions());
        assertEquals(List.of(helperCall), suffix.instructions());
        assertTrue(fieldGet == prefix.instructions().get(0));
        assertTrue(helperCall == suffix.instructions().get(0));
        assertEquals(fieldGet.exceptionSites(), prefix.instructions().get(0).exceptionSites());
        assertEquals(helperCall.exceptionSites(), suffix.instructions().get(0).exceptionSites());
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void basicBlockSplittingMovesExplicitThrowEdgesAndTerminatorToSuffix() {
        IrValue receiver = new IrValue("%p0", IrType.REFERENCE);
        IrValue nullValue = new IrValue("%null", IrType.REFERENCE);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue caught = new IrValue("%caught", IrType.REFERENCE);
        IrExceptionEdge handler =
                new IrExceptionEdge("handler", "java/lang/RuntimeException", List.of(receiver));
        IrBlock originalEntry = new IrBlock(
                "entry",
                List.of(),
                List.of(),
                List.of(handler),
                List.of(
                        IrInstruction.constNull(nullValue),
                        IrInstruction.binary(condition, IrOpcode.CMP_EQ_REF, receiver, nullValue)),
                IrTerminator.throwValue(receiver));
        IrBlock originalHandler = new IrBlock(
                "handler",
                List.of(caught),
                List.of("java/lang/RuntimeException"),
                List.of(),
                IrTerminator.returnValue(caught));
        IrMethod method = new IrMethod(
                "pkg/Split",
                "throwing",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                IrType.REFERENCE,
                List.of(receiver),
                List.of(originalEntry, originalHandler));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());

        var result = new ProtectionPipeline(List.of(new BasicBlockSplittingPass()))
                .runDetailed(method, splitAndFakeConfig(true, false));

        assertEquals(3, result.method().blocks().size());
        IrBlock prefix = result.method().blocks().get(0);
        IrBlock suffix = result.method().blocks().get(1);
        assertTrue(prefix.exceptionEdges().isEmpty());
        assertEquals(IrTerminatorKind.GOTO, prefix.terminator().kind());
        assertEquals(List.of(handler), suffix.exceptionEdges());
        assertEquals(IrTerminatorKind.THROW, suffix.terminator().kind());
        assertTrue(originalHandler == result.method().blocks().get(2));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void basicBlockSplittingStillSkipsJmmSensitiveBody() {
        IrValue value = new IrValue("%value", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Jmm",
                "read",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.operation(
                                        java.util.Optional.empty(),
                                        IrOpcode.VOLATILE_READ_BARRIER,
                                        List.of(),
                                        "volatile"),
                                IrInstruction.constInt(value, 1)),
                        IrTerminator.returnValue(value))));

        var result = new ProtectionPipeline(List.of(new BasicBlockSplittingPass()))
                .runDetailed(method, splitAndFakeConfig(true, false));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("BASIC_BLOCK_SPLITTING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("PROTECTION_CFG_SHAPE_NOT_SUPPORTED")));
    }

    @Test
    void basicBlockSplittingStillSkipsMonitorSensitiveBody() {
        IrValue monitor = new IrValue("%p0", IrType.REFERENCE);
        IrValue value = new IrValue("%value", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Monitor",
                "read",
                "(Ljava/lang/Object;)I",
                IrType.I32,
                List.of(monitor),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.operation(
                                        java.util.Optional.empty(),
                                        IrOpcode.MONITOR_ENTER,
                                        List.of(monitor),
                                        "monitor"),
                                IrInstruction.constInt(value, 1)),
                        IrTerminator.returnValue(value))));

        var result = new ProtectionPipeline(List.of(new BasicBlockSplittingPass()))
                .runDetailed(method, splitAndFakeConfig(true, false));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("BASIC_BLOCK_SPLITTING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("PROTECTION_MONITOR_SENSITIVE_SKIP")));
    }

    @Test
    void basicBlockSplittingStillSkipsDangerousClassInitAdjacency() {
        IrValue token = new IrValue("%token", IrType.I64);
        IrValue classObject = new IrValue("%class", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/ClassInit",
                "touch",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constLong(token, 7L),
                                IrInstruction.operation(
                                        java.util.Optional.of(classObject),
                                        IrOpcode.CLASS_OBJECT,
                                        List.of(token),
                                        "class:Lpkg/ClassInit;")),
                        IrTerminator.returnVoid())));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());

        var result = new ProtectionPipeline(List.of(new BasicBlockSplittingPass()))
                .runDetailed(method, splitAndFakeConfig(true, false));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("BASIC_BLOCK_SPLITTING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("PROTECTION_CFG_SHAPE_NOT_SUPPORTED")));
    }

    @Test
    void basicBlockSplittingStillSkipsExceptionHandlerBlock() {
        IrValue resultValue = new IrValue("%result", IrType.I32);
        IrValue pending = new IrValue("%pending", IrType.REFERENCE);
        IrValue caught = new IrValue("%caught", IrType.REFERENCE);
        IrValue one = new IrValue("%one", IrType.I32);
        IrValue two = new IrValue("%two", IrType.I32);
        IrExceptionEdge handlerEdge =
                new IrExceptionEdge("handler", "java/lang/RuntimeException", List.of(pending));
        IrInstruction helperCall = IrInstruction.call(
                        java.util.Optional.of(resultValue),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(),
                        "j2ll_rt_int_helper")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(handlerEdge),
                        java.util.Optional.of(pending)));
        IrMethod method = new IrMethod(
                "pkg/Handler",
                "run",
                "()I",
                IrType.I32,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(helperCall),
                                IrTerminator.returnValue(resultValue)),
                        new IrBlock(
                                "handler",
                                List.of(caught),
                                List.of("java/lang/RuntimeException"),
                                List.of(
                                        IrInstruction.constInt(one, 1),
                                        IrInstruction.constInt(two, 2)),
                                IrTerminator.returnValue(two))));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());

        var result = new ProtectionPipeline(List.of(new BasicBlockSplittingPass()))
                .runDetailed(method, splitAndFakeConfig(true, false));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("BASIC_BLOCK_SPLITTING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("PROTECTION_CFG_SHAPE_NOT_SUPPORTED")));
    }

    @Test
    void fakeBranchesOnlyAddsDetourWhenEnabledAlone() {
        IrMethod method = splittableMethod();

        var result = splitAndFakePipeline().runDetailed(method, splitAndFakeConfig(false, true));

        assertEquals(3, result.method().blocks().size());
        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.terminator().kind() == xyz.melodysky.ir.model.IrTerminatorKind.BRANCH));
        assertTrue(result.method().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.XOR_I32));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("BASIC_BLOCK_SPLITTING")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("PROTECTION_PASS_DISABLED")));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("FAKE_BRANCHES")
                && report.status().equals("RAN")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void fakeBranchesPreservesHelperFieldReferenceAndProtectedExceptionBody() {
        IrValue receiver = new IrValue("%p0", IrType.REFERENCE);
        IrValue fieldValue = new IrValue("%field", IrType.REFERENCE);
        IrValue helperValue = new IrValue("%helper", IrType.REFERENCE);
        IrValue callValue = new IrValue("%call", IrType.REFERENCE);
        IrValue fieldException = new IrValue("%fieldException", IrType.REFERENCE);
        IrValue helperException = new IrValue("%helperException", IrType.REFERENCE);
        IrValue callException = new IrValue("%callException", IrType.REFERENCE);
        IrValue caught = new IrValue("%caught", IrType.REFERENCE);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrExceptionEdge fieldHandler =
                new IrExceptionEdge("handler", "java/lang/RuntimeException", List.of(fieldException));
        IrExceptionEdge helperHandler =
                new IrExceptionEdge("handler", "java/lang/RuntimeException", List.of(helperException));
        IrExceptionEdge callHandler =
                new IrExceptionEdge("handler", "java/lang/RuntimeException", List.of(callException));
        IrInstruction fieldGet = IrInstruction.fieldGet(
                        fieldValue,
                        IrOpcode.GET_FIELD,
                        List.of(receiver),
                        "pkg/Protected#value!Ljava/lang/Object;")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(fieldHandler),
                        java.util.Optional.of(fieldException)));
        IrInstruction helperCall = IrInstruction.call(
                        java.util.Optional.of(helperValue),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(fieldValue),
                        "j2ll_rt_reference_helper")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(helperHandler),
                        java.util.Optional.of(helperException)));
        IrInstruction javaCall = IrInstruction.call(
                        java.util.Optional.of(callValue),
                        IrOpcode.CALL_VIRTUAL,
                        List.of(helperValue),
                        "java/lang/Object#toString!()Ljava/lang/String;")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(callHandler),
                        java.util.Optional.of(callException)));
        IrExceptionEdge explicitThrowHandler =
                new IrExceptionEdge("handler", "java/lang/RuntimeException", List.of(receiver));
        IrMethod method = new IrMethod(
                "pkg/Protected",
                "run",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                IrType.REFERENCE,
                List.of(receiver),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        fieldGet,
                                        helperCall,
                                        javaCall,
                                        IrInstruction.binary(
                                                condition,
                                                IrOpcode.CMP_EQ_REF,
                                                fieldValue,
                                                receiver)),
                                IrTerminator.branch(condition, "normal", "thrower")),
                        new IrBlock("normal", List.of(), IrTerminator.returnValue(callValue)),
                        new IrBlock(
                                "thrower",
                                List.of(),
                                List.of(),
                                List.of(explicitThrowHandler),
                                List.of(),
                                IrTerminator.throwValue(receiver)),
                        new IrBlock(
                                "handler",
                                List.of(caught),
                                List.of("java/lang/RuntimeException"),
                                List.of(),
                                IrTerminator.returnValue(caught))));
        List<IrBlock> originalBlocks = method.blocks();
        assertTrue(new IrMethodValidator().validate(method).isEmpty());

        var result = new ProtectionPipeline(List.of(new FakeBranchesPass()))
                .runDetailed(method, splitAndFakeConfig(false, true));

        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("FAKE_BRANCHES")
                && report.status().equals("RAN")));
        assertEquals(originalBlocks, result.method().blocks().subList(2, result.method().blocks().size()));
        for (int index = 0; index < originalBlocks.size(); index++) {
            assertTrue(originalBlocks.get(index) == result.method().blocks().get(index + 2));
        }
        assertEquals(fieldGet.exceptionSites(), result.method().blocks().get(2).instructions().get(0).exceptionSites());
        assertEquals(helperCall.exceptionSites(), result.method().blocks().get(2).instructions().get(1).exceptionSites());
        assertEquals(javaCall.exceptionSites(), result.method().blocks().get(2).instructions().get(2).exceptionSites());
        assertEquals(
                List.of(explicitThrowHandler),
                result.method().blocks().get(4).exceptionEdges());
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void fakeBranchesStillSkipsJmmSensitiveBody() {
        IrValue value = new IrValue("%value", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Jmm",
                "read",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.operation(
                                        java.util.Optional.empty(),
                                        IrOpcode.VOLATILE_READ_BARRIER,
                                        List.of(),
                                        "volatile"),
                                IrInstruction.constInt(value, 1)),
                        IrTerminator.returnValue(value))));

        var result = new ProtectionPipeline(List.of(new FakeBranchesPass()))
                .runDetailed(method, splitAndFakeConfig(false, true));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("FAKE_BRANCHES")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("PROTECTION_CFG_SHAPE_NOT_SUPPORTED")));
    }

    @Test
    void fakeBranchesStillSkipsMonitorSensitiveBody() {
        IrValue monitor = new IrValue("%p0", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Monitor",
                "run",
                "(Ljava/lang/Object;)V",
                IrType.VOID,
                List.of(monitor),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.operation(
                                java.util.Optional.empty(),
                                IrOpcode.MONITOR_ENTER,
                                List.of(monitor),
                                "monitor")),
                        IrTerminator.returnVoid())));

        var result = new ProtectionPipeline(List.of(new FakeBranchesPass()))
                .runDetailed(method, splitAndFakeConfig(false, true));

        assertEquals(method.blocks(), result.method().blocks());
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("FAKE_BRANCHES")
                && report.status().equals("SKIPPED")
                && report.reasonCode().equals("PROTECTION_MONITOR_SENSITIVE_SKIP")));
    }

    @Test
    void fakeBranchesStillSkipsInitializerBodies() {
        for (String name : List.of("<init>", "<clinit>")) {
            IrMethod method = new IrMethod(
                    "pkg/Initializer",
                    name,
                    "()V",
                    IrType.VOID,
                    List.of(),
                    List.of(new IrBlock("entry", List.of(), IrTerminator.returnVoid())));

            var result = new ProtectionPipeline(List.of(new FakeBranchesPass()))
                    .runDetailed(method, splitAndFakeConfig(false, true));

            assertEquals(method.blocks(), result.method().blocks());
            assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("FAKE_BRANCHES")
                    && report.status().equals("SKIPPED")
                    && report.reasonCode().equals("PROTECTION_STUB_BACKED_METHOD")));
        }
    }

    @Test
    void basicBlockSplittingAndFakeBranchesCanBothRunOnOneMethod() {
        IrMethod method = splittableMethod();

        var result = splitAndFakePipeline().runDetailed(method, splitAndFakeConfig(true, true));

        assertEquals(4, result.method().blocks().size());
        assertTrue(result.method().blocks().stream().anyMatch(block -> block.name().startsWith("split_")));
        assertTrue(result.method().blocks().stream().anyMatch(block -> block.name().startsWith("fake_detour_")));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("BASIC_BLOCK_SPLITTING")
                && report.status().equals("RAN")));
        assertTrue(result.reports().stream().anyMatch(report -> report.passName().equals("FAKE_BRANCHES")
                && report.status().equals("RAN")));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    private ProtectionPipeline splitAndFakePipeline() {
        return new ProtectionPipeline(List.of(new BasicBlockSplittingPass(), new FakeBranchesPass()));
    }

    private ProtectionConfig splitAndFakeConfig(boolean split, boolean fake) {
        return new ProtectionConfig(true, 17, false, false, false, split, fake, false);
    }

    private IrMethod splittableMethod() {
        IrValue input = new IrValue("%p0", IrType.I32);
        IrValue one = new IrValue("%one", IrType.I32);
        IrValue result = new IrValue("%result", IrType.I32);
        return new IrMethod(
                "pkg/Sample",
                "calculate",
                "(I)I",
                IrType.I32,
                List.of(input),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(one, 1),
                                IrInstruction.binary(result, IrOpcode.ADD_I32, input, one)),
                        IrTerminator.returnValue(result))));
    }

    private IrMethod method() {
        return new IrMethod(
                "pkg/Sample",
                "run",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock("entry", List.<IrInstruction>of(), IrTerminator.returnVoid())));
    }

    private IrMethod branchingMethod(boolean helperSensitive) {
        IrValue input = new IrValue("%p0", IrType.I32);
        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue left = new IrValue("%left", IrType.I32);
        IrValue right = new IrValue("%right", IrType.I32);
        List<IrInstruction> entryInstructions = new java.util.ArrayList<>();
        if (helperSensitive) {
            entryInstructions.add(IrInstruction.call(
                    java.util.Optional.empty(),
                    IrOpcode.CALL_RUNTIME_HELPER,
                    List.of(),
                    "j2ll_rt_sensitive"));
        }
        entryInstructions.add(IrInstruction.constInt(zero, 0));
        entryInstructions.add(IrInstruction.binary(condition, IrOpcode.CMP_GE_I32, input, zero));
        return new IrMethod(
                "pkg/Branches",
                "choose",
                "(I)I",
                IrType.I32,
                List.of(input),
                List.of(
                        new IrBlock(
                                "entry",
                                entryInstructions,
                                IrTerminator.branch(condition, "left", "right")),
                        new IrBlock(
                                "left",
                                List.of(IrInstruction.constInt(left, 1)),
                                IrTerminator.returnValue(left)),
                        new IrBlock(
                                "right",
                                List.of(IrInstruction.constInt(right, -1)),
                                IrTerminator.returnValue(right))));
    }

    private Map<String, Integer> flattenedStates(IrMethod method) {
        IrBlock dispatcher = method.blocks().stream()
                .filter(block -> block.terminator().kind()
                        == IrTerminatorKind.SWITCH)
                .findFirst()
                .orElseThrow();
        String token = dispatcher.name().substring(
                "cff_dispatch_".length());
        String bodyPrefix = "cff_body_" + token + "_";
        LinkedHashMap<String, Integer> states = new LinkedHashMap<>();
        dispatcher.terminator().switchCases().forEach(switchCase ->
                states.put(
                        switchCase.target().substring(bodyPrefix.length()),
                        switchCase.key()));
        Set<Integer> assigned = new HashSet<>(states.values());
        int defaultState = IntStream.range(
                        0,
                        dispatcher.terminator().switchCases().size() + 1)
                .filter(state -> !assigned.contains(state))
                .findFirst()
                .orElseThrow();
        String defaultTarget =
                dispatcher.terminator().defaultTarget().orElseThrow();
        states.put(
                defaultTarget.substring(bodyPrefix.length()),
                defaultState);
        return Map.copyOf(states);
    }
}
