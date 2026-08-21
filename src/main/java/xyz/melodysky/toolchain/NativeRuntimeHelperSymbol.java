package xyz.melodysky.toolchain;

import java.util.Optional;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.runtime.PureNativeJdkRuntimeHelpers;

/** Canonical parsing and JNI-surface classification for runtime-helper symbols. */
final class NativeRuntimeHelperSymbol {
    private NativeRuntimeHelperSymbol() {
    }

    static String base(String symbol) {
        int separator = symbol.indexOf('|');
        return separator < 0 ? symbol : symbol.substring(0, separator);
    }

    static Optional<String> metadataKey(IrInstruction instruction) {
        return instruction.symbol().flatMap(symbol -> {
            int separator = symbol.indexOf('|');
            if (separator < 0 || separator + 1 >= symbol.length()) {
                return Optional.empty();
            }
            return Optional.of(symbol.substring(separator + 1));
        });
    }

    static boolean requiresJniEnv(String symbol) {
        String base = base(symbol);
        return base.equals("j2ll_rt_string_length")
                || base.equals("j2ll_rt_string_equals")
                || base.equals("j2ll_rt_string_is_empty")
                || base.equals("j2ll_rt_string_char_at")
                || base.equals("j2ll_rt_string_starts_with")
                || base.equals("j2ll_rt_string_ends_with")
                || base.equals("j2ll_rt_string_substring")
                || base.equals("j2ll_rt_string_substring_range")
                || base.equals("j2ll_rt_string_constant")
                || base.startsWith("j2ll_rt_string_builder_")
                || base.equals("j2ll_rt_system_arraycopy")
                || base.startsWith("j2ll_rt_integer_")
                || base.startsWith("j2ll_rt_long_")
                || base.startsWith("j2ll_rt_boolean_")
                || base.startsWith("j2ll_rt_double_")
                || base.equals("j2ll_rt_object_get_class")
                || base.equals("j2ll_rt_class_get_class_loader")
                || base.equals("j2ll_rt_is_same_object")
                || base.equals("j2ll_rt_thread_sleep")
                || base.startsWith("j2ll_rt_objects_")
                || base.equals("j2ll_rt_lambda_new")
                || base.equals("j2ll_rt_class_for_name_static")
                || base.equals("j2ll_rt_get_declared_method")
                || base.equals("j2ll_rt_get_declared_field")
                || base.equals("j2ll_rt_get_declared_constructor")
                || base.equals("j2ll_rt_reflect_invoke")
                || base.equals("j2ll_rt_reflect_new_instance")
                || base.equals("j2ll_rt_reflect_set_accessible")
                || base.startsWith("j2ll_rt_reflect_field_")
                || base.startsWith("j2ll_rt_unsafe_")
                || base.startsWith("j2ll_rt_var_handle_")
                || PureNativeJdkRuntimeHelpers.isI32BigEndianFrameHelper(base);
    }
}
