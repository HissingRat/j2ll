package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.runtime.RuntimeTokenMapper;

final class HostJniLocalizedFieldRuntimeSourceTest {
    @Test
    void declaredOwnerWinsOverReceiverClassAndAllFieldAbisAreExact() {
        List<IrInstruction> instructions = new ArrayList<>();
        IrValue self = new IrValue("%self", IrType.REFERENCE);
        String descriptors = "ZBSCIJFD";
        int valueIndex = 0;
        for (char descriptor : descriptors.toCharArray()) {
            IrType type = irType(String.valueOf(descriptor));
            IrValue value = new IrValue("%v" + valueIndex++, type);
            String field = "base/Owner#f" + descriptor + "!" + descriptor;
            instructions.add(IrInstruction.fieldGet(
                    value,
                    IrOpcode.GET_FIELD,
                    List.of(self),
                    field));
            instructions.add(IrInstruction.fieldPut(
                    IrOpcode.PUT_FIELD,
                    List.of(self, value),
                    field));
            instructions.add(IrInstruction.fieldGet(
                    value,
                    IrOpcode.GET_STATIC,
                    List.of(),
                    field));
            instructions.add(IrInstruction.fieldPut(
                    IrOpcode.PUT_STATIC,
                    List.of(value),
                    field));
        }
        IrValue reference = new IrValue("%ref", IrType.REFERENCE);
        String referenceField =
                "base/Owner#object!Ljava/lang/Object;";
        instructions.add(IrInstruction.fieldGet(
                reference,
                IrOpcode.GET_FIELD,
                List.of(self),
                referenceField));
        instructions.add(IrInstruction.fieldPut(
                IrOpcode.PUT_FIELD,
                List.of(self, reference),
                referenceField));
        String source = emit(instructions, "field-build-one");

        assertTrue(source.contains(
                "jclass cls = (*env)->FindClass(env, \"base/Owner\")"));
        assertFalse(source.contains("GetObjectClass(env, self)"));
        for (String suffix : List.of(
                "Boolean", "Byte", "Short", "Char", "Int",
                "Long", "Float", "Double", "Object")) {
            assertTrue(source.contains("Get" + suffix + "Field"));
            assertTrue(source.contains("Set" + suffix + "Field"));
        }
        assertTrue(source.contains(
                "result == JNI_FALSE ? 0 : 1"));
        assertTrue(source.contains(
                "(jboolean)((uint32_t)value & UINT32_C(1))"));
        assertFalse(source.contains("j2ll_k"));
        assertFalse(source.contains(
                "native local ABI integrity check failed"));
        assertFalse(source.contains("j2ll_field_table"));
        assertFalse(source.contains("j2ll_find_field"));
    }

    @Test
    void fieldHelperSymbolIsBuildScopedAndHashOnly() {
        IrValue result = new IrValue("%value", IrType.I32);
        IrInstruction access = IrInstruction.fieldGet(
                result,
                IrOpcode.GET_FIELD,
                List.of(new IrValue("%self", IrType.REFERENCE)),
                "base/Owner#secret!I");
        String first = HostJniLocalizedFieldRuntimeSource.helperSymbol(
                RuntimeTokenMapper.fromBytes(
                        "one".getBytes(StandardCharsets.UTF_8)),
                access);
        String second = HostJniLocalizedFieldRuntimeSource.helperSymbol(
                RuntimeTokenMapper.fromBytes(
                        "two".getBytes(StandardCharsets.UTF_8)),
                access);

        assertNotEquals(first, second);
        assertTrue(first.matches("j2ll_h_[0-9a-f]{16}"));
        assertFalse(first.contains("field"));
        assertFalse(first.contains("secret"));
        assertFalse(first.contains("Owner"));
    }

    private static String emit(
            List<IrInstruction> instructions,
            String buildKey) {
        IrMethod method = new IrMethod(
                "child/Receiver",
                "fields",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        instructions,
                        IrTerminator.returnVoid())));
        StringBuilder source = new StringBuilder();
        HostJniLocalizedFieldRuntimeSource.append(
                source,
                List.of(binding(method)),
                RuntimeTokenMapper.fromBytes(
                        buildKey.getBytes(StandardCharsets.UTF_8)));
        return source.toString();
    }

    private static HostJniCSourceGenerator.Binding binding(
            IrMethod method) {
        return new HostJniCSourceGenerator.Binding(
                null, null, NativeImplementationPath.LLVM_NATIVE_PATH,
                Optional.empty(), true, false,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                Optional.of(method), "test", null);
    }

    private static IrType irType(String descriptor) {
        return switch (descriptor) {
            case "Z", "B", "S", "C", "I" -> IrType.I32;
            case "J" -> IrType.I64;
            case "F" -> IrType.F32;
            case "D" -> IrType.F64;
            default -> IrType.REFERENCE;
        };
    }
}
