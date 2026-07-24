package xyz.melodysky.toolchain.symbols;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MachOExportTrie {
    List<String> read(BinaryData data, int trieOffset, int trieSize) throws IOException {
        data.require(trieOffset, trieSize);
        ArrayList<String> exports = new ArrayList<>();
        walk(data, trieOffset, trieOffset + trieSize, 0, "", new HashSet<>(), exports);
        return exports.stream()
                .map(name -> name.startsWith("_") ? name.substring(1) : name)
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private void walk(
            BinaryData data,
            int trieStart,
            int trieEnd,
            int nodeOffset,
            String prefix,
            Set<Integer> activePath,
            List<String> exports) throws IOException {
        if (nodeOffset < 0 || nodeOffset >= trieEnd - trieStart) {
            throw new IOException("Mach-O export trie node is outside the trie: " + nodeOffset);
        }
        if (!activePath.add(nodeOffset)) {
            throw new IOException("Mach-O export trie contains a cycle at node " + nodeOffset);
        }
        int cursor = trieStart + nodeOffset;
        Uleb terminalSize = readUleb(data, cursor, trieEnd);
        cursor = terminalSize.nextOffset();
        int children = data.checkedOffset(cursor + terminalSize.value(), "Mach-O export trie terminal");
        if (children > trieEnd) {
            throw new IOException("Mach-O export trie terminal exceeds trie bounds");
        }
        if (terminalSize.value() > 0) {
            exports.add(prefix);
        }
        cursor = children;
        int childCount = data.u8(cursor++);
        for (int index = 0; index < childCount; index++) {
            int edgeStart = cursor;
            while (cursor < trieEnd && data.u8(cursor) != 0) {
                cursor++;
            }
            if (cursor >= trieEnd) {
                throw new IOException("unterminated Mach-O export trie edge");
            }
            String edge = data.cString(edgeStart, trieEnd);
            cursor++;
            Uleb child = readUleb(data, cursor, trieEnd);
            cursor = child.nextOffset();
            walk(
                    data,
                    trieStart,
                    trieEnd,
                    data.checkedOffset(child.value(), "Mach-O export trie child"),
                    prefix + edge,
                    activePath,
                    exports);
        }
        activePath.remove(nodeOffset);
    }

    private Uleb readUleb(BinaryData data, int offset, int limit) throws IOException {
        long value = 0;
        int shift = 0;
        int cursor = offset;
        while (cursor < limit) {
            int current = data.u8(cursor++);
            if (shift >= 64 || ((long) (current & 0x7f) << shift >>> shift) != (current & 0x7f)) {
                throw new IOException("Mach-O export trie ULEB128 overflows 64 bits");
            }
            value |= (long) (current & 0x7f) << shift;
            if ((current & 0x80) == 0) {
                return new Uleb(value, cursor);
            }
            shift += 7;
        }
        throw new IOException("truncated Mach-O export trie ULEB128");
    }

    private record Uleb(long value, int nextOffset) {
    }
}
