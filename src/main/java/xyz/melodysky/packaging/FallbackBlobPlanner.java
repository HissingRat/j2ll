package xyz.melodysky.packaging;

import java.util.List;

public final class FallbackBlobPlanner {
    private final FallbackHelperClassFactory helperClassFactory = new FallbackHelperClassFactory();
    private final FallbackBlobCodec codec = new FallbackBlobCodec();

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
        FallbackHelperClass helperClass = helperClassFactory.create(
                input.originalMethodId(),
                input.originalMethodKey(),
                input.ownerInternalName());
        EncodedFallbackBlob encoded = codec.encode(
                helperClass.bytes(),
                input.originalMethodId() + "\n" + input.originalMethodKey());
        return new NativeEmbeddedFallbackBlob(
                input.originalMethodId(),
                input.originalMethodKey(),
                helperClass.internalName(),
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
                "DefineClass",
                "lazyPerClassLoaderReuse");
    }
}
