package xyz.melodysky.toolchain;

import xyz.melodysky.toolchain.localref.NativeLocalReferenceOwnership;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlan;

/** Shared fail-closed interpretation of local-reference planning evidence. */
final class NativeJniEntryLocalReferenceFacts {
    private NativeJniEntryLocalReferenceFacts() {}

    static boolean requiresSemanticHandling(NativeLocalReferencePlan plan) {
        if (plan == null) {
            return false;
        }
        return plan.emitsReleases()
                || plan.ownershipByValue().values().stream()
                        .map(NativeLocalReferenceOwnership::kind)
                        .anyMatch(kind -> kind
                                != NativeLocalReferenceOwnership.Kind.BORROWED);
    }
}
