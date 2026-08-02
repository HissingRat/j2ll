package xyz.melodysky.toolchain;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import xyz.melodysky.runtime.PureNativeJdkRuntimeHelpers;

/** Maps one referenced LLVM helper symbol to its host-JNI source roots. */
final class HostJniRuntimeSourceClassifier {
    private static final Pattern BUSINESS_STRING_HELPER = Pattern.compile(
            "j2ll_rt_string_constant_[0-9a-f]{32}");
    private static final Set<String> EXTERNALLY_EMITTED_HELPER_COMMENTS =
            Set.of(
                    "businessStringConstantLocal",
                    "localizedFieldBinding");
    private static final Map<
            String,
            Set<HostJniRuntimeSourceFamily>> STABLE_FAMILIES =
            stableFamilies();

    Classification classify(
            String symbol,
            String declarationComment) {
        if (symbol.startsWith("j2ll_h_")) {
            return classifyLocalized(declarationComment);
        }
        if (!symbol.startsWith("j2ll_rt_")) {
            return Classification.notRuntime();
        }
        if (symbol.startsWith("j2ll_rt_string_constant_")) {
            return BUSINESS_STRING_HELPER.matcher(symbol).matches()
                            && "businessStringConstantLocal"
                                    .equals(declarationComment)
                    ? Classification.known()
                    : Classification.unknownRuntime();
        }
        return classifyStable(symbol);
    }

    private Classification classifyLocalized(String comment) {
        if (comment == null || comment.isBlank()) {
            return Classification.unknownRuntime();
        }
        if (EXTERNALLY_EMITTED_HELPER_COMMENTS.contains(comment)
                || comment.startsWith("native field slot ")) {
            return Classification.known();
        }
        return switch (comment) {
            case "localizedObjectAllocation",
                    "localizedReferenceArrayAllocation",
                    "localizedTypeCheck",
                    "localizedCatchTypeCheck",
                    "localizedClassObject" ->
                    Classification.known(
                            HostJniRuntimeSourceFamily.ALLOCATION);
            /*
             * The localized entry point is emitted by allocation support,
             * while its loader-correct Class.forName implementation lives in
             * the reflection common source.
             */
            case "localizedClassForName" ->
                    Classification.known(
                            HostJniRuntimeSourceFamily.ALLOCATION,
                            HostJniRuntimeSourceFamily.REFLECTION);
            case "localizedReflectionLookup" ->
                    Classification.known(
                            HostJniRuntimeSourceFamily.REFLECTION);
            case "localizedLambdaFactory" ->
                    Classification.known(
                            HostJniRuntimeSourceFamily.LAMBDA);
            case "localizedConstructorDispatch",
                    "localizedStaticDispatch",
                    "localizedVirtualDispatch" ->
                    Classification.known(
                            HostJniRuntimeSourceFamily.DISPATCH);
            default -> Classification.unknownRuntime();
        };
    }

    private Classification classifyStable(String symbol) {
        Set<HostJniRuntimeSourceFamily> families =
                STABLE_FAMILIES.get(symbol);
        return families == null
                ? Classification.unknownRuntime()
                : Classification.known(families);
    }

    private static Map<
            String,
            Set<HostJniRuntimeSourceFamily>> stableFamilies() {
        LinkedHashMap<
                String,
                Set<HostJniRuntimeSourceFamily>> families =
                new LinkedHashMap<>();
        add(
                families,
                HostJniRuntimeSourceFamily.EXCEPTION,
                "j2ll_rt_throw",
                "j2ll_rt_rethrow",
                "j2ll_rt_pending_exception",
                "j2ll_rt_clear_exception");
        add(
                families,
                HostJniRuntimeSourceFamily.CLASS_INIT,
                "j2ll_rt_class_init_guard",
                "j2ll_rt_class_init_begin",
                "j2ll_rt_class_init_end",
                "j2ll_rt_class_init_failed");
        add(
                families,
                HostJniRuntimeSourceFamily.ARITHMETIC,
                "j2ll_rt_div_i32",
                "j2ll_rt_rem_i32",
                "j2ll_rt_div_i64",
                "j2ll_rt_rem_i64");
        add(
                families,
                HostJniRuntimeSourceFamily.NUMERIC,
                "j2ll_rt_i2b",
                "j2ll_rt_i2c",
                "j2ll_rt_i2s",
                "j2ll_rt_f2i",
                "j2ll_rt_f2l",
                "j2ll_rt_d2i",
                "j2ll_rt_d2l",
                "j2ll_rt_lcmp",
                "j2ll_rt_fcmpl",
                "j2ll_rt_fcmpg",
                "j2ll_rt_dcmpl",
                "j2ll_rt_dcmpg");
        add(
                families,
                HostJniRuntimeSourceFamily.MATH,
                "j2ll_rt_math_abs_i32",
                "j2ll_rt_math_abs_i64",
                "j2ll_rt_math_abs_f32",
                "j2ll_rt_math_abs_f64",
                "j2ll_rt_math_min_i32",
                "j2ll_rt_math_min_i64",
                "j2ll_rt_math_min_f32",
                "j2ll_rt_math_min_f64",
                "j2ll_rt_math_max_i32",
                "j2ll_rt_math_max_i64",
                "j2ll_rt_math_max_f32",
                "j2ll_rt_math_max_f64");
        add(
                families,
                HostJniRuntimeSourceFamily.JDK_OBJECT,
                "j2ll_rt_object_get_class",
                "j2ll_rt_class_get_class_loader",
                "j2ll_rt_integer_value_of",
                "j2ll_rt_integer_int_value",
                "j2ll_rt_long_value_of",
                "j2ll_rt_long_long_value",
                "j2ll_rt_boolean_value_of",
                "j2ll_rt_boolean_boolean_value",
                "j2ll_rt_double_value_of",
                "j2ll_rt_double_double_value",
                "j2ll_rt_objects_require_non_null",
                "j2ll_rt_objects_equals");
        add(
                families,
                HostJniRuntimeSourceFamily.PURE_NATIVE_JDK,
                PureNativeJdkRuntimeHelpers.I32_BIG_ENDIAN_FRAME_NEW,
                PureNativeJdkRuntimeHelpers.I32_BIG_ENDIAN_FRAME_WRITE,
                PureNativeJdkRuntimeHelpers.I32_BIG_ENDIAN_FRAME_FINISH);
        add(
                families,
                HostJniRuntimeSourceFamily.THREAD,
                "j2ll_rt_thread_sleep");
        add(
                families,
                HostJniRuntimeSourceFamily.MONITOR,
                "j2ll_rt_monitor_enter",
                "j2ll_rt_monitor_exit",
                "j2ll_rt_monitor_exit_on_exception");
        add(
                families,
                HostJniRuntimeSourceFamily.ARRAY,
                "j2ll_rt_array_length_i32",
                "j2ll_rt_array_load_i8",
                "j2ll_rt_array_store_i8",
                "j2ll_rt_array_load_i16",
                "j2ll_rt_array_store_i16",
                "j2ll_rt_array_load_u16",
                "j2ll_rt_array_store_u16",
                "j2ll_rt_array_load_i32",
                "j2ll_rt_array_store_i32",
                "j2ll_rt_array_load_i64",
                "j2ll_rt_array_store_i64",
                "j2ll_rt_array_load_f32",
                "j2ll_rt_array_store_f32",
                "j2ll_rt_array_load_f64",
                "j2ll_rt_array_store_f64",
                "j2ll_rt_array_load_ref",
                "j2ll_rt_array_store_ref");
        add(
                families,
                HostJniRuntimeSourceFamily.ALLOCATION,
                "j2ll_rt_new_byte_array",
                "j2ll_rt_new_short_array",
                "j2ll_rt_new_char_array",
                "j2ll_rt_new_int_array",
                "j2ll_rt_new_long_array",
                "j2ll_rt_new_float_array",
                "j2ll_rt_new_double_array");
        add(
                families,
                HostJniRuntimeSourceFamily.STRING,
                "j2ll_rt_string_length",
                "j2ll_rt_string_is_empty",
                "j2ll_rt_string_char_at",
                "j2ll_rt_string_equals",
                "j2ll_rt_string_starts_with",
                "j2ll_rt_string_ends_with",
                "j2ll_rt_string_substring",
                "j2ll_rt_string_substring_range",
                "j2ll_rt_string_builder_new",
                "j2ll_rt_string_builder_init",
                "j2ll_rt_string_builder_append_ref",
                "j2ll_rt_string_builder_append_i32",
                "j2ll_rt_string_builder_append_i64",
                "j2ll_rt_string_builder_append_f32",
                "j2ll_rt_string_builder_append_f64",
                "j2ll_rt_string_builder_to_string",
                "j2ll_rt_system_arraycopy");
        add(
                families,
                HostJniRuntimeSourceFamily.LAMBDA,
                "j2ll_rt_lambda_new");
        add(
                families,
                HostJniRuntimeSourceFamily.VAR_HANDLE,
                "j2ll_rt_var_handle_get_int",
                "j2ll_rt_var_handle_set_int",
                "j2ll_rt_var_handle_get_volatile_int",
                "j2ll_rt_var_handle_set_volatile_int",
                "j2ll_rt_var_handle_compare_and_set_int");
        add(
                families,
                HostJniRuntimeSourceFamily.REFLECTION,
                "j2ll_rt_reflect_invoke",
                "j2ll_rt_reflect_new_instance",
                "j2ll_rt_reflect_set_accessible",
                "j2ll_rt_reflect_field_get",
                "j2ll_rt_reflect_field_set",
                "j2ll_rt_reflect_field_get_int",
                "j2ll_rt_reflect_field_set_int",
                "j2ll_rt_reflect_field_get_boolean",
                "j2ll_rt_reflect_field_set_boolean",
                "j2ll_rt_reflect_field_get_long",
                "j2ll_rt_reflect_field_set_long",
                "j2ll_rt_reflect_field_get_double",
                "j2ll_rt_reflect_field_set_double",
                "j2ll_rt_unsafe_object_field_offset",
                "j2ll_rt_unsafe_static_field_offset",
                "j2ll_rt_unsafe_get_int",
                "j2ll_rt_unsafe_put_int",
                "j2ll_rt_unsafe_compare_and_swap_int",
                "j2ll_rt_unsafe_allocate_instance");
        add(
                families,
                Set.of(
                        HostJniRuntimeSourceFamily.REFLECTION,
                        HostJniRuntimeSourceFamily.VAR_HANDLE),
                "j2ll_rt_unsafe_get",
                "j2ll_rt_unsafe_get_volatile");
        return Map.copyOf(families);
    }

    private static void add(
            Map<String, Set<HostJniRuntimeSourceFamily>> target,
            HostJniRuntimeSourceFamily family,
            String... symbols) {
        add(target, Set.of(family), symbols);
    }

    private static void add(
            Map<String, Set<HostJniRuntimeSourceFamily>> target,
            Set<HostJniRuntimeSourceFamily> families,
            String... symbols) {
        for (String symbol : symbols) {
            if (target.put(symbol, Set.copyOf(families)) != null) {
                throw new IllegalStateException(
                        "duplicate stable runtime helper " + symbol);
            }
        }
    }

    record Classification(
            boolean runtimeReference,
            boolean recognized,
            EnumSet<HostJniRuntimeSourceFamily> families) {
        Classification {
            families = families.clone();
        }

        private static Classification notRuntime() {
            return new Classification(
                    false,
                    true,
                    EnumSet.noneOf(
                            HostJniRuntimeSourceFamily.class));
        }

        private static Classification unknownRuntime() {
            return new Classification(
                    true,
                    false,
                    EnumSet.noneOf(
                            HostJniRuntimeSourceFamily.class));
        }

        private static Classification known(
                HostJniRuntimeSourceFamily... families) {
            EnumSet<HostJniRuntimeSourceFamily> result =
                    EnumSet.noneOf(
                            HostJniRuntimeSourceFamily.class);
            java.util.Collections.addAll(result, families);
            return new Classification(true, true, result);
        }

        private static Classification known(
                Set<HostJniRuntimeSourceFamily> families) {
            EnumSet<HostJniRuntimeSourceFamily> result =
                    EnumSet.noneOf(
                            HostJniRuntimeSourceFamily.class);
            result.addAll(families);
            return new Classification(true, true, result);
        }
    }
}
