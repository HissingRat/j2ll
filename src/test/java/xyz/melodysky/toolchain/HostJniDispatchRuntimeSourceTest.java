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

final class HostJniDispatchRuntimeSourceTest {
    @Test
    void emitsStaticVirtualInterfaceAndExactNarrowJniFamilies() {
        List<IrInstruction> instructions = new ArrayList<>();
        List<String> dispatchKeys = new ArrayList<>();
        IrValue receiver = new IrValue("%receiver", IrType.REFERENCE);
        int index = 0;
        for (String descriptor : List.of(
                "()Z", "()B", "()C", "()S", "()I",
                "()J", "()F", "()D", "()Ljava/lang/Object;", "()V")) {
            String virtual = "base/Owner#v" + index + "!" + descriptor;
            String iface = "api/Contract#i" + index + "!" + descriptor;
            Optional<IrValue> result = descriptor.endsWith("V")
                    ? Optional.empty()
                    : Optional.of(new IrValue(
                            "%r" + index,
                            returnType(descriptor)));
            instructions.add(IrInstruction.call(
                    result,
                    IrOpcode.CALL_VIRTUAL,
                    List.of(receiver),
                    virtual));
            instructions.add(IrInstruction.call(
                    result,
                    IrOpcode.CALL_INTERFACE,
                    List.of(receiver),
                    iface));
            dispatchKeys.add(virtual);
            dispatchKeys.add(iface);
            index++;
        }
        IrMethod method = new IrMethod(
                "child/Receiver",
                "calls",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        instructions,
                        IrTerminator.returnVoid())));
        HostJniCSourceGenerator.Binding binding = binding(
                method,
                List.of("base/Owner#<init>!()V"),
                List.of("base/Owner#staticBoolean!()Z"),
                dispatchKeys);
        StringBuilder source = new StringBuilder();
        HostJniDispatchRuntimeSource.append(
                source,
                List.of(binding),
                mapper("dispatch-build"));
        String generated = source.toString();

        assertTrue(generated.contains("CallNonvirtualVoidMethodA"));
        assertTrue(generated.contains("CallStaticBooleanMethodA"));
        for (String suffix : List.of(
                "Boolean", "Byte", "Char", "Short", "Int",
                "Long", "Float", "Double", "Object", "Void")) {
            assertTrue(generated.contains("Call" + suffix + "MethodA"));
        }
        assertTrue(generated.contains(
                "result == JNI_FALSE ? 0 : 1"));
        assertTrue(generated.contains(
                "GetObjectClass(env, receiver)"));
        assertFalse(generated.contains("j2ll_k"));
        assertFalse(generated.contains(
                "native local ABI integrity check failed"));
        assertFalse(generated.contains(
                "FindClass(env, \"api/Contract\")"));
        assertFalse(generated.contains("j2ll_method_table"));
        assertFalse(generated.contains("j2ll_find_method"));
    }

    @Test
    void dispatchHelperIsBuildScopedHashOnlyAndOperationSeparated() {
        String method = "api/Contract#secret!()Z";
        RuntimeTokenMapper firstMapper = mapper("one");
        String virtual = HostJniDispatchRuntimeSource.helperSymbol(
                firstMapper,
                "virtual_dispatch_boolean",
                method);
        String iface = HostJniDispatchRuntimeSource.helperSymbol(
                firstMapper,
                "interface_dispatch_boolean",
                method);
        String otherBuild = HostJniDispatchRuntimeSource.helperSymbol(
                mapper("two"),
                "virtual_dispatch_boolean",
                method);

        assertNotEquals(virtual, iface);
        assertNotEquals(virtual, otherBuild);
        assertTrue(virtual.matches("j2ll_h_[0-9a-f]{16}"));
        assertFalse(virtual.contains("dispatch"));
        assertFalse(virtual.contains("boolean"));
        assertFalse(virtual.contains("secret"));
        assertFalse(virtual.contains("Contract"));
    }

    private static HostJniCSourceGenerator.Binding binding(
            IrMethod method,
            List<String> constructors,
            List<String> statics,
            List<String> dispatch) {
        return new HostJniCSourceGenerator.Binding(
                null, null, NativeImplementationPath.LLVM_NATIVE_PATH,
                Optional.empty(), true, false,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), constructors, statics, dispatch, List.of(),
                Optional.of(method), "test", null);
    }

    private static RuntimeTokenMapper mapper(String key) {
        return RuntimeTokenMapper.fromBytes(
                key.getBytes(StandardCharsets.UTF_8));
    }

    private static IrType returnType(String descriptor) {
        return switch (descriptor.charAt(descriptor.indexOf(')') + 1)) {
            case 'Z', 'B', 'C', 'S', 'I' -> IrType.I32;
            case 'J' -> IrType.I64;
            case 'F' -> IrType.F32;
            case 'D' -> IrType.F64;
            default -> IrType.REFERENCE;
        };
    }
}
