package xyz.melodysky.backend.llvm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.backend.llvm.model.LlvmDeclaration;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.toolchain.localref.NativeLocalReferenceOwnership;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlan;

/**
 * Lowers one verified local-reference plan to release calls and ownership SSA.
 */
final class LlvmLocalReferenceLowering {
    static final String RELEASE_HELPER = "j2ll_rt_release_local_ref";

    private final NativeLocalReferencePlan plan;

    LlvmLocalReferenceLowering(NativeLocalReferencePlan plan) {
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    static LlvmDeclaration declaration() {
        return new LlvmDeclaration(
                RELEASE_HELPER,
                "void",
                List.of("ptr", "ptr", "i32"),
                "ownedJniLocalReferenceRelease");
    }

    List<LlvmInstruction> releases(List<IrValue> values) {
        ArrayList<LlvmInstruction> instructions = new ArrayList<>();
        values.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(value -> instructions.add(LlvmInstruction.rawProvenNoNativeUnwind(
                        Optional.empty(),
                        "call void @"
                                + RELEASE_HELPER
                                + "(ptr %j2ll_env, ptr "
                                + value.name()
                                + ", i32 "
                                + ownershipOperand(value)
                                + ")")));
        return List.copyOf(instructions);
    }

    Optional<LlvmInstruction> ownershipPhi(
            IrValue parameter,
            List<OwnershipIncoming> incoming) {
        NativeLocalReferenceOwnership ownership = ownership(parameter);
        if (ownership.kind()
                != NativeLocalReferenceOwnership.Kind.DYNAMIC) {
            return Optional.empty();
        }
        if (incoming.isEmpty()) {
            throw new IllegalArgumentException(
                    "dynamic local-reference ownership phi has no incoming values: "
                            + parameter.name());
        }
        List<String> operands = incoming.stream()
                .map(value -> "[ "
                        + ownershipOperand(value.value())
                        + ", %"
                        + value.predecessorBlock()
                        + " ]")
                .toList();
        return Optional.of(LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.of(ownershipValueName(parameter)),
                "phi i32 " + String.join(", ", operands)));
    }

    String ownershipOperand(IrValue value) {
        return ownershipOperand(value.name(), new LinkedHashSet<>());
    }

    String ownershipValueName(IrValue value) {
        return "%j2ll.lref.owned." + stableHash(value.name());
    }

    private String ownershipOperand(
            String valueName,
            Set<String> visiting) {
        if (!visiting.add(valueName)) {
            throw new IllegalArgumentException(
                    "cyclic local-reference ownership alias: " + valueName);
        }
        NativeLocalReferenceOwnership ownership =
                plan.ownershipByValue().get(valueName);
        if (ownership == null) {
            throw new IllegalArgumentException(
                    "local-reference plan has no ownership for " + valueName);
        }
        String result = switch (ownership.kind()) {
            case OWNED -> "1";
            case BORROWED -> "0";
            case DYNAMIC -> ownershipValueName(
                    new IrValue(valueName, xyz.melodysky.ir.model.IrType.REFERENCE));
            case ALIAS -> ownershipOperand(
                    ownership.aliasSource().orElseThrow(),
                    visiting);
        };
        visiting.remove(valueName);
        return result;
    }

    private NativeLocalReferenceOwnership ownership(IrValue value) {
        return plan.ownershipOf(value).orElseThrow(() ->
                new IllegalArgumentException(
                        "local-reference plan has no ownership for "
                                + value.name()));
    }

    private String stableHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 digest is unavailable",
                    exception);
        }
    }

    record OwnershipIncoming(
            String predecessorBlock,
            IrValue value) {
        OwnershipIncoming {
            Objects.requireNonNull(predecessorBlock, "predecessorBlock");
            Objects.requireNonNull(value, "value");
        }
    }
}
