package xyz.melodysky.analysis.runtime;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.runtime.PureNativeJdkRuntimeHelpers;
import xyz.melodysky.toolchain.NativeMethodImplementation;

/** Maps helper-shaped IR operations to stable evidence names and reason codes. */
final class RuntimeHelperSiteClassifier {
    private static final Set<IrOpcode> REPORTABLE_OPCODES = EnumSet.of(
            IrOpcode.CALL_RUNTIME_HELPER,
            IrOpcode.MONITOR_ENTER,
            IrOpcode.MONITOR_EXIT,
            IrOpcode.MONITOR_EXIT_ON_EXCEPTION,
            IrOpcode.CLASS_INIT_GUARD,
            IrOpcode.CLASS_INIT_BEGIN,
            IrOpcode.CLASS_INIT_END,
            IrOpcode.CLASS_INIT_FAILED,
            IrOpcode.DIV_I32,
            IrOpcode.REM_I32,
            IrOpcode.DIV_I64,
            IrOpcode.REM_I64,
            IrOpcode.I2B,
            IrOpcode.I2C,
            IrOpcode.I2S,
            IrOpcode.F2I,
            IrOpcode.F2L,
            IrOpcode.D2I,
            IrOpcode.D2L,
            IrOpcode.LCMP,
            IrOpcode.FCMPL,
            IrOpcode.FCMPG,
            IrOpcode.DCMPL,
            IrOpcode.DCMPG,
            IrOpcode.VOLATILE_READ_BARRIER,
            IrOpcode.VOLATILE_WRITE_BARRIER,
            IrOpcode.FINAL_FIELD_PUBLICATION,
            IrOpcode.MONITOR_HAPPENS_BEFORE,
            IrOpcode.NEW_OBJECT,
            IrOpcode.NEW_ARRAY,
            IrOpcode.NEW_MULTI_ARRAY,
            IrOpcode.ARRAY_LENGTH,
            IrOpcode.ARRAY_LOAD_I32,
            IrOpcode.ARRAY_LOAD_I64,
            IrOpcode.ARRAY_LOAD_F32,
            IrOpcode.ARRAY_LOAD_F64,
            IrOpcode.ARRAY_LOAD_REF,
            IrOpcode.ARRAY_STORE_I32,
            IrOpcode.ARRAY_STORE_I64,
            IrOpcode.ARRAY_STORE_F32,
            IrOpcode.ARRAY_STORE_F64,
            IrOpcode.ARRAY_STORE_REF,
            IrOpcode.CHECKCAST,
            IrOpcode.INSTANCEOF,
            IrOpcode.GET_STATIC,
            IrOpcode.PUT_STATIC,
            IrOpcode.GET_FIELD,
            IrOpcode.PUT_FIELD,
            IrOpcode.CALL_STATIC,
            IrOpcode.CALL_SPECIAL,
            IrOpcode.CALL_VIRTUAL,
            IrOpcode.CALL_INTERFACE,
            IrOpcode.CALL_DYNAMIC);

    private static final Set<IrOpcode> FIELD_OPCODES = EnumSet.of(
            IrOpcode.GET_STATIC, IrOpcode.PUT_STATIC, IrOpcode.GET_FIELD, IrOpcode.PUT_FIELD);
    private static final Set<IrOpcode> DIV_REM_OPCODES = EnumSet.of(
            IrOpcode.DIV_I32, IrOpcode.REM_I32, IrOpcode.DIV_I64, IrOpcode.REM_I64);
    private static final Set<IrOpcode> NUMERIC_HELPER_OPCODES = EnumSet.of(
            IrOpcode.I2B,
            IrOpcode.I2C,
            IrOpcode.I2S,
            IrOpcode.F2I,
            IrOpcode.F2L,
            IrOpcode.D2I,
            IrOpcode.D2L,
            IrOpcode.LCMP,
            IrOpcode.FCMPL,
            IrOpcode.FCMPG,
            IrOpcode.DCMPL,
            IrOpcode.DCMPG);
    private static final Set<IrOpcode> JMM_OPCODES = EnumSet.of(
            IrOpcode.VOLATILE_READ_BARRIER,
            IrOpcode.VOLATILE_WRITE_BARRIER,
            IrOpcode.FINAL_FIELD_PUBLICATION,
            IrOpcode.MONITOR_HAPPENS_BEFORE);
    private static final Set<IrOpcode> MONITOR_OPCODES = EnumSet.of(
            IrOpcode.MONITOR_ENTER, IrOpcode.MONITOR_EXIT, IrOpcode.MONITOR_EXIT_ON_EXCEPTION);
    private static final Set<IrOpcode> ARRAY_OPCODES = EnumSet.of(
            IrOpcode.ARRAY_LENGTH,
            IrOpcode.ARRAY_LOAD_I32,
            IrOpcode.ARRAY_LOAD_I64,
            IrOpcode.ARRAY_LOAD_F32,
            IrOpcode.ARRAY_LOAD_F64,
            IrOpcode.ARRAY_LOAD_REF,
            IrOpcode.ARRAY_STORE_I32,
            IrOpcode.ARRAY_STORE_I64,
            IrOpcode.ARRAY_STORE_F32,
            IrOpcode.ARRAY_STORE_F64,
            IrOpcode.ARRAY_STORE_REF);
    private static final Set<IrOpcode> ALLOCATION_OPCODES = EnumSet.of(
            IrOpcode.NEW_OBJECT, IrOpcode.NEW_ARRAY, IrOpcode.NEW_MULTI_ARRAY);
    private static final Set<IrOpcode> TYPE_OPCODES = EnumSet.of(IrOpcode.CHECKCAST, IrOpcode.INSTANCEOF);
    private static final Set<IrOpcode> DEFERRED_CALL_OPCODES = EnumSet.of(
            IrOpcode.CALL_VIRTUAL, IrOpcode.CALL_INTERFACE, IrOpcode.CALL_DYNAMIC);

    private final JvmCallBoundaryClassifier callBoundaries = new JvmCallBoundaryClassifier();

    boolean isReportable(IrInstruction instruction) {
        return REPORTABLE_OPCODES.contains(instruction.opcode());
    }

    RuntimeHelperSite classify(
            IrInstruction instruction,
            Optional<NativeMethodImplementation> implementation) {
        return new RuntimeHelperSite(siteName(instruction, implementation), reasonCode(instruction, implementation));
    }

    private String siteName(
            IrInstruction instruction,
            Optional<NativeMethodImplementation> implementation) {
        IrOpcode opcode = instruction.opcode();
        if (FIELD_OPCODES.contains(opcode)) {
            return "field:" + symbolOrOpcode(instruction);
        }
        if (DIV_REM_OPCODES.contains(opcode)) {
            return "arithmetic:" + opcode.name();
        }
        if (NUMERIC_HELPER_OPCODES.contains(opcode)) {
            return "numeric:" + opcode.name();
        }
        if (JMM_OPCODES.contains(opcode)) {
            return "jmm:" + opcode.name() + ":" + instruction.symbol().orElse("fence");
        }
        if (MONITOR_OPCODES.contains(opcode)) {
            return "monitor:" + opcode.name();
        }
        if (ARRAY_OPCODES.contains(opcode)) {
            return "array:" + opcode.name();
        }
        if (ALLOCATION_OPCODES.contains(opcode)) {
            return "allocation:" + symbolOrOpcode(instruction);
        }
        if (TYPE_OPCODES.contains(opcode)) {
            return "type:" + symbolOrOpcode(instruction);
        }
        if (opcode == IrOpcode.CALL_STATIC || opcode == IrOpcode.CALL_SPECIAL) {
            String target = symbolOrOpcode(instruction);
            if (isDirectTarget(target, implementation)) {
                return "direct:" + target;
            }
            return "call:" + target;
        }
        if (DEFERRED_CALL_OPCODES.contains(opcode)) {
            return "call:" + symbolOrOpcode(instruction);
        }
        return symbolOrOpcode(instruction);
    }

    private String reasonCode(
            IrInstruction instruction,
            Optional<NativeMethodImplementation> implementation) {
        IrOpcode opcode = instruction.opcode();
        if (FIELD_OPCODES.contains(opcode)) {
            return "FIELD_HELPER";
        }
        if (DIV_REM_OPCODES.contains(opcode)) {
            return "DIV_REM_EXCEPTION_HELPER";
        }
        if (NUMERIC_HELPER_OPCODES.contains(opcode)) {
            return "JVM_NUMERIC_HELPER";
        }
        if (JMM_OPCODES.contains(opcode)) {
            return "JMM_FENCE";
        }
        if (MONITOR_OPCODES.contains(opcode)) {
            return "MONITOR_HELPER";
        }
        if (ARRAY_OPCODES.contains(opcode)) {
            return "ARRAY_HELPER";
        }
        if (opcode == IrOpcode.NEW_OBJECT || opcode == IrOpcode.NEW_ARRAY) {
            return "ALLOCATION_HELPER";
        }
        if (opcode == IrOpcode.NEW_MULTI_ARRAY) {
            return "UNSUPPORTED_MULTI_ARRAY_ALLOCATION";
        }
        if (TYPE_OPCODES.contains(opcode)) {
            return "TYPE_HELPER";
        }
        if (opcode == IrOpcode.CALL_STATIC || opcode == IrOpcode.CALL_SPECIAL) {
            String target = instruction.symbol().orElse("");
            if (isDirectTarget(target, implementation)) {
                return "DIRECT_LLVM_CALL";
            }
            String boundaryReason = callBoundaryReason(target);
            if (boundaryReason != null) {
                return boundaryReason;
            }
            if (opcode == IrOpcode.CALL_SPECIAL && target.contains("#<init>!")) {
                return "CONSTRUCTOR_CALL_HELPER";
            }
            return "JVM_CALL_HELPER";
        }
        if (DEFERRED_CALL_OPCODES.contains(opcode)) {
            String boundaryReason = callBoundaryReason(instruction.symbol().orElse(""));
            return boundaryReason == null ? "DEFERRED_DISPATCH_HELPER" : boundaryReason;
        }
        return runtimeHelperReason(instruction.symbol().orElse(opcode.name()));
    }

    private boolean isDirectTarget(
            String target,
            Optional<NativeMethodImplementation> implementation) {
        return implementation.map(item -> item.directCallTargets().contains(target)).orElse(false);
    }

    private String callBoundaryReason(String target) {
        if (callBoundaries.isJdkCollectionCall(target)) {
            return "JDK_COLLECTION_HELPER";
        }
        if (callBoundaries.isThrowableCall(target)) {
            return "THROWABLE_HELPER";
        }
        if (callBoundaries.isThreadCall(target)) {
            return "THREAD_HELPER";
        }
        if (callBoundaries.isWaitNotifyCall(target)) {
            return "WAIT_NOTIFY_UNSUPPORTED";
        }
        return null;
    }

    private String runtimeHelperReason(String helper) {
        if (PureNativeJdkRuntimeHelpers
                .isI32BigEndianFrameHelper(helper)) {
            return "JDK_INTRINSIC_HELPER";
        }
        if (helper.startsWith("j2ll_rt_var_handle_")) {
            return "VARHANDLE_HELPER";
        }
        if (helper.startsWith("j2ll_rt_string_constant")) {
            return "STRING_CONCAT_CONSTANTS_HELPER";
        }
        if (helper.startsWith("j2ll_rt_lambda_new")) {
            return "LAMBDA_METAFACTORY_HELPER";
        }
        if (helper.startsWith("j2ll_rt_unsafe_")) {
            return "UNSAFE_HELPER";
        }
        if (helper.equals("j2ll_rt_string_length")
                || helper.equals("j2ll_rt_string_is_empty")
                || helper.equals("j2ll_rt_string_char_at")
                || helper.equals("j2ll_rt_string_equals")
                || helper.equals("j2ll_rt_string_starts_with")
                || helper.equals("j2ll_rt_string_ends_with")
                || helper.equals("j2ll_rt_string_substring")
                || helper.equals("j2ll_rt_string_substring_range")) {
            return "STRING_HELPER";
        }
        if (helper.startsWith("j2ll_rt_string_builder_")) {
            return "STRING_BUILDER_HELPER";
        }
        if (helper.equals("j2ll_rt_system_arraycopy")) {
            return "ARRAYCOPY_HELPER";
        }
        if (helper.equals("j2ll_rt_thread_sleep")) {
            return "THREAD_HELPER";
        }
        if (helper.equals("j2ll_rt_object_get_class")
                || helper.equals("j2ll_rt_class_get_class_loader")) {
            return "JDK_INTRINSIC_HELPER";
        }
        if (helper.equals("j2ll_rt_class_for_name_static")
                || helper.startsWith("j2ll_rt_get_declared_field")) {
            return "REFLECTION_HELPER";
        }
        if (helper.startsWith("j2ll_rt_reflect_field_")) {
            return "REFLECTION_FIELD_HELPER";
        }
        if (helper.startsWith("j2ll_rt_get_declared_method")
                || helper.startsWith("j2ll_rt_reflect_invoke")) {
            return "REFLECTION_METHOD_HELPER";
        }
        if (helper.startsWith("j2ll_rt_get_declared_constructor")
                || helper.startsWith("j2ll_rt_reflect_new_instance")) {
            return "REFLECTION_CONSTRUCTOR_HELPER";
        }
        if (helper.startsWith("j2ll_rt_reflect_set_accessible")) {
            return "REFLECTION_ACCESSIBLE_HELPER";
        }
        if (helper.startsWith("j2ll_rt_math_")
                || helper.startsWith("j2ll_rt_integer_")
                || helper.startsWith("j2ll_rt_long_")
                || helper.startsWith("j2ll_rt_boolean_")
                || helper.startsWith("j2ll_rt_double_")
                || helper.startsWith("j2ll_rt_objects_")) {
            return "JDK_INTRINSIC_HELPER";
        }
        if (helper.startsWith("j2ll_rt_class_")
                || helper.startsWith("j2ll_rt_method_handle_")
                || helper.startsWith("j2ll_rt_constant_dynamic")) {
            return "RUNTIME_METADATA_HELPER";
        }
        return "HELPER_BACKED_LOWERING";
    }

    private String symbolOrOpcode(IrInstruction instruction) {
        return instruction.symbol().orElse(instruction.opcode().name());
    }
}
