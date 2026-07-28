package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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

class NativeExceptionFlowSupportTest {
    private final NativeExceptionFlowSupport support = new NativeExceptionFlowSupport();

    @Test
    void supportsJvmCallInsideUserProtectedRegionWithCompleteTransferEvidence() {
        IrValue pending = new IrValue("%pending", IrType.REFERENCE);
        IrValue caught = new IrValue("%caught", IrType.REFERENCE);
        IrExceptionEdge edge = new IrExceptionEdge(
                "catch",
                "java/security/NoSuchAlgorithmException",
                List.of(pending));
        IrInstruction call = IrInstruction.call(
                Optional.of(new IrValue("%digest", IrType.REFERENCE)),
                IrOpcode.CALL_STATIC,
                List.of(),
                "java/security/MessageDigest#getInstance!(Ljava/lang/String;)Ljava/security/MessageDigest;")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(edge),
                        Optional.of(pending)));

        assertFalse(support.hasUnsupportedJvmFlow(method(
                List.of(call),
                List.of(edge),
                new IrBlock(
                        "catch",
                        List.of(caught),
                        List.of("java/security/NoSuchAlgorithmException"),
                        List.of(),
                        IrTerminator.returnVoid()))));
    }

    @Test
    void rejectsProtectedJvmCallWhenTransferEvidenceIsIncomplete() {
        IrInstruction call = IrInstruction.call(
                        Optional.empty(),
                        IrOpcode.CALL_STATIC,
                        List.of(),
                        "pkg/Target#run!()V")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(new IrExceptionEdge(
                                "catch",
                                "java/lang/RuntimeException")),
                        Optional.empty()));

        assertTrue(support.hasUnsupportedJvmFlow(method(
                List.of(call),
                List.of(),
                new IrBlock(
                        "catch",
                        List.of(new IrValue("%caught", IrType.REFERENCE)),
                        List.of("java/lang/RuntimeException"),
                        List.of(),
                        IrTerminator.returnVoid()))));
    }

    @Test
    void pureInstructionDoesNotNeedPendingExceptionTransfer() {
        IrInstruction pure = IrInstruction.constInt(new IrValue("%one", IrType.I32), 1);

        assertFalse(support.hasUnsupportedJvmFlow(method(
                List.of(pure),
                List.of(new IrExceptionEdge("catch", "java/lang/RuntimeException")),
                new IrBlock(
                        "catch",
                        List.of(new IrValue("%caught", IrType.REFERENCE)),
                        List.of("java/lang/RuntimeException"),
                        List.of(),
                        IrTerminator.returnVoid()))));
    }

    @Test
    void supportsUnprotectedJvmCallWithPendingExceptionValue() {
        IrValue pending = new IrValue("%pending", IrType.REFERENCE);
        IrInstruction call = IrInstruction.call(
                        Optional.empty(),
                        IrOpcode.CALL_STATIC,
                        List.of(),
                        "pkg/Target#run!()V")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(),
                        Optional.of(pending)));

        assertFalse(support.hasUnsupportedJvmFlow(new IrMethod(
                "pkg/Unprotected",
                "run",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(call),
                        IrTerminator.returnVoid())))));
    }

    @Test
    void rejectsThrowableInstructionWithoutPendingExceptionEvidence() {
        IrInstruction call = IrInstruction.call(
                Optional.empty(),
                IrOpcode.CALL_STATIC,
                List.of(),
                "pkg/Target#run!()V");

        assertTrue(support.hasUnsupportedJvmFlow(new IrMethod(
                "pkg/Unprotected",
                "run",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(call),
                        IrTerminator.returnVoid())))));
    }

    @Test
    void supportsRenamedSyntheticCleanupWithCompleteTransferEvidence() {
        IrValue pending = new IrValue("%pending", IrType.REFERENCE);
        IrExceptionEdge edge = new IrExceptionEdge(
                "obfuscated_cleanup",
                "<any>",
                List.of(pending));
        IrInstruction call = IrInstruction.call(
                        Optional.empty(),
                        IrOpcode.CALL_STATIC,
                        List.of(),
                        "pkg/Target#run!()V")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(edge),
                        Optional.of(pending)));
        IrBlock protectedBlock = new IrBlock(
                "entry",
                List.of(),
                List.of(),
                List.of(edge),
                List.of(call),
                IrTerminator.returnVoid());
        IrValue exception = new IrValue("%exception", IrType.REFERENCE);
        IrValue cleanupPending = new IrValue("%cleanupPending", IrType.REFERENCE);
        IrBlock cleanupBlock = new IrBlock(
                "obfuscated_cleanup",
                List.of(exception),
                List.of("<any>"),
                List.of(),
                List.of(
                        IrInstruction.operation(
                                Optional.empty(),
                                IrOpcode.MONITOR_EXIT_ON_EXCEPTION,
                                List.of(new IrValue("%lock", IrType.REFERENCE)),
                                "monitor")
                        .withExceptionSite(new IrExceptionSite(
                                IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                                List.of(),
                                Optional.of(cleanupPending))),
                        IrInstruction.operation(
                                Optional.empty(),
                                IrOpcode.MONITOR_HAPPENS_BEFORE,
                                List.of(new IrValue("%lock", IrType.REFERENCE)),
                                "monitorExitOnException")),
                IrTerminator.throwValue(exception));

        IrMethod method = new IrMethod(
                "pkg/Protected",
                "run",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(protectedBlock, cleanupBlock));

        assertFalse(support.hasUnsupportedJvmFlow(method));
    }

    @Test
    void supportsTypedUserCatchThatRethrowsWithCompleteTransferEvidence() {
        IrValue pending = new IrValue("%pending", IrType.REFERENCE);
        IrValue exception = new IrValue("%exception", IrType.REFERENCE);
        IrExceptionEdge edge = new IrExceptionEdge(
                "typed_catch",
                "java/lang/RuntimeException",
                List.of(pending));
        IrInstruction call = IrInstruction.call(
                        Optional.empty(),
                        IrOpcode.CALL_STATIC,
                        List.of(),
                        "pkg/Target#run!()V")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(edge),
                        Optional.of(pending)));
        IrBlock protectedBlock = new IrBlock(
                "entry",
                List.of(),
                List.of(),
                List.of(edge),
                List.of(call),
                IrTerminator.returnVoid());
        IrBlock userCatch = new IrBlock(
                "typed_catch",
                List.of(exception),
                List.of("java/lang/RuntimeException"),
                List.of(),
                List.of(
                        IrInstruction.constInt(new IrValue("%marker", IrType.I32), 1),
                        IrInstruction.operation(
                                Optional.empty(),
                                IrOpcode.MONITOR_EXIT_ON_EXCEPTION,
                                List.of(new IrValue("%lock", IrType.REFERENCE)),
                                "monitor")
                        .withExceptionSite(new IrExceptionSite(
                                IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                                List.of(),
                                Optional.of(new IrValue("%cleanupPending", IrType.REFERENCE))))),
                IrTerminator.throwValue(exception));

        assertFalse(support.hasUnsupportedJvmFlow(new IrMethod(
                "pkg/Protected",
                "run",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(protectedBlock, userCatch))));
    }

    private IrMethod method(
            List<IrInstruction> instructions,
            List<IrExceptionEdge> exceptionEdges,
            IrBlock handler) {
        return new IrMethod(
                "pkg/Protected",
                "run",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                List.of(),
                                exceptionEdges,
                                instructions,
                                IrTerminator.returnVoid()),
                        handler));
    }
}
