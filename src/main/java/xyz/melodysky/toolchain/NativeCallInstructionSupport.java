package xyz.melodysky.toolchain;

import java.util.List;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;

/** Descriptor-aware structural policy for constructor and Java call operations. */
final class NativeCallInstructionSupport {
    private final NativeIrTypeSupport typeSupport;

    NativeCallInstructionSupport(NativeIrTypeSupport typeSupport) {
        this.typeSupport = java.util.Objects.requireNonNull(
                typeSupport,
                "typeSupport");
    }

    boolean isConstructorCall(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_SPECIAL
                && instruction.symbol()
                        .map(symbol -> symbol.contains("#<init>!"))
                        .orElse(false);
    }

    boolean isDirectSpecialCall(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_SPECIAL
                && instruction.symbol()
                        .map(symbol -> !symbol.contains("#<init>!"))
                        .orElse(false);
    }

    boolean supportsConstructorCall(IrInstruction instruction) {
        if (!isConstructorCall(instruction)
                || instruction.result().isPresent()
                || instruction.operands().isEmpty()
                || instruction.operands().get(0).type()
                        != IrType.REFERENCE) {
            return false;
        }
        String descriptor = constructorDescriptor(
                instruction.symbol().orElseThrow());
        return typeSupport.returnDescriptor(descriptor).equals("V")
                && typeSupport.operandsMatchDescriptor(
                        descriptor,
                        instruction.operands().subList(
                                1,
                                instruction.operands().size()));
    }

    boolean supportsStaticBridge(IrInstruction instruction) {
        String descriptor = instruction.symbol()
                .flatMap(typeSupport::methodDescriptor)
                .orElse("");
        return descriptor.startsWith("(")
                && typeSupport.supportsJvmHostedDescriptor(descriptor)
                && resultMatchesDescriptor(instruction, descriptor)
                && typeSupport.operandsMatchDescriptor(
                        descriptor,
                        instruction.operands());
    }

    boolean isDispatch(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_DIRECT
                || instruction.opcode() == IrOpcode.CALL_VIRTUAL
                || instruction.opcode() == IrOpcode.CALL_INTERFACE;
    }

    boolean supportsDispatch(IrInstruction instruction) {
        if (instruction.operands().isEmpty()
                || instruction.operands().get(0).type()
                        != IrType.REFERENCE) {
            return false;
        }
        String descriptor = instruction.symbol()
                .flatMap(typeSupport::methodDescriptor)
                .orElse("");
        return descriptor.startsWith("(")
                && typeSupport.supportsJvmHostedDescriptor(descriptor)
                && resultMatchesDescriptor(instruction, descriptor)
                && typeSupport.operandsMatchDescriptor(
                        descriptor,
                        instruction.operands().subList(
                                1,
                                instruction.operands().size()));
    }

    private boolean resultMatchesDescriptor(
            IrInstruction instruction,
            String descriptor) {
        String returnDescriptor = typeSupport.returnDescriptor(descriptor);
        if (returnDescriptor.equals("V")) {
            return instruction.result().isEmpty();
        }
        return instruction.result().isPresent()
                && typeSupport.descriptorType(returnDescriptor)
                        == instruction.result().orElseThrow().type();
    }

    private String constructorDescriptor(String methodKey) {
        int descriptorStart = methodKey.indexOf('!');
        if (descriptorStart < 0) {
            return "";
        }
        return methodKey.substring(descriptorStart + 1);
    }
}
