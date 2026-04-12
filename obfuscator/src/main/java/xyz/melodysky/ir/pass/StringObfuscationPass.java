package xyz.melodysky.ir.pass;

import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class StringObfuscationPass implements IrMethodPass {

    private static final String STRING_HELPER_PREFIX = "ir_rt_ldc_string__";
    private static final String CONCAT_HELPER_PREFIX = "ir_rt_concat__";

    private final boolean cacheStrings;
    private final SecureRandom random;
    private final AtomicInteger nextSiteId = new AtomicInteger(1);

    public StringObfuscationPass(boolean cacheStrings) {
        this(cacheStrings, new SecureRandom());
    }

    StringObfuscationPass(boolean cacheStrings, SecureRandom random) {
        this.cacheStrings = cacheStrings;
        this.random = random;
    }

    @Override
    public String name() {
        return "string-obfuscation";
    }

    @Override
    public IrMethod apply(IrMethod method) {
        ArrayList<IrBlock> rewrittenBlocks = new ArrayList<>(method.blocks().size());
        for (IrBlock block : method.blocks()) {
            ArrayList<IrInstruction> rewrittenInstructions = new ArrayList<>(block.instructions().size());
            for (IrInstruction instruction : block.instructions()) {
                rewrittenInstructions.add(rewriteInstruction(instruction));
            }
            rewrittenBlocks.add(new IrBlock(block.label(), rewrittenInstructions, block.terminator()));
        }
        return new IrMethod(
                method.name(),
                method.returnType(),
                method.parameterTypes(),
                method.maxLocals(),
                method.isStatic(),
                method.isPrivate(),
                method.isFinal(),
                method.entryBlock(),
                rewrittenBlocks
        );
    }

    private IrInstruction rewriteInstruction(IrInstruction instruction) {
        if (instruction instanceof IrInstruction.CallHelper helper) {
            String obfuscatedName = obfuscateHelperName(helper.helperName());
            if (!obfuscatedName.equals(helper.helperName())) {
                return new IrInstruction.CallHelper(helper.result(), obfuscatedName, helper.arguments());
            }
        }
        if (instruction instanceof IrInstruction.CallHelperVoid helper) {
            String obfuscatedName = obfuscateHelperName(helper.helperName());
            if (!obfuscatedName.equals(helper.helperName())) {
                return new IrInstruction.CallHelperVoid(obfuscatedName, helper.arguments());
            }
        }
        return instruction;
    }

    private String obfuscateHelperName(String helperName) {
        if (helperName.startsWith(STRING_HELPER_PREFIX)) {
            return obfuscateStringHelper(helperName.substring(STRING_HELPER_PREFIX.length()));
        }
        if (helperName.startsWith(CONCAT_HELPER_PREFIX)) {
            return obfuscateConcatHelper(helperName);
        }
        return helperName;
    }

    private String obfuscateStringHelper(String stringHex) {
        byte[] plaintext = decodeHex(stringHex);
        ObfuscatedEntry entry = obfuscate(plaintext);
        return new StringBuilder("ir_rt_sobf__")
                .append(cacheStrings ? '1' : '0')
                .append("__")
                .append(hexSite(entry.siteId()))
                .append("__")
                .append(hex(entry.nonce()))
                .append("__")
                .append(hex(entry.seedA()))
                .append("__")
                .append(hex(entry.seedB()))
                .append("__")
                .append(hex(entry.ciphertext()))
                .toString();
    }

    private String obfuscateConcatHelper(String helperName) {
        String[] pieces = helperName.split("__");
        if (pieces.length < 2) {
            return helperName;
        }
        byte[] recipeBytes = decodeHex(pieces[1]);
        ObfuscatedEntry entry = obfuscate(recipeBytes);
        StringBuilder builder = new StringBuilder("ir_rt_sobf_concat__")
                .append(hexSite(entry.siteId()))
                .append("__")
                .append(hex(entry.nonce()))
                .append("__")
                .append(hex(entry.seedA()))
                .append("__")
                .append(hex(entry.seedB()))
                .append("__")
                .append(hex(entry.ciphertext()));
        for (int index = 2; index < pieces.length; index++) {
            builder.append("__").append(pieces[index]);
        }
        return builder.toString();
    }

    private ObfuscatedEntry obfuscate(byte[] plaintext) {
        int siteId = nextSiteId.getAndIncrement();
        byte[] nonce = randomBytes(12);
        byte[] seedA = randomBytes(32);
        byte[] seedB = randomBytes(32);
        byte[] key = deriveKey(seedA, seedB, siteId);
        byte[] ciphertext = chacha20Xor(key, nonce, plaintext);
        return new ObfuscatedEntry(siteId, nonce, seedA, seedB, ciphertext);
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    static byte[] deriveKey(byte[] seedA, byte[] seedB, int siteId) {
        if (seedA.length != 32 || seedB.length != 32) {
            throw new IllegalArgumentException("ChaCha20 string seeds must be 32 bytes");
        }
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            int mix = seedB[(index * 7 + siteId) & 31] & 0xff;
            int shift = (siteId + index) & 7;
            int rotated = shift == 0 ? mix : ((mix << shift) | (mix >>> (8 - shift))) & 0xff;
            int spice = (siteId * 131 + index * 17) & 0xff;
            key[index] = (byte) ((seedA[index] & 0xff) ^ rotated ^ spice);
        }
        return key;
    }

    static byte[] chacha20Xor(byte[] key, byte[] nonce, byte[] input) {
        if (key.length != 32) {
            throw new IllegalArgumentException("ChaCha20 key must be 32 bytes");
        }
        if (nonce.length != 12) {
            throw new IllegalArgumentException("ChaCha20 nonce must be 12 bytes");
        }
        byte[] output = new byte[input.length];
        int counter = 1;
        int offset = 0;
        byte[] block = new byte[64];
        while (offset < input.length) {
            chacha20Block(key, nonce, counter++, block);
            int blockLength = Math.min(64, input.length - offset);
            for (int index = 0; index < blockLength; index++) {
                output[offset + index] = (byte) (input[offset + index] ^ block[index]);
            }
            offset += blockLength;
        }
        return output;
    }

    private static void chacha20Block(byte[] key, byte[] nonce, int counter, byte[] out) {
        int[] state = new int[16];
        state[0] = 0x61707865;
        state[1] = 0x3320646e;
        state[2] = 0x79622d32;
        state[3] = 0x6b206574;
        for (int index = 0; index < 8; index++) {
            state[4 + index] = littleEndianInt(key, index * 4);
        }
        state[12] = counter;
        state[13] = littleEndianInt(nonce, 0);
        state[14] = littleEndianInt(nonce, 4);
        state[15] = littleEndianInt(nonce, 8);

        int[] working = state.clone();
        for (int round = 0; round < 10; round++) {
            quarterRound(working, 0, 4, 8, 12);
            quarterRound(working, 1, 5, 9, 13);
            quarterRound(working, 2, 6, 10, 14);
            quarterRound(working, 3, 7, 11, 15);
            quarterRound(working, 0, 5, 10, 15);
            quarterRound(working, 1, 6, 11, 12);
            quarterRound(working, 2, 7, 8, 13);
            quarterRound(working, 3, 4, 9, 14);
        }

        for (int index = 0; index < 16; index++) {
            int value = working[index] + state[index];
            out[index * 4] = (byte) value;
            out[index * 4 + 1] = (byte) (value >>> 8);
            out[index * 4 + 2] = (byte) (value >>> 16);
            out[index * 4 + 3] = (byte) (value >>> 24);
        }
    }

    private static void quarterRound(int[] state, int a, int b, int c, int d) {
        state[a] += state[b];
        state[d] = Integer.rotateLeft(state[d] ^ state[a], 16);
        state[c] += state[d];
        state[b] = Integer.rotateLeft(state[b] ^ state[c], 12);
        state[a] += state[b];
        state[d] = Integer.rotateLeft(state[d] ^ state[a], 8);
        state[c] += state[d];
        state[b] = Integer.rotateLeft(state[b] ^ state[c], 7);
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static byte[] decodeHex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index + 1 < hex.length(); index += 2) {
            bytes[index / 2] = (byte) Integer.parseInt(hex.substring(index, index + 2), 16);
        }
        return bytes;
    }

    private static String hexSite(int siteId) {
        return String.format("%08x", siteId);
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            builder.append(String.format("%02x", current & 0xff));
        }
        return builder.toString();
    }

    private record ObfuscatedEntry(int siteId, byte[] nonce, byte[] seedA, byte[] seedB, byte[] ciphertext) {
    }
}
