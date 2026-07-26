package xyz.melodysky.toolchain;

import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminatorKind;

/**
 * Describes the exception-flow boundary of the current LLVM/JNI lowering path.
 *
 * <p>Explicit {@code athrow} terminators can target an IR exception edge. JNI
 * and runtime-helper calls cannot yet transfer a pending JVM exception to an
 * in-method catch handler, so protected instructions that can raise a JVM
 * exception must stay on the bytecode-preserving fallback path.
 */
public final class NativeExceptionFlowSupport {
    private static final String SYNCHRONIZED_CLEANUP_BLOCK = "$sync_cleanup";
    private static final String CLASS_INITIALIZATION_FAILED_BLOCK = "$class_init_failed";

    public boolean hasUnsupportedProtectedJvmFlow(IrMethod method) {
        return method.blocks().stream()
                .filter(block -> hasUserExceptionEdge(method, block))
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::canRaiseJvmException);
    }

    private boolean hasUserExceptionEdge(IrMethod method, IrBlock block) {
        return block.exceptionEdges().stream()
                .anyMatch(edge -> !isSyntheticUnwindTarget(method, edge.target()));
    }

    private boolean isSyntheticUnwindTarget(IrMethod method, String target) {
        if (target.equals(SYNCHRONIZED_CLEANUP_BLOCK)
                || target.equals(CLASS_INITIALIZATION_FAILED_BLOCK)) {
            return true;
        }
        return method.blocks().stream()
                .filter(block -> block.name().equals(target))
                .anyMatch(this::isSyntheticUnwindBlock);
    }

    private boolean isSyntheticUnwindBlock(IrBlock block) {
        if (block.terminator().kind() != IrTerminatorKind.THROW
                || !block.exceptionCatchTypes().equals(java.util.List.of("<any>"))) {
            return false;
        }
        java.util.Set<IrOpcode> opcodes = block.instructions().stream()
                .map(IrInstruction::opcode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean synchronizedCleanup = opcodes.contains(IrOpcode.MONITOR_EXIT_ON_EXCEPTION)
                && opcodes.stream().allMatch(opcode ->
                        opcode == IrOpcode.MONITOR_EXIT_ON_EXCEPTION
                                || opcode == IrOpcode.MONITOR_HAPPENS_BEFORE);
        boolean classInitializationCleanup = opcodes.contains(IrOpcode.CLASS_INIT_FAILED)
                && opcodes.stream().allMatch(opcode ->
                        opcode == IrOpcode.CLASS_INIT_FAILED
                                || opcode == IrOpcode.CLASS_INIT_HAPPENS_BEFORE);
        return synchronizedCleanup || classInitializationCleanup;
    }

    private boolean canRaiseJvmException(IrInstruction instruction) {
        IrOpcode opcode = instruction.opcode();
        return switch (opcode) {
            case CONST_STRING, CONST_CLASS, CONST_METHOD_TYPE, CONST_METHOD_HANDLE,
                    CLASS_OBJECT, CLASS_INIT_GUARD, CLASS_INIT_BEGIN, CLASS_INIT_END, CLASS_INIT_FAILED,
                    DIV_I32, REM_I32, DIV_I64, REM_I64,
                    NEW_OBJECT, NEW_ARRAY, NEW_MULTI_ARRAY,
                    ARRAY_LENGTH, ARRAY_LOAD_I32, ARRAY_LOAD_I64, ARRAY_LOAD_F32, ARRAY_LOAD_F64,
                    ARRAY_LOAD_REF, ARRAY_STORE_I32, ARRAY_STORE_I64, ARRAY_STORE_F32,
                    ARRAY_STORE_F64, ARRAY_STORE_REF,
                    CHECKCAST, INSTANCEOF,
                    GET_STATIC, PUT_STATIC, GET_NATIVE_STATIC, PUT_NATIVE_STATIC, GET_FIELD, PUT_FIELD,
                    CALL_STATIC, CALL_SPECIAL, CALL_VIRTUAL, CALL_INTERFACE, CALL_DYNAMIC,
                    CALL_RUNTIME_HELPER,
                    MONITOR_ENTER, MONITOR_EXIT, MONITOR_EXIT_ON_EXCEPTION -> true;
            default -> false;
        };
    }
}
