package xyz.melodysky.runtime;

import java.util.ArrayList;
import java.util.List;

public final class RuntimeStubGenerator {
    private final RuntimeAbi abi = new RuntimeAbi();

    public String emitHeader(RuntimeHelperCatalog catalog) {
        StringBuilder builder = new StringBuilder();
        builder.append("#ifndef J2LL_RUNTIME_HELPERS_H\n");
        builder.append("#define J2LL_RUNTIME_HELPERS_H\n\n");
        builder.append("#include <jni.h>\n");
        builder.append("#include <stdint.h>\n\n");
        builder.append("/* Java-visible references in this ABI are JNI handles owned by the JVM. */\n");
        builder.append("/* Helpers must allocate Java objects through JNIEnv/runtime helpers, never native heap. */\n\n");
        RuntimeHelperCategory currentCategory = null;
        for (RuntimeHelper helper : catalog.helpers()) {
            if (helper.category() != currentCategory) {
                currentCategory = helper.category();
                builder.append("/* ").append(categoryName(currentCategory)).append(" */\n");
            }
            builder.append(prototype(helper)).append(";\n");
        }
        builder.append("\n#endif\n");
        return builder.toString();
    }

    public String emitCSource(RuntimeHelperCatalog catalog) {
        StringBuilder builder = new StringBuilder();
        builder.append("#include \"runtime-helpers.h\"\n\n");
        RuntimeHelperCategory currentCategory = null;
        for (RuntimeHelper helper : catalog.helpers()) {
            if (helper.category() != currentCategory) {
                currentCategory = helper.category();
                builder.append("/* ").append(categoryName(currentCategory)).append(" */\n");
            }
            builder.append(prototype(helper)).append(" {\n");
            builder.append("    (void)env;\n");
            builder.append("    /* TODO: PushLocalFrame/PopLocalFrame according to helper local-frame policy. */\n");
            builder.append("    /* TODO: Check (*env)->ExceptionCheck(env) before returning or chaining helpers. */\n");
            for (int index = 0; index < helper.signature().parameterTypes().size(); index++) {
                builder.append("    (void)arg").append(index).append(";\n");
            }
            defaultReturn(helper).forEach(line -> builder.append("    ").append(line).append('\n'));
            builder.append("}\n\n");
        }
        return builder.toString();
    }

    public String prototype(RuntimeHelper helper) {
        return abi.cType(helper.signature().returnType())
                + " "
                + helper.llvmSymbol()
                + "("
                + cParameters(helper)
                + ")";
    }

    private String cParameters(RuntimeHelper helper) {
        ArrayList<String> parameters = new ArrayList<>();
        parameters.add("JNIEnv* env");
        for (int index = 0; index < helper.signature().parameterTypes().size(); index++) {
            parameters.add(abi.cType(helper.signature().parameterTypes().get(index)) + " arg" + index);
        }
        return String.join(", ", parameters);
    }

    private List<String> defaultReturn(RuntimeHelper helper) {
        return switch (helper.llvmReturnType()) {
            case "void" -> List.of();
            case "ptr" -> List.of("return 0;");
            case "i32", "i64" -> List.of("return 0;");
            case "float" -> List.of("return 0.0f;");
            case "double" -> List.of("return 0.0;");
            default -> throw new IllegalArgumentException("unsupported runtime ABI type " + helper.llvmReturnType());
        };
    }

    private String categoryName(RuntimeHelperCategory category) {
        return category.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
