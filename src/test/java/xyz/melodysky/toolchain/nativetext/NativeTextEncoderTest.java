package xyz.melodysky.toolchain.nativetext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class NativeTextEncoderTest {
    @Test
    void byteEncodingPreservesModifiedUtf8PayloadExactly() {
        byte[] modifiedUtf8 = java.util.HexFormat.of()
                .parseHex("61c080eda0bdedb880");
        NativeTextEncoding encoding = new NativeTextEncoder().encodeBytes(
                NativeTextBuildKey.fromUtf8("modified-utf-build"),
                NativeTextPurpose.BUSINESS_STRING,
                "modified-utf-site",
                modifiedUtf8);

        assertArrayEquals(
                modifiedUtf8,
                new NativeTextEncoder().decodeBytes(encoding));
    }

    private final NativeTextEncoder encoder = new NativeTextEncoder();

    @Test
    void fixedBuildKeyAndUseIdentityAreReproducible() {
        NativeTextBuildKey key = NativeTextBuildKey.fromUtf8("fixed-build-key");

        NativeTextEncoding first = encoder.encode(
                key,
                NativeTextPurpose.REGISTRATION_OWNER,
                "owner:sample/Owner",
                "sample/Owner");
        NativeTextEncoding second = encoder.encode(
                key,
                NativeTextPurpose.REGISTRATION_OWNER,
                "owner:sample/Owner",
                "sample/Owner");

        assertEquals(first, second);
        assertArrayEquals(first.ciphertext(), second.ciphertext());
        assertEquals(key.hashHex(), NativeTextBuildKey.fromUtf8("fixed-build-key").hashHex());
        assertTrue(first.symbol().matches("j2ll_nt_[0-9a-f]{24}"));
    }

    @Test
    void buildKeyPurposeAndUseIdentityAreDomainSeparated() {
        NativeTextEncoding baseline = encoder.encode(
                NativeTextBuildKey.fromUtf8("build-a"),
                NativeTextPurpose.REGISTRATION_DESCRIPTOR,
                "method:sample/Owner#run!()V",
                "()V");
        NativeTextEncoding differentBuild = encoder.encode(
                NativeTextBuildKey.fromUtf8("build-b"),
                NativeTextPurpose.REGISTRATION_DESCRIPTOR,
                "method:sample/Owner#run!()V",
                "()V");
        NativeTextEncoding differentPurpose = encoder.encode(
                NativeTextBuildKey.fromUtf8("build-a"),
                NativeTextPurpose.RUNTIME_DESCRIPTOR,
                "method:sample/Owner#run!()V",
                "()V");
        NativeTextEncoding differentUse = encoder.encode(
                NativeTextBuildKey.fromUtf8("build-a"),
                NativeTextPurpose.REGISTRATION_DESCRIPTOR,
                "method:sample/Owner#other!()V",
                "()V");

        assertNotEquals(baseline.symbol(), differentBuild.symbol());
        assertNotEquals(baseline.symbol(), differentPurpose.symbol());
        assertNotEquals(baseline.symbol(), differentUse.symbol());
        assertNotEquals(
                java.util.Arrays.toString(baseline.ciphertext()),
                java.util.Arrays.toString(differentBuild.ciphertext()));
        assertNotEquals(
                java.util.Arrays.toString(baseline.ciphertext()),
                java.util.Arrays.toString(differentPurpose.ciphertext()));
    }

    @Test
    void utf8RoundTripPreservesUnicodeText() {
        String value = "owner/类/メソッド😀/méthode";
        NativeTextEncoding encoding = encoder.encode(
                NativeTextBuildKey.fromUtf8("unicode-build"),
                NativeTextPurpose.RUNTIME_METHOD_NAME,
                "unicode-method",
                value);

        assertEquals(value, encoder.decodeUtf8(encoding));
        assertEquals(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, encoding.utf8Length());
        assertEquals(encoding.utf8Length() + 1, encoding.decodedBufferLength());
    }

    @Test
    void buildScopedSitesUseMultipleFamiliesAndRescheduleAcrossBuilds() {
        List<String> identities = IntStream.range(0, 128)
                .mapToObj(index -> "extractor-resistance-site:" + index)
                .toList();
        NativeTextBuildKey firstKey =
                NativeTextBuildKey.fromUtf8("extractor-build-a");
        NativeTextBuildKey secondKey =
                NativeTextBuildKey.fromUtf8("extractor-build-b");
        List<NativeTextEncoding> first = encodings(firstKey, identities);
        List<NativeTextEncoding> repeated = encodings(firstKey, identities);
        List<NativeTextEncoding> second = encodings(secondKey, identities);

        assertEquals(first, repeated);
        assertEquals(
                Set.of(NativeTextCodecFamily.values()),
                families(first));
        assertEquals(
                Set.of(NativeTextCodecFamily.values()),
                families(second));
        long changedShapes = IntStream.range(0, identities.size())
                .filter(index -> !first.get(index)
                        .codecPlan()
                        .shapeId()
                        .equals(second.get(index).codecPlan().shapeId()))
                .count();
        assertTrue(
                changedShapes >= identities.size() * 3L / 4L,
                "most sites should require a different codec extractor after a build-key change");
    }

    private List<NativeTextEncoding> encodings(
            NativeTextBuildKey buildKey,
            List<String> identities) {
        return identities.stream()
                .map(identity -> encoder.encode(
                        buildKey,
                        NativeTextPurpose.RUNTIME_DESCRIPTOR,
                        identity,
                        "(Ljava/lang/String;)V"))
                .toList();
    }

    private Set<NativeTextCodecFamily> families(
            List<NativeTextEncoding> encodings) {
        HashSet<NativeTextCodecFamily> families = new HashSet<>();
        encodings.forEach(encoding ->
                families.add(encoding.codecPlan().family()));
        return families;
    }
}
