package xyz.melodysky.toolchain.nativetext;

/** Test-only access to preserve an encoding while replacing its C symbol. */
public final class NativeTextEncodingTestFixture {
    private NativeTextEncodingTestFixture() {}

    public static NativeTextEncoding withSymbol(
            NativeTextEncoding source,
            String symbol) {
        return new NativeTextEncoding(
                symbol,
                source.purpose(),
                source.utf8Length(),
                source.ciphertext(),
                source.codecPlan(),
                source.storagePermutation());
    }
}
