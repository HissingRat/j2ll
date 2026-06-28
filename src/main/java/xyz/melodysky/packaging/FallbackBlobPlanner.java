package xyz.melodysky.packaging;

import java.util.List;

public final class FallbackBlobPlanner {
    private final FallbackHelperClassFactory helperClassFactory = new FallbackHelperClassFactory();
    private final FallbackBlobCodec codec = new FallbackBlobCodec();
    private final FallbackDefinitionCapabilityResolver capabilityResolver;

    public FallbackBlobPlanner() {
        this(new FallbackDefinitionCapabilityResolver());
    }

    public FallbackBlobPlanner(FallbackDefinitionCapabilityResolver capabilityResolver) {
        this.capabilityResolver = capabilityResolver;
    }

    public List<NativeEmbeddedFallbackBlob> plan(List<FallbackBlobInput> inputs) {
        return inputs.stream()
                .sorted(java.util.Comparator
                        .comparing(FallbackBlobInput::ownerInternalName)
                        .thenComparing(FallbackBlobInput::originalMethodId)
                        .thenComparing(FallbackBlobInput::originalMethodKey))
                .map(this::blob)
                .toList();
    }

    private NativeEmbeddedFallbackBlob blob(FallbackBlobInput input) {
        FallbackHelperClass helperClass = helperClassFactory.create(input);
        EncodedFallbackBlob encoded = codec.encode(
                helperClass.bytes(),
                input.originalMethodId() + "\n" + input.originalMethodKey());
        FallbackDefinitionCapability capability = capabilityResolver.currentRuntimeCapability();
        return new NativeEmbeddedFallbackBlob(
                input.originalMethodId(),
                input.originalMethodKey(),
                helperClass.internalName(),
                helperClassFactory.helperDescriptor(input.ownerInternalName(), input.descriptor(), input.staticMethod()),
                input.reasonCode(),
                encoded.encodedSha256(),
                encoded.originalSha256(),
                encoded.encodedSha256(),
                encoded.encodingVersion(),
                encoded.originalBytes().length,
                encoded.encodedBytes().length,
                encoded.compressionAlgorithm(),
                encoded.encryptionAlgorithm(),
                "8",
                "nativeEmbeddedClassBlob",
                capability.definitionMechanism(),
                capability.reasonCode(),
                capability.hiddenClassApiAvailable(),
                capability.ownerLookupSupported(),
                capability.reason(),
                "FALLBACK_CACHE_REUSE",
                "lazyPerClassLoaderReuse",
                "process",
                "fallbackId+definingClassLoaderIdentity",
                "processLifetime",
                "globalRefPerFallbackClassAndClassLoader");
    }
}
