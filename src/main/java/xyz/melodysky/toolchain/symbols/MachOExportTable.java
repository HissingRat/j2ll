package xyz.melodysky.toolchain.symbols;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.toolchain.TargetTriple;

final class MachOExportTable {
    private static final long MH_MAGIC_64 = 0xfeedfacfL;
    private static final long MH_DYLIB = 0x6L;
    private static final long LC_SYMTAB = 0x2L;
    private static final long LC_DYLD_INFO = 0x22L;
    private static final long LC_DYLD_INFO_ONLY = 0x80000022L;
    private static final long LC_DYLD_EXPORTS_TRIE = 0x80000033L;
    private static final int N_STAB = 0xe0;
    private static final int N_PEXT = 0x10;
    private static final int N_TYPE = 0x0e;
    private static final int N_UNDF = 0x00;
    private static final int N_EXT = 0x01;

    List<String> read(BinaryData data, TargetTriple target) throws IOException {
        if (data.u32le(0) != MH_MAGIC_64) {
            throw new IOException("selected macOS target did not produce a little-endian Mach-O 64 image");
        }
        if (data.u32le(12) != MH_DYLIB) {
            throw new IOException("selected macOS target produced a Mach-O image that is not MH_DYLIB");
        }
        long cpuType = data.u32le(4);
        long expectedCpu = target == TargetTriple.MACOS_X64 ? 0x01000007L : 0x0100000cL;
        if (cpuType != expectedCpu) {
            throw new IOException("Mach-O CPU type mismatch for " + target.directoryName()
                    + ": expected 0x" + Long.toHexString(expectedCpu)
                    + " but found 0x" + Long.toHexString(cpuType));
        }
        int commandCount = data.checkedOffset(data.u32le(16), "Mach-O load command count");
        int command = 32;
        int symbolTableCommand = -1;
        int exportsOffset = 0;
        int exportsSize = 0;
        for (int index = 0; index < commandCount; index++) {
            long type = data.u32le(command);
            int size = data.checkedOffset(data.u32le(command + 4), "Mach-O load command size");
            if (size < 8) {
                throw new IOException("Mach-O load command is too small: " + size);
            }
            if (type == LC_SYMTAB) {
                symbolTableCommand = command;
            } else if (type == LC_DYLD_EXPORTS_TRIE) {
                exportsOffset = data.checkedOffset(data.u32le(command + 8), "Mach-O export trie offset");
                exportsSize = data.checkedOffset(data.u32le(command + 12), "Mach-O export trie size");
            } else if (type == LC_DYLD_INFO || type == LC_DYLD_INFO_ONLY) {
                exportsOffset = data.checkedOffset(data.u32le(command + 40), "Mach-O export info offset");
                exportsSize = data.checkedOffset(data.u32le(command + 44), "Mach-O export info size");
            }
            command += size;
            data.require(command, index + 1 == commandCount ? 0 : 8);
        }
        if (exportsSize > 0) {
            return new MachOExportTrie().read(data, exportsOffset, exportsSize);
        }
        if (symbolTableCommand >= 0) {
            return readSymbolTable(data, symbolTableCommand);
        }
        throw new IOException("Mach-O image has no symbol table");
    }

    private List<String> readSymbolTable(BinaryData data, int command) throws IOException {
        int symbolsOffset = data.checkedOffset(data.u32le(command + 8), "Mach-O symbol table offset");
        int symbolCount = data.checkedOffset(data.u32le(command + 12), "Mach-O symbol count");
        int stringsOffset = data.checkedOffset(data.u32le(command + 16), "Mach-O string table offset");
        int stringsSize = data.checkedOffset(data.u32le(command + 20), "Mach-O string table size");
        ArrayList<String> exports = new ArrayList<>();
        for (int index = 0; index < symbolCount; index++) {
            int symbol = symbolsOffset + index * 16;
            data.require(symbol, 16);
            long stringIndex = data.u32le(symbol);
            int type = data.u8(symbol + 4);
            if (stringIndex == 0
                    || (type & N_STAB) != 0
                    || (type & N_EXT) == 0
                    || (type & N_PEXT) != 0
                    || (type & N_TYPE) == N_UNDF) {
                continue;
            }
            int nameOffset = data.checkedOffset(stringsOffset + stringIndex, "Mach-O symbol name");
            String name = data.cString(nameOffset, stringsOffset + stringsSize);
            exports.add(name.startsWith("_") ? name.substring(1) : name);
        }
        return exports.stream().filter(value -> !value.isBlank()).distinct().sorted().toList();
    }
}
