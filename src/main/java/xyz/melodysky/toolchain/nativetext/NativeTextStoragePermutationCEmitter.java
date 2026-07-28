package xyz.melodysky.toolchain.nativetext;

/**
 * Emits the constant-size physical cursor for one affine ciphertext layout.
 */
final class NativeTextStoragePermutationCEmitter {
    String cursorDeclaration(
            NativeTextEncoding encoding,
            String token,
            String indent) {
        NativeTextCodecPlan codec = encoding.codecPlan();
        NativeTextStoragePermutation storage =
                encoding.storagePermutation();
        int initialLogicalIndex = codec.reverseTraversal()
                ? storage.length() - 1
                : 0;
        int initialStorageIndex =
                storage.physicalIndex(initialLogicalIndex);
        return indent
                + "size_t j2ll_nt_s_"
                + token
                + " = (size_t)UINT64_C("
                + initialStorageIndex
                + ");\n";
    }

    String cursorAdvance(
            NativeTextEncoding encoding,
            String token,
            String lengthExpression,
            String indent) {
        NativeTextCodecPlan codec = encoding.codecPlan();
        NativeTextStoragePermutation storage =
                encoding.storagePermutation();
        String storageIndex = "j2ll_nt_s_" + token;
        String stride = "UINT64_C(" + storage.stride() + ")";
        if (codec.reverseTraversal()) {
            return indent
                    + storageIndex
                    + " += "
                    + storageIndex
                    + " < "
                    + stride
                    + " ? "
                    + lengthExpression
                    + " : 0u;\n"
                    + indent
                    + storageIndex
                    + " -= "
                    + stride
                    + ";\n";
        }
        return indent
                + storageIndex
                + " += "
                + stride
                + ";\n"
                + indent
                + storageIndex
                + " -= "
                + storageIndex
                + " >= "
                + lengthExpression
                + " ? "
                + lengthExpression
                + " : 0u;\n";
    }
}
