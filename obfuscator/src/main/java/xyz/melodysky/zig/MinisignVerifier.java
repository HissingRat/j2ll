package xyz.melodysky.zig;

import com.rfksystems.blake2b.security.Blake2b512Digest;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

final class MinisignVerifier {

    private static final String UNTRUSTED_COMMENT_PREFIX = "untrusted comment: ";
    private static final String TRUSTED_COMMENT_PREFIX = "trusted comment: ";
    private static final byte[] MINISIGN_PUBLIC_KEY_ALGORITHM = new byte[]{'E', 'd'};
    private static final byte[] MINISIGN_SIGNATURE_ALGORITHM = new byte[]{'E', 'd'};
    private static final byte[] MINISIGN_PREHASHED_SIGNATURE_ALGORITHM = new byte[]{'E', 'D'};
    private static final int MINISIGN_PUBLIC_KEY_LENGTH = 42;
    private static final int MINISIGN_SIGNATURE_LENGTH = 74;
    private static final int ED25519_SIGNATURE_LENGTH = 64;
    private static final int FILE_COPY_BUFFER_SIZE = 64 * 1024;

    private MinisignVerifier() {
    }

    static void verify(Path archive, Path signatureFile, String expectedArchiveName, String encodedPublicKey) throws Exception {
        MinisignPublicKey publicKey = parseMinisignPublicKey(encodedPublicKey);
        MinisignSignature signature = parseMinisignSignature(signatureFile);
        if (!Arrays.equals(signature.keyId, publicKey.keyId)) {
            throw new IllegalStateException("Minisign key id does not match the configured Zig public key");
        }

        validateTrustedComment(signature.trustedComment, expectedArchiveName, signature.prehashed);

        byte[] message = signature.prehashed ? hashFileBlake2b512(archive) : Files.readAllBytes(archive);
        verifyEd25519Signature(publicKey.publicKey, signature.signature, message, "archive");
        verifyEd25519Signature(publicKey.publicKey, signature.globalSignature,
                concatenate(signature.signature, signature.trustedComment.getBytes(StandardCharsets.UTF_8)),
                "trusted comment");
    }

    private static MinisignPublicKey parseMinisignPublicKey(String encodedKey) {
        byte[] decoded = Base64.getDecoder().decode(encodedKey);
        if (decoded.length != MINISIGN_PUBLIC_KEY_LENGTH) {
            throw new IllegalStateException("Unexpected minisign public key length: " + decoded.length);
        }
        byte[] algorithm = Arrays.copyOfRange(decoded, 0, 2);
        if (!Arrays.equals(algorithm, MINISIGN_PUBLIC_KEY_ALGORITHM)) {
            throw new IllegalStateException("Unsupported minisign public key algorithm");
        }
        return new MinisignPublicKey(
                Arrays.copyOfRange(decoded, 2, 10),
                Arrays.copyOfRange(decoded, 10, decoded.length)
        );
    }

    private static MinisignSignature parseMinisignSignature(Path signatureFile) throws Exception {
        List<String> lines = Files.readAllLines(signatureFile, StandardCharsets.UTF_8);
        if (lines.size() < 4) {
            throw new IllegalStateException("Invalid minisig file: " + signatureFile);
        }
        if (!lines.getFirst().startsWith(UNTRUSTED_COMMENT_PREFIX)) {
            throw new IllegalStateException("Invalid minisig header in " + signatureFile);
        }

        byte[] signatureBlock = Base64.getDecoder().decode(lines.get(1).trim());
        if (signatureBlock.length != MINISIGN_SIGNATURE_LENGTH) {
            throw new IllegalStateException("Unexpected minisig signature length: " + signatureBlock.length);
        }

        byte[] algorithm = Arrays.copyOfRange(signatureBlock, 0, 2);
        boolean prehashed;
        if (Arrays.equals(algorithm, MINISIGN_SIGNATURE_ALGORITHM)) {
            prehashed = false;
        } else if (Arrays.equals(algorithm, MINISIGN_PREHASHED_SIGNATURE_ALGORITHM)) {
            prehashed = true;
        } else {
            throw new IllegalStateException("Unsupported minisign signature algorithm");
        }

        String trustedCommentLine = lines.get(2);
        if (!trustedCommentLine.startsWith(TRUSTED_COMMENT_PREFIX)) {
            throw new IllegalStateException("Missing trusted minisig comment in " + signatureFile);
        }
        String trustedComment = trustedCommentLine.substring(TRUSTED_COMMENT_PREFIX.length());

        byte[] globalSignature = Base64.getDecoder().decode(lines.get(3).trim());
        if (globalSignature.length != ED25519_SIGNATURE_LENGTH) {
            throw new IllegalStateException("Unexpected trusted comment signature length: " + globalSignature.length);
        }

        return new MinisignSignature(
                Arrays.copyOfRange(signatureBlock, 2, 10),
                Arrays.copyOfRange(signatureBlock, 10, signatureBlock.length),
                trustedComment,
                globalSignature,
                prehashed
        );
    }

    private static void validateTrustedComment(String trustedComment, String expectedArchiveName, boolean prehashed) {
        boolean fileNameMatched = false;
        boolean hashedFlagPresent = false;
        for (String part : trustedComment.split("\t")) {
            if (part.equals("file:" + expectedArchiveName)) {
                fileNameMatched = true;
            }
            if (part.equals("hashed")) {
                hashedFlagPresent = true;
            }
        }
        if (!fileNameMatched) {
            throw new IllegalStateException("Minisig trusted comment does not match expected archive " + expectedArchiveName);
        }
        if (prehashed && !hashedFlagPresent) {
            throw new IllegalStateException("Prehashed minisig is missing the hashed trusted comment flag");
        }
    }

    private static byte[] hashFileBlake2b512(Path archive) throws Exception {
        Blake2b512Digest digest = new Blake2b512Digest();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(archive))) {
            byte[] buffer = new byte[FILE_COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static void verifyEd25519Signature(byte[] publicKey, byte[] signature, byte[] message, String label) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            verifier.initVerify(keyFactory.generatePublic(new X509EncodedKeySpec(encodeSubjectPublicKeyInfo(publicKey))));
            verifier.update(message);
            if (verifier.verify(signature)) {
                return;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Minisig " + label + " verification failed", exception);
        }
        throw new IllegalStateException("Minisig " + label + " verification failed");
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    private static byte[] encodeSubjectPublicKeyInfo(byte[] key) {
        byte[] prefix = new byte[]{
                0x30, 0x2A,
                0x30, 0x05,
                0x06, 0x03, 0x2B, 0x65, 0x70,
                0x03, 0x21, 0x00
        };
        byte[] encoded = Arrays.copyOf(prefix, prefix.length + key.length);
        System.arraycopy(key, 0, encoded, prefix.length, key.length);
        return encoded;
    }

    private static class MinisignPublicKey {
        private final byte[] keyId;
        private final byte[] publicKey;

        private MinisignPublicKey(byte[] keyId, byte[] publicKey) {
            this.keyId = keyId;
            this.publicKey = publicKey;
        }
    }

    private static class MinisignSignature {
        private final byte[] keyId;
        private final byte[] signature;
        private final String trustedComment;
        private final byte[] globalSignature;
        private final boolean prehashed;

        private MinisignSignature(byte[] keyId, byte[] signature, String trustedComment,
                                  byte[] globalSignature, boolean prehashed) {
            this.keyId = keyId;
            this.signature = signature;
            this.trustedComment = trustedComment;
            this.globalSignature = globalSignature;
            this.prehashed = prehashed;
        }
    }
}
