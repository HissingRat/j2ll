package xyz.melodysky.runtime;

/** Stable ABI names for JDK call combinations implemented without Java method dispatch. */
public final class PureNativeJdkRuntimeHelpers {
    public static final String I32_BIG_ENDIAN_FRAME_NEW =
            "j2ll_rt_i32_be_frame_new";
    public static final String I32_BIG_ENDIAN_FRAME_WRITE =
            "j2ll_rt_i32_be_frame_write";
    public static final String I32_BIG_ENDIAN_FRAME_FINISH =
            "j2ll_rt_i32_be_frame_finish";

    private PureNativeJdkRuntimeHelpers() {
    }

    public static boolean isI32BigEndianFrameHelper(String symbol) {
        String base = baseSymbol(symbol);
        return base.equals(I32_BIG_ENDIAN_FRAME_NEW)
                || base.equals(I32_BIG_ENDIAN_FRAME_WRITE)
                || base.equals(I32_BIG_ENDIAN_FRAME_FINISH);
    }

    public static boolean returnsOperandZeroAlias(String symbol) {
        String base = baseSymbol(symbol);
        return base.equals(I32_BIG_ENDIAN_FRAME_WRITE)
                || base.equals(I32_BIG_ENDIAN_FRAME_FINISH);
    }

    private static String baseSymbol(String symbol) {
        int separator = symbol.indexOf('|');
        return separator < 0 ? symbol : symbol.substring(0, separator);
    }
}
