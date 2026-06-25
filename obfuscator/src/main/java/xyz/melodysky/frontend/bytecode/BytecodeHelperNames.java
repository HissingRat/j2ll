package xyz.melodysky.frontend.bytecode;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.ir.model.IrType;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class BytecodeHelperNames {
    private BytecodeHelperNames() {
    }

    static String stringConstantHelperName(String value) {
        return "ir_rt_ldc_string__" + encodeHexUtf8(value);
    }

    static String arrayCreationHelperName(IrType arrayType) {
        return "ir_rt_new_array__" + encodeHelperToken(arrayType.displayName());
    }

    static String multiArrayCreationHelperName(IrType arrayType) {
        return "ir_rt_multi_new_array__" + encodeHelperToken(arrayType.displayName());
    }

    static String arrayLoadHelperName(IrType arrayType) {
        return "ir_rt_array_load__" + encodeHelperToken(arrayType.displayName());
    }

    static String arrayStoreHelperName(IrType arrayType) {
        return "ir_rt_array_store__" + encodeHelperToken(arrayType.displayName());
    }

    static String concatHelperName(String recipe, List<IrType> parameterTypes) {
        StringBuilder builder = new StringBuilder("ir_rt_concat__");
        builder.append(encodeHexUtf8(recipe));
        for (IrType parameterType : parameterTypes) {
            builder.append("__").append(encodeHelperToken(parameterType.displayName()));
        }
        return builder.toString();
    }

    static int countRecipeArguments(String recipe) {
        int count = 0;
        for (int index = 0; index < recipe.length(); index++) {
            if (recipe.charAt(index) == '\u0001') {
                count++;
            }
        }
        return count;
    }

    static String instanceOfHelperName(IrType targetType) {
        return "ir_rt_instanceof__" + encodeHelperToken(targetType.displayName());
    }

    static String lambdaInvokeKindToken(Handle implMethod) {
        return switch (implMethod.getTag()) {
            case Opcodes.H_INVOKESTATIC -> "static";
            case Opcodes.H_INVOKEVIRTUAL -> "virtual";
            case Opcodes.H_INVOKEINTERFACE -> "interface";
            case Opcodes.H_INVOKESPECIAL -> "special";
            case Opcodes.H_NEWINVOKESPECIAL -> "constructor";
            default -> throw new IllegalArgumentException("Unsupported lambda implementation handle tag: " + implMethod.getTag());
        };
    }

    static String encodeHelperToken(String value) {
        return encodeHexUtf8(value);
    }

    static String encodeHexUtf8(String value) {
        StringBuilder builder = new StringBuilder(value.length() * 2);
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            builder.append(String.format("%02x", current & 0xff));
        }
        return builder.toString();
    }
}
