package xyz.melodysky.toolchain.symbols;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class BinaryData {
    private final byte[] bytes;

    BinaryData(byte[] bytes) {
        this.bytes = bytes;
    }

    int length() {
        return bytes.length;
    }

    int u8(int offset) throws IOException {
        require(offset, 1);
        return Byte.toUnsignedInt(bytes[offset]);
    }

    int u16le(int offset) throws IOException {
        require(offset, 2);
        return u8(offset) | (u8(offset + 1) << 8);
    }

    long u32le(int offset) throws IOException {
        require(offset, 4);
        return Integer.toUnsignedLong(
                u8(offset)
                        | (u8(offset + 1) << 8)
                        | (u8(offset + 2) << 16)
                        | (u8(offset + 3) << 24));
    }

    long u64le(int offset) throws IOException {
        require(offset, 8);
        return u32le(offset) | (u32le(offset + 4) << 32);
    }

    String cString(int offset, int limit) throws IOException {
        require(offset, 1);
        int end = offset;
        int maximum = Math.min(bytes.length, Math.max(offset, limit));
        while (end < maximum && bytes[end] != 0) {
            end++;
        }
        if (end == maximum) {
            throw new IOException("unterminated native symbol string at file offset " + offset);
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    int checkedOffset(long value, String label) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException(label + " is outside the supported binary size: " + value);
        }
        return (int) value;
    }

    void require(int offset, int size) throws IOException {
        if (offset < 0 || size < 0 || offset > bytes.length - size) {
            throw new IOException("native binary structure exceeds file bounds at " + offset + " size " + size);
        }
    }
}
