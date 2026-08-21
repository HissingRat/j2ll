package xyz.melodysky.ir.ssa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.pass.OptimizationPipeline;
import xyz.melodysky.ir.pass.PassContext;
import xyz.melodysky.ir.pass.protection.ProtectionConfig;
import xyz.melodysky.ir.pass.protection.ProtectionPipeline;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.pipeline.LoweringStatus;
import xyz.melodysky.testsupport.ProtectedExceptionFlowFixture;

class ProtectedJvmExceptionFlowSsaTest {
    @TempDir
    Path temp;

    private ParsedClass fixtureClass;

    @BeforeEach
    void compileFixture() throws Exception {
        Path jar = ProtectedExceptionFlowFixture.compileJar(temp);
        byte[] bytes = ProtectedExceptionFlowFixture.classBytes(
                jar,
                ProtectedExceptionFlowFixture.OPS_INTERNAL_NAME);
        fixtureClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        ProtectedExceptionFlowFixture.OPS_INTERNAL_NAME + ".class",
                        bytes,
                        "protected-exception-flow-fixture"))
                .artifact()
                .orElseThrow();
    }

    @Test
    void carriesThrowSiteLocalIntoOrderedTypedCatchHandlers() {
        IrMethod method = lowerNative("typedAndContinue");
        IrExceptionSite protectedSite = pendingSites(method).stream()
                .filter(site -> site.handlers().size() == 2)
                .findFirst()
                .orElseThrow();

        assertEquals(
                List.of(
                        "java/lang/NullPointerException",
                        "java/lang/IndexOutOfBoundsException"),
                protectedSite.handlers().stream()
                        .map(IrExceptionEdge::catchType)
                        .toList());
        assertTrue(protectedSite.exceptionValue().isPresent());
        assertEquals(IrType.REFERENCE, protectedSite.exceptionValue().orElseThrow().type());
        for (IrExceptionEdge edge : protectedSite.handlers()) {
            assertEquals(
                    List.of(IrType.REFERENCE, IrType.I32),
                    edge.arguments().stream().map(value -> value.type()).toList(),
                    edge.catchType());
            IrBlock handler = block(method, edge.target());
            assertEquals(
                    edge.arguments().stream().map(value -> value.type()).toList(),
                    handler.parameters().stream().map(value -> value.type()).toList(),
                    edge.catchType());
            assertTrue(handler.exceptionCatchTypes().contains(edge.catchType()));
        }
    }

    @Test
    void typedCatchCanContinueThroughJoinAndReturn() {
        IrMethod method = lowerNative("typedAndContinue");

        assertTrue(method.blocks().stream()
                .filter(block -> !block.exceptionCatchTypes().isEmpty())
                .anyMatch(block -> block.terminator().kind() == IrTerminatorKind.GOTO));
        assertTrue(method.blocks().stream()
                .anyMatch(block -> block.terminator().kind() == IrTerminatorKind.RETURN
                        && block.terminator().value().isPresent()));
        assertTrue(method.blocks().stream()
                .filter(block -> block.parameters().stream()
                        .anyMatch(parameter -> parameter.type() == IrType.I32))
                .anyMatch(block -> block.exceptionCatchTypes().isEmpty()),
                "the normal/catch result must merge before the final return");
    }

    @Test
    void catchAllFinallyCarriesThrowSiteLocalAndRethrows() {
        IrMethod method = lowerNative("finallyAndRethrow");
        IrExceptionEdge catchAll = pendingSites(method).stream()
                .flatMap(site -> site.handlers().stream())
                .filter(edge -> edge.catchType().equals("<any>"))
                .findFirst()
                .orElseThrow();

        assertEquals(
                List.of(IrType.REFERENCE, IrType.I32),
                catchAll.arguments().stream().map(value -> value.type()).toList());
        IrBlock handler = block(method, catchAll.target());
        assertTrue(handler.exceptionCatchTypes().contains("<any>"));
        IrBlock cleanup = handler.terminator().kind() == IrTerminatorKind.GOTO
                ? block(method, handler.terminator().target().orElseThrow())
                : handler;
        assertEquals(IrTerminatorKind.THROW, cleanup.terminator().kind());
        assertEquals(
                catchAll.arguments().stream().map(value -> value.type()).toList(),
                handler.parameters().stream().map(value -> value.type()).toList());
    }

    @Test
    void keepsHandlerLocalLiveBeforeALaterStoreInTheSameProtectedBlock() {
        IrMethod method = lowerNative("lateStoreInProtectedBlock");

        IrExceptionSite firstProtectedSite = pendingSites(method).stream()
                .findFirst()
                .orElseThrow();
        IrExceptionEdge catchEdge = firstProtectedSite.handlers().get(0);
        assertEquals(
                List.of(IrType.REFERENCE, IrType.I32, IrType.REFERENCE),
                catchEdge.arguments().stream().map(IrValue::type).toList());
        assertTrue(new IrMethodValidator().validate(method).isEmpty());
    }

    @Test
    void recordsPendingExceptionEvidenceForEveryUnprotectedThrowableInstruction() {
        IrMethod method = lowerNative("unprotectedLengthAndMarker");
        JvmExceptionInstructionSemantics semantics = new JvmExceptionInstructionSemantics();
        var throwableInstructions = method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(semantics::canRaiseJvmException)
                .toList();

        assertFalse(throwableInstructions.isEmpty());
        for (var instruction : throwableInstructions) {
            assertFalse(instruction.exceptionSites().isEmpty(), instruction.toString());
            assertTrue(
                    instruction.exceptionSites().stream()
                            .allMatch(site -> site.handlers().isEmpty()
                                    && site.exceptionValue().isPresent()
                                    && site.exceptionValue().orElseThrow().type() == IrType.REFERENCE),
                    instruction.toString());
        }
        assertTrue(new IrMethodValidator().validate(method).isEmpty());
    }

    @Test
    void everyProtectedFixtureMethodProducesValidIrWithoutFrontendSkip() {
        for (String methodName : List.of(
                "typedAndContinue",
                "catchAllAndContinue",
                "typedOrRethrow",
                "finallyAndRethrow",
                "lateStoreInProtectedBlock")) {
            IrMethod method = lowerNative(methodName);
            assertFalse(pendingSites(method).isEmpty(), methodName);
            assertTrue(new IrMethodValidator().validate(method).isEmpty(), methodName);
        }
    }

    @Test
    void enabledProtectionPreservesExceptionTransferDefinitionsAndArguments() {
        for (String methodName : List.of(
                "typedAndContinue",
                "catchAllAndContinue",
                "typedOrRethrow",
                "finallyAndRethrow")) {
            IrMethod lowered = lowerNative(methodName);
            var optimized = OptimizationPipeline.defaultPipeline()
                    .run(lowered, PassContext.empty());
            assertFalse(optimized.hasErrors(), methodName + ": " + optimized.diagnostics());
            var protectedResult = ProtectionPipeline.defaultPipeline().runDetailed(
                    optimized.artifact().orElseThrow(),
                    ProtectionConfig.enabled(7));
            assertTrue(
                    protectedResult.diagnostics().stream()
                            .noneMatch(diagnostic -> diagnostic.severity().wireName().equals("error")),
                    methodName + ": " + protectedResult.diagnostics() + "\n" + protectedResult.method());
            assertTrue(
                    new IrMethodValidator().validate(protectedResult.method()).isEmpty(),
                    methodName + ": " + protectedResult.method());
        }
    }

    private IrMethod lowerNative(String methodName) {
        ParsedMethod method = fixtureClass.methods().stream()
                .filter(candidate -> candidate.name().equals(methodName))
                .findFirst()
                .orElseThrow();
        var cfg = new MethodCfgBuilder().build(method).artifact().orElseThrow();
        var stage = xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer().lower(cfg);
        assertFalse(stage.hasErrors(), stage.diagnostics().toString());
        SsaMethodResult result = stage.artifact().orElseThrow();
        assertEquals(LoweringStatus.NATIVE_LOWERED, result.status(), result.reason());
        return result.irMethod().orElseThrow();
    }

    private List<IrExceptionSite> pendingSites(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .flatMap(instruction -> instruction.exceptionSites().stream())
                .filter(site -> site.kind() == IrExceptionSiteKind.JVM_PENDING_EXCEPTION)
                .toList();
    }

    private IrBlock block(IrMethod method, String name) {
        return method.blocks().stream()
                .filter(block -> block.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
