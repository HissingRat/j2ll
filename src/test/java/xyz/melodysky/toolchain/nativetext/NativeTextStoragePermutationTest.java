package xyz.melodysky.toolchain.nativetext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class NativeTextStoragePermutationTest {
    private final NativeTextStoragePermutationPlanner planner =
            new NativeTextStoragePermutationPlanner();

    @Test
    void emptyAndSingletonStorageUseTheSafeIdentityMapping() {
        NativeTextBuildKey key =
                NativeTextBuildKey.fromUtf8("affine-degenerate-build");

        NativeTextStoragePermutation empty = planner.plan(
                key,
                NativeTextPurpose.RUNTIME_ERROR,
                "affine-empty",
                0);
        NativeTextStoragePermutation singleton = planner.plan(
                key,
                NativeTextPurpose.RUNTIME_ERROR,
                "affine-singleton",
                1);

        assertEquals(NativeTextStoragePermutation.identity(0), empty);
        assertEquals(NativeTextStoragePermutation.identity(1), singleton);
        assertArrayEquals(new byte[0], empty.store(new byte[0]));
        assertArrayEquals(new byte[] {0x5a}, singleton.store(new byte[] {0x5a}));
        assertEquals(0, singleton.physicalIndex(0));
    }

    @Test
    void everyNonDegeneratePlanIsBijectiveAndAddsNoStorageBytes() {
        NativeTextBuildKey key =
                NativeTextBuildKey.fromUtf8("affine-bijection-build");

        for (int length = 2; length <= 1024; length++) {
            NativeTextStoragePermutation permutation = planner.plan(
                    key,
                    NativeTextPurpose.RUNTIME_DESCRIPTOR,
                    "affine-length:" + length,
                    length);
            assertFalse(permutation.isIdentity(), "length=" + length);
            assertTrue(NativeTextStoragePermutation.areCoprime(
                    permutation.stride(),
                    length));
            if (hasNonTrivialUnit(length)) {
                assertTrue(
                        permutation.stride() != 1
                                && permutation.stride()
                                        != length - 1,
                        "length=" + length);
            }

            byte[] logical = new byte[length];
            boolean[] physicalSeen = new boolean[length];
            for (int index = 0; index < length; index++) {
                logical[index] = (byte) (index * 37 + 11);
                int physical = permutation.physicalIndex(index);
                assertFalse(
                        physicalSeen[physical],
                        "duplicate physical index for length=" + length);
                physicalSeen[physical] = true;
            }
            byte[] stored = permutation.store(logical);
            assertEquals(logical.length, stored.length);
            for (int logicalIndex = 0;
                    logicalIndex < logical.length;
                    logicalIndex++) {
                assertEquals(
                        logical[logicalIndex],
                        stored[permutation.physicalIndex(logicalIndex)]);
            }
        }
    }

    @Test
    void physicalIndexUsesWideArithmeticNearTheJavaArrayLengthLimit() {
        int length = Integer.MAX_VALUE;
        NativeTextStoragePermutation permutation =
                new NativeTextStoragePermutation(
                        length,
                        length - 1,
                        length - 2);
        int logical = length - 1;

        assertEquals(
                (int) (((length - 1L)
                                + (long) logical
                                        * (length - 2L))
                        % length),
                permutation.physicalIndex(logical));
    }

    @Test
    void affineParametersAreStableWithinABuildAndVaryAcrossDomains() {
        NativeTextBuildKey firstKey =
                NativeTextBuildKey.fromUtf8("affine-domain-build-a");
        NativeTextBuildKey secondKey =
                NativeTextBuildKey.fromUtf8("affine-domain-build-b");

        NativeTextStoragePermutation baseline = planner.plan(
                firstKey,
                NativeTextPurpose.REGISTRATION_OWNER,
                "affine-domain-use-a",
                257);

        assertEquals(
                baseline,
                planner.plan(
                        firstKey,
                        NativeTextPurpose.REGISTRATION_OWNER,
                        "affine-domain-use-a",
                        257));
        assertNotEquals(
                baseline,
                planner.plan(
                        secondKey,
                        NativeTextPurpose.REGISTRATION_OWNER,
                        "affine-domain-use-a",
                        257));
        assertNotEquals(
                baseline,
                planner.plan(
                        firstKey,
                        NativeTextPurpose.RUNTIME_CLASS_NAME,
                        "affine-domain-use-a",
                        257));
        assertNotEquals(
                baseline,
                planner.plan(
                        firstKey,
                        NativeTextPurpose.REGISTRATION_OWNER,
                        "affine-domain-use-b",
                        257));
    }

    @Test
    void encodedStorageHasZeroByteOverheadAndDecodeLogicHasConstantSizeGrowth() {
        NativeTextEncoder encoder = new NativeTextEncoder();
        NativeTextBuildKey key =
                NativeTextBuildKey.fromUtf8("affine-size-boundary-build");
        NativeTextEncoding small = encoder.encode(
                key,
                NativeTextPurpose.RUNTIME_ERROR,
                "affine-size-small",
                "tiny");
        String largePlaintext = "x".repeat(8192);
        NativeTextEncoding large = matchingCodecCase(
                encoder,
                key,
                small,
                largePlaintext);

        assertEquals(
                "tiny".getBytes(StandardCharsets.UTF_8).length + 1,
                small.ciphertext().length);
        assertEquals(
                largePlaintext.getBytes(StandardCharsets.UTF_8).length + 1,
                large.ciphertext().length);
        assertEquals("tiny", encoder.decodeUtf8(small));
        assertEquals(largePlaintext, encoder.decodeUtf8(large));

        NativeTextCEmitter emitter = new NativeTextCEmitter();
        String smallDecode = emitter.decodeInto(
                small,
                "output",
                "");
        String largeDecode = emitter.decodeInto(
                large,
                "output",
                "");
        int affineSourceOverhead =
                affineSourceOverhead(large, largeDecode);
        assertTrue(
                Math.abs(smallDecode.length() - largeDecode.length()) <= 512,
                "affine decode logic must remain constant-sized instead of emitting a permutation table");
        assertTrue(
                affineSourceOverhead > 0
                        && affineSourceOverhead <= 440,
                "one affine cursor must add at most 440 generated-C characters, actual="
                        + affineSourceOverhead);
        assertFalse(smallDecode.contains("[] ="));
        assertFalse(largeDecode.contains("[] ="));
    }

    private int affineSourceOverhead(
            NativeTextEncoding encoding,
            String decodeSource) {
        String token =
                encoding.symbol().substring("j2ll_nt_".length());
        String storageIndex = "j2ll_nt_s_" + token;
        StringBuilder direct = new StringBuilder();
        boolean declarationFound = false;
        boolean firstLine = true;
        for (String line : decodeSource.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith(
                    "size_t " + storageIndex + " =")) {
                declarationFound = true;
                continue;
            }
            if (trimmed.startsWith(storageIndex + " +=")
                    || trimmed.startsWith(storageIndex + " -=")) {
                continue;
            }
            if (!firstLine) {
                direct.append('\n');
            }
            direct.append(line.replace(
                    storageIndex,
                    "j2ll_nt_p_" + token));
            firstLine = false;
        }
        if (!declarationFound) {
            throw new AssertionError(
                    "affine storage index declaration is missing");
        }
        return decodeSource.length() - direct.length();
    }

    private NativeTextEncoding matchingCodecCase(
            NativeTextEncoder encoder,
            NativeTextBuildKey key,
            NativeTextEncoding expectedShape,
            String plaintext) {
        String expected = coarseShape(expectedShape);
        for (int index = 0; index < 10_000; index++) {
            NativeTextEncoding candidate = encoder.encode(
                    key,
                    NativeTextPurpose.RUNTIME_ERROR,
                    "affine-size-large:" + index,
                    plaintext);
            if (coarseShape(candidate).equals(expected)) {
                return candidate;
            }
        }
        throw new AssertionError("could not select a matching codec case");
    }

    private String coarseShape(NativeTextEncoding encoding) {
        NativeTextCodecPlan plan = encoding.codecPlan();
        return plan.family()
                + ":"
                + plan.schedule()
                + ":"
                + plan.reverseTraversal();
    }

    private boolean hasNonTrivialUnit(int length) {
        for (int candidate = 2;
                candidate < length - 1;
                candidate++) {
            if (NativeTextStoragePermutation.areCoprime(
                    candidate,
                    length)) {
                return true;
            }
        }
        return false;
    }
}
