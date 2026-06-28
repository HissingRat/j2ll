package xyz.melodysky.packaging;

public final class FallbackDefinitionCapabilityResolver {
    public FallbackDefinitionCapability currentRuntimeCapability() {
        int feature = Runtime.version().feature();
        return resolve(feature, feature >= 15);
    }

    public FallbackDefinitionCapability resolve(int javaFeatureVersion, boolean ownerLookupSupported) {
        boolean hiddenClassApiAvailable = javaFeatureVersion >= 15;
        if (!hiddenClassApiAvailable) {
            return new FallbackDefinitionCapability(
                    "DefineClass",
                    "FALLBACK_HIDDEN_CLASS_UNAVAILABLE",
                    false,
                    ownerLookupSupported,
                    "MethodHandles.Lookup#defineHiddenClass requires Java 15 or newer");
        }
        if (!ownerLookupSupported) {
            return new FallbackDefinitionCapability(
                    "DefineClass",
                    "FALLBACK_HIDDEN_CLASS_UNSUPPORTED_ACCESS",
                    true,
                    false,
                    "owner-private Lookup handoff is unavailable for this fallback blob");
        }
        return new FallbackDefinitionCapability(
                "HiddenClass",
                "FALLBACK_HIDDEN_CLASS",
                true,
                true,
                "owner-private Lookup can define hidden fallback helper class");
    }
}
