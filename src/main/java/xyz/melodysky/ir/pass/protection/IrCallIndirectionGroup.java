package xyz.melodysky.ir.pass.protection;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.ir.model.IrCallSignature;

public record IrCallIndirectionGroup(
        String groupId,
        IrCallSignature signature,
        List<IrCallIndirectionTarget> targets) {
    public IrCallIndirectionGroup {
        Objects.requireNonNull(groupId, "groupId");
        if (groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be blank");
        }
        Objects.requireNonNull(signature, "signature");
        targets = targets.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(IrCallIndirectionTarget::indexOrSelector)
                        .thenComparing(IrCallIndirectionTarget::entryId))
                .toList();
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("call-indirection group requires at least one target");
        }
        if (new HashSet<>(targets.stream().map(IrCallIndirectionTarget::entryId).toList()).size()
                != targets.size()) {
            throw new IllegalArgumentException("call-indirection group entry ids must be unique");
        }
        if (new HashSet<>(targets.stream().map(IrCallIndirectionTarget::targetMethodKey).toList()).size()
                != targets.size()) {
            throw new IllegalArgumentException("call-indirection group target methods must be unique");
        }
    }
}
