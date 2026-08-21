package xyz.melodysky.backend.llvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import xyz.melodysky.analysis.field.NativeFieldStorageKind;
import xyz.melodysky.backend.llvm.model.LlvmDeclaration;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.model.NativeFieldSlotRef;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;

/**
 * Exact lowering for opaque native static-field slots.
 */
final class NativeFieldLlvmLowering {
    static final String REFERENCE_SIDECAR_CACHE = "%j2ll_nfs_ref_cache";
    private final RuntimeTokenMapper runtimeTokens;

    NativeFieldLlvmLowering(RuntimeTokenMapper runtimeTokens) {
        this.runtimeTokens = java.util.Objects.requireNonNull(
                runtimeTokens,
                "runtimeTokens");
    }

    List<LlvmDeclaration> declarations() {
        return List.of(
                declaration("j2ll_nfs_get_z", "i32", List.of("ptr", "ptr", "i64"), "boolean"),
                declaration("j2ll_nfs_put_z", "void", List.of("ptr", "ptr", "i64", "i32"), "boolean"),
                declaration("j2ll_nfs_get_b", "i32", List.of("ptr", "ptr", "i64"), "byte"),
                declaration("j2ll_nfs_put_b", "void", List.of("ptr", "ptr", "i64", "i32"), "byte"),
                declaration("j2ll_nfs_get_s", "i32", List.of("ptr", "ptr", "i64"), "short"),
                declaration("j2ll_nfs_put_s", "void", List.of("ptr", "ptr", "i64", "i32"), "short"),
                declaration("j2ll_nfs_get_c", "i32", List.of("ptr", "ptr", "i64"), "char"),
                declaration("j2ll_nfs_put_c", "void", List.of("ptr", "ptr", "i64", "i32"), "char"),
                declaration("j2ll_nfs_get_i32", "i32", List.of("ptr", "ptr", "i64"), "int"),
                declaration("j2ll_nfs_put_i32", "void", List.of("ptr", "ptr", "i64", "i32"), "int"),
                declaration("j2ll_nfs_get_i64", "i64", List.of("ptr", "ptr", "i64"), "long"),
                declaration("j2ll_nfs_put_i64", "void", List.of("ptr", "ptr", "i64", "i64"), "long"),
                declaration("j2ll_nfs_get_f32_bits", "i32", List.of("ptr", "ptr", "i64"), "float bits"),
                declaration("j2ll_nfs_put_f32_bits", "void", List.of("ptr", "ptr", "i64", "i32"), "float bits"),
                declaration("j2ll_nfs_get_f64_bits", "i64", List.of("ptr", "ptr", "i64"), "double bits"),
                declaration("j2ll_nfs_put_f64_bits", "void", List.of("ptr", "ptr", "i64", "i64"), "double bits"),
                declaration(
                        "j2ll_nfs_reference_sidecar_cached",
                        "ptr",
                        List.of("ptr", "ptr", "ptr"),
                        "cached reference sidecar"),
                declaration(
                        "j2ll_nfs_release_reference_sidecar",
                        "void",
                        List.of("ptr", "ptr"),
                        "reference sidecar cleanup"),
                declaration("j2ll_nfs_get_ref", "ptr", List.of("ptr", "ptr", "i32"), "reference"),
                declaration("j2ll_nfs_put_ref", "void", List.of("ptr", "ptr", "i32", "ptr"), "reference"));
    }

    boolean usesReferenceSidecar(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(this::isNativeFieldInstruction)
                .flatMap(instruction -> instruction.symbol().stream())
                .map(NativeFieldSlotRef::parse)
                .flatMap(Optional::stream)
                .anyMatch(slot -> slot.kind() == NativeFieldStorageKind.REFERENCE);
    }

    List<LlvmInstruction> referenceSidecarCacheInitialization() {
        return List.of(
                LlvmInstruction.rawProvenNoNativeUnwind(
                        Optional.of(REFERENCE_SIDECAR_CACHE),
                        "alloca ptr"),
                LlvmInstruction.rawProvenNoNativeUnwind(
                        Optional.empty(),
                        "store ptr null, ptr " + REFERENCE_SIDECAR_CACHE));
    }

    LlvmInstruction referenceSidecarCleanup() {
        return LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.empty(),
                "call void @j2ll_nfs_release_reference_sidecar("
                        + "ptr %j2ll_env, ptr " + REFERENCE_SIDECAR_CACHE + ")");
    }

    List<LlvmInstruction> lower(IrInstruction instruction, String uniqueSuffix) {
        NativeFieldSlotRef slot = instruction.symbol()
                .flatMap(NativeFieldSlotRef::parse)
                .orElseThrow(() -> new IllegalArgumentException(
                        "native static field instruction has no typed slot reference"));
        validateShape(instruction, slot);
        if (slot.kind() == NativeFieldStorageKind.REFERENCE) {
            return lowerReference(instruction, slot, uniqueSuffix);
        }
        String helper = helper(instruction.opcode(), slot.kind());
        String token = "i64 " + runtimeTokens.token(
                RuntimeTokenDomain.NATIVE_FIELD_SLOT,
                slot.opaqueSlotId());
        if (instruction.opcode() == IrOpcode.GET_NATIVE_STATIC) {
            if (slot.kind() == NativeFieldStorageKind.FLOAT
                    || slot.kind() == NativeFieldStorageKind.DOUBLE) {
                String integerType = slot.kind() == NativeFieldStorageKind.FLOAT ? "i32" : "i64";
                String floatType = slot.kind() == NativeFieldStorageKind.FLOAT ? "float" : "double";
                String bits = instruction.result().orElseThrow().name()
                        + ".nfs.bits." + uniqueSuffix;
                return List.of(
                        LlvmInstruction.rawProvenNoNativeUnwind(
                                Optional.of(bits),
                                "call " + integerType + " @" + helper
                                        + "(ptr %j2ll_env, ptr %j2ll_owner, " + token + ")"),
                        LlvmInstruction.rawProvenNoNativeUnwind(
                                Optional.of(instruction.result().orElseThrow().name()),
                                "bitcast " + integerType + " " + bits + " to " + floatType));
            }
            String type = slot.kind() == NativeFieldStorageKind.LONG ? "i64" : "i32";
            return List.of(LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + type + " @" + helper
                            + "(ptr %j2ll_env, ptr %j2ll_owner, " + token + ")"));
        }
        IrValue value = instruction.operands().get(0);
        if (slot.kind() == NativeFieldStorageKind.FLOAT
                || slot.kind() == NativeFieldStorageKind.DOUBLE) {
            String integerType = slot.kind() == NativeFieldStorageKind.FLOAT ? "i32" : "i64";
            String floatType = slot.kind() == NativeFieldStorageKind.FLOAT ? "float" : "double";
            String bits = "%j2ll.nfs.put.bits." + uniqueSuffix;
            return List.of(
                    LlvmInstruction.rawProvenNoNativeUnwind(
                            Optional.of(bits),
                            "bitcast " + floatType + " " + value.name() + " to " + integerType),
                    LlvmInstruction.rawProvenNoNativeUnwind(
                            Optional.empty(),
                            "call void @" + helper
                                    + "(ptr %j2ll_env, ptr %j2ll_owner, " + token
                                    + ", " + integerType + " " + bits + ")"));
        }
        String type = slot.kind() == NativeFieldStorageKind.LONG ? "i64" : "i32";
        return List.of(LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.empty(),
                "call void @" + helper
                        + "(ptr %j2ll_env, ptr %j2ll_owner, " + token
                        + ", " + type + " " + value.name() + ")"));
    }

    private List<LlvmInstruction> lowerReference(
            IrInstruction instruction,
            NativeFieldSlotRef slot,
            String uniqueSuffix) {
        String index = "i32 " + slot.referenceIndex();
        String sidecar = "%j2ll.nfs.sidecar." + uniqueSuffix;
        LlvmInstruction lookup = LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.of(sidecar),
                "call ptr @j2ll_nfs_reference_sidecar_cached("
                        + "ptr %j2ll_env, ptr %j2ll_owner, ptr "
                        + REFERENCE_SIDECAR_CACHE + ")");
        if (instruction.opcode() == IrOpcode.GET_NATIVE_STATIC) {
            return List.of(
                    lookup,
                    LlvmInstruction.rawProvenNoNativeUnwind(
                            Optional.of(instruction.result().orElseThrow().name()),
                            "call ptr @j2ll_nfs_get_ref(ptr %j2ll_env, ptr "
                                    + sidecar + ", " + index + ")"));
        }
        return List.of(
                lookup,
                LlvmInstruction.rawProvenNoNativeUnwind(
                        Optional.empty(),
                        "call void @j2ll_nfs_put_ref(ptr %j2ll_env, ptr "
                                + sidecar + ", " + index
                                + ", ptr " + instruction.operands().get(0).name() + ")"));
    }

    private String helper(IrOpcode opcode, NativeFieldStorageKind kind) {
        String prefix = opcode == IrOpcode.GET_NATIVE_STATIC
                ? "j2ll_nfs_get_"
                : "j2ll_nfs_put_";
        return prefix + switch (kind) {
            case BOOLEAN -> "z";
            case BYTE -> "b";
            case SHORT -> "s";
            case CHAR -> "c";
            case INT -> "i32";
            case LONG -> "i64";
            case FLOAT -> "f32_bits";
            case DOUBLE -> "f64_bits";
            case REFERENCE -> throw new IllegalArgumentException(
                    "reference fields use the JVM sidecar helpers");
        };
    }

    private void validateShape(
            IrInstruction instruction,
            NativeFieldSlotRef slot) {
        IrType expectedType = switch (slot.kind()) {
            case BOOLEAN, BYTE, SHORT, CHAR, INT -> IrType.I32;
            case LONG -> IrType.I64;
            case FLOAT -> IrType.F32;
            case DOUBLE -> IrType.F64;
            case REFERENCE -> IrType.REFERENCE;
        };
        if (instruction.opcode() == IrOpcode.GET_NATIVE_STATIC) {
            if (!instruction.operands().isEmpty()
                    || instruction.result().isEmpty()
                    || instruction.result().orElseThrow().type() != expectedType) {
                throw new IllegalArgumentException(
                        "native static field get shape does not match slot kind "
                                + slot.kind());
            }
            return;
        }
        if (instruction.opcode() != IrOpcode.PUT_NATIVE_STATIC
                || instruction.result().isPresent()
                || instruction.operands().size() != 1
                || instruction.operands().get(0).type() != expectedType) {
            throw new IllegalArgumentException(
                    "native static field put shape does not match slot kind "
                            + slot.kind());
        }
    }

    private boolean isNativeFieldInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.GET_NATIVE_STATIC
                || instruction.opcode() == IrOpcode.PUT_NATIVE_STATIC;
    }

    private LlvmDeclaration declaration(
            String symbol,
            String returnType,
            List<String> parameters,
            String valueKind) {
        return new LlvmDeclaration(
                symbol,
                returnType,
                parameters,
                "native field slot " + valueKind);
    }
}
