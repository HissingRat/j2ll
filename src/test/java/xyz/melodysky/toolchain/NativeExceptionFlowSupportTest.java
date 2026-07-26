package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

class NativeExceptionFlowSupportTest {
    private final NativeExceptionFlowSupport support = new NativeExceptionFlowSupport();

    @Test
    void rejectsJvmCallInsideUserProtectedRegion() {
        IrInstruction call = IrInstruction.call(
                Optional.of(new IrValue("%digest", IrType.REFERENCE)),
                IrOpcode.CALL_STATIC,
                List.of(),
                "java/security/MessageDigest#getInstance!(Ljava/lang/String;)Ljava/security/MessageDigest;");

        assertTrue(support.hasUnsupportedProtectedJvmFlow(method(
                List.of(call),
                List.of(new IrExceptionEdge("catch", "java/security/NoSuchAlgorithmException")))));
    }

    @Test
    void keepsExplicitThrowFlowAndSyntheticCleanupOutsideBoundary() {
        IrInstruction pure = IrInstruction.constInt(new IrValue("%one", IrType.I32), 1);
        IrInstruction call = IrInstruction.call(
                Optional.empty(),
                IrOpcode.CALL_STATIC,
                List.of(),
                "pkg/Target#run!()V");

        assertFalse(support.hasUnsupportedProtectedJvmFlow(method(
                List.of(pure),
                List.of(new IrExceptionEdge("catch", "java/lang/RuntimeException")))));
        assertFalse(support.hasUnsupportedProtectedJvmFlow(method(
                List.of(call),
                List.of(new IrExceptionEdge("$sync_cleanup", "<any>")))));
    }

    @Test
    void recognizesRenamedSyntheticCleanupByItsSemanticInstruction() {
        IrInstruction call = IrInstruction.call(
                Optional.empty(),
                IrOpcode.CALL_STATIC,
                List.of(),
                "pkg/Target#run!()V");
        IrBlock protectedBlock = new IrBlock(
                "entry",
                List.of(),
                List.of(),
                List.of(new IrExceptionEdge("obfuscated_cleanup", "<any>")),
                List.of(call),
                IrTerminator.returnVoid());
        IrValue exception = new IrValue("%exception", IrType.REFERENCE);
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
                                "monitor"),
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

        assertFalse(support.hasUnsupportedProtectedJvmFlow(method));
    }

    @Test
    void doesNotMistakeUserCatchThatRethrowsFromSynchronizedMethodForCleanupBlock() {
        IrInstruction call = IrInstruction.call(
                Optional.empty(),
                IrOpcode.CALL_STATIC,
                List.of(),
                "pkg/Target#run!()V");
        IrValue exception = new IrValue("%exception", IrType.REFERENCE);
        IrBlock protectedBlock = new IrBlock(
                "entry",
                List.of(),
                List.of(),
                List.of(new IrExceptionEdge("typed_catch", "java/lang/RuntimeException")),
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
                                "monitor")),
                IrTerminator.throwValue(exception));

        assertTrue(support.hasUnsupportedProtectedJvmFlow(new IrMethod(
                "pkg/Protected",
                "run",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(protectedBlock, userCatch))));
    }

    private IrMethod method(
            List<IrInstruction> instructions,
            List<IrExceptionEdge> exceptionEdges) {
        return new IrMethod(
                "pkg/Protected",
                "run",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(),
                        List.of(),
                        exceptionEdges,
                        instructions,
                        IrTerminator.returnVoid())));
    }
}
