package xyz.melodysky.toolchain.symbols;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.toolchain.TargetTriple;

final class ElfExportTable {
    private static final int ET_DYN = 3;
    private static final int SHT_DYNSYM = 11;
    private static final int SHN_UNDEF = 0;
    private static final int STB_GLOBAL = 1;
    private static final int STB_WEAK = 2;
    private static final int STV_DEFAULT = 0;
    private static final int STV_PROTECTED = 3;

    List<String> read(BinaryData data, TargetTriple target) throws IOException {
        if (data.u8(0) != 0x7f
                || data.u8(1) != 'E'
                || data.u8(2) != 'L'
                || data.u8(3) != 'F'
                || data.u8(4) != 2
                || data.u8(5) != 1) {
            throw new IOException("selected Linux target did not produce a little-endian ELF64 image");
        }
        if (data.u16le(16) != ET_DYN) {
            throw new IOException("selected Linux target produced an ELF image that is not ET_DYN");
        }
        int machine = data.u16le(18);
        int expectedMachine = target == TargetTriple.LINUX_X64 ? 62 : 183;
        if (machine != expectedMachine) {
            throw new IOException("ELF machine mismatch for " + target.directoryName()
                    + ": expected " + expectedMachine + " but found " + machine);
        }
        int sectionTable = data.checkedOffset(data.u64le(0x28), "ELF section table");
        int sectionEntrySize = data.u16le(0x3a);
        int sectionCount = data.u16le(0x3c);
        if (sectionEntrySize < 64) {
            throw new IOException("ELF64 section entry is too small: " + sectionEntrySize);
        }
        for (int index = 0; index < sectionCount; index++) {
            int section = sectionTable + index * sectionEntrySize;
            data.require(section, 64);
            if (data.u32le(section + 4) != SHT_DYNSYM) {
                continue;
            }
            int stringsIndex = data.checkedOffset(data.u32le(section + 40), "ELF string table index");
            if (stringsIndex >= sectionCount) {
                throw new IOException("ELF dynamic symbol string table index is invalid");
            }
            int stringsSection = sectionTable + stringsIndex * sectionEntrySize;
            int stringsOffset = data.checkedOffset(data.u64le(stringsSection + 24), "ELF string table offset");
            int stringsSize = data.checkedOffset(data.u64le(stringsSection + 32), "ELF string table size");
            int symbolsOffset = data.checkedOffset(data.u64le(section + 24), "ELF dynamic symbol offset");
            long symbolsSize = data.u64le(section + 32);
            long entrySize = data.u64le(section + 56);
            if (entrySize < 24) {
                throw new IOException("ELF64 dynamic symbol entry is too small: " + entrySize);
            }
            ArrayList<String> exports = new ArrayList<>();
            for (long offset = 0; offset + entrySize <= symbolsSize; offset += entrySize) {
                int symbol = data.checkedOffset(symbolsOffset + offset, "ELF dynamic symbol");
                long nameOffset = data.u32le(symbol);
                int info = data.u8(symbol + 4);
                int other = data.u8(symbol + 5);
                int sectionIndex = data.u16le(symbol + 6);
                int binding = info >>> 4;
                int visibility = other & 0x3;
                if (nameOffset == 0
                        || sectionIndex == SHN_UNDEF
                        || (binding != STB_GLOBAL && binding != STB_WEAK)
                        || (visibility != STV_DEFAULT && visibility != STV_PROTECTED)) {
                    continue;
                }
                int name = data.checkedOffset(stringsOffset + nameOffset, "ELF symbol name");
                exports.add(data.cString(name, stringsOffset + stringsSize));
            }
            return exports.stream().filter(value -> !value.isBlank()).distinct().sorted().toList();
        }
        throw new IOException("ELF image has no dynamic symbol table");
    }
}
