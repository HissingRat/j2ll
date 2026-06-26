package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FallbackBlobPlannerTest {
    @Test
    void plansNativeEmbeddedFallbackBlobManifestAndClassloaderReusePolicy() {
        NativeEmbeddedFallbackBlob blob = new FallbackBlobPlanner().plan(List.of(new FallbackBlobInput(
                        "run__8f3a21c0d4e5f607",
                        "pkg/Foo#run!()V",
                        "pkg/Foo")))
                .get(0);

        assertEquals("run__8f3a21c0d4e5f607", blob.originalMethodId());
        assertEquals("pkg/Foo#run!()V", blob.originalMethodKey());
        assertEquals("j2ll/generated/fallback/pkg_Foo/Fallback$run__8f3a21c0d4e5f607", blob.helperClassName());
        assertEquals("j2ll-rle-byte-pairs-v1", blob.compressionAlgorithm());
        assertEquals("xor-sha256-key-stream-v1", blob.encryptionAlgorithm());
        assertEquals("fallbackBlobEncodingV1", blob.encodingVersion());
        assertEquals("nativeEmbeddedClassBlob", blob.storageTarget());
        assertEquals("DefineClass", blob.definitionMechanism());
        assertEquals("lazyPerClassLoaderReuse", blob.classloaderReusePolicy());
        assertEquals(64, blob.sha256().length());
        assertEquals(blob.encodedSha256(), blob.sha256());
        assertEquals(64, blob.originalSha256().length());
        assertEquals(64, blob.encodedSha256().length());
        assertTrue(blob.originalSize() > 0);
        assertTrue(blob.encodedSize() > 0);
        assertTrue(blob.sha256().matches("[0-9a-f]+"));
    }

    @Test
    void codecEncodesFallbackClassBytesAndRejectsHashMismatch() {
        FallbackHelperClass helperClass = new FallbackHelperClassFactory().create(
                "substring__1234",
                "pkg/Foo#substring!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/Foo");
        FallbackBlobCodec codec = new FallbackBlobCodec();
        EncodedFallbackBlob encoded = codec.encode(helperClass.bytes(), "seed\npkg/Foo#substring");

        assertFalse(Arrays.equals(helperClass.bytes(), encoded.encodedBytes()));
        assertTrue(Arrays.equals(helperClass.bytes(), codec.decode(encoded)));

        EncodedFallbackBlob tampered = new EncodedFallbackBlob(
                encoded.originalBytes(),
                encoded.encodedBytes(),
                encoded.keyBytes(),
                encoded.originalSha256(),
                "0".repeat(64),
                encoded.compressionAlgorithm(),
                encoded.encryptionAlgorithm(),
                encoded.encodingVersion());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(tampered));
    }
}
