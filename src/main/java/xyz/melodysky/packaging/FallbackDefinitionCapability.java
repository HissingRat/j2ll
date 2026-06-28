package xyz.melodysky.packaging;

import java.util.Objects;

public record FallbackDefinitionCapability(
        String definitionMechanism,
        String reasonCode,
        boolean hiddenClassApiAvailable,
        boolean ownerLookupSupported,
        String reason) {
    public FallbackDefinitionCapability {
        Objects.requireNonNull(definitionMechanism, "definitionMechanism");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(reason, "reason");
    }
}
