package xyz.melodysky.toolchain.symbols;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.toolchain.TargetTriple;

final class PeExportTable {
    private static final int DOS_SIGNATURE = 0x5a4d;
    private static final long PE_SIGNATURE = 0x00004550L;
    private static final int PE32_PLUS_MAGIC = 0x20b;
    private static final int IMAGE_FILE_DLL = 0x2000;

    List<String> read(BinaryData data, TargetTriple target) throws IOException {
        if (data.u16le(0) != DOS_SIGNATURE) {
            throw new IOException("selected Windows target did not produce an MZ executable image");
        }
        int pe = data.checkedOffset(data.u32le(0x3c), "PE header offset");
        if (data.u32le(pe) != PE_SIGNATURE) {
            throw new IOException("selected Windows target did not produce a PE image");
        }
        int machine = data.u16le(pe + 4);
        int expectedMachine = target == TargetTriple.WINDOWS_X64 ? 0x8664 : 0xaa64;
        if (machine != expectedMachine) {
            throw new IOException("PE machine mismatch for " + target.directoryName()
                    + ": expected 0x" + Integer.toHexString(expectedMachine)
                    + " but found 0x" + Integer.toHexString(machine));
        }
        int characteristics = data.u16le(pe + 22);
        if ((characteristics & IMAGE_FILE_DLL) == 0) {
            throw new IOException("selected Windows target produced a PE image that is not a DLL");
        }
        int sectionCount = data.u16le(pe + 6);
        int optionalSize = data.u16le(pe + 20);
        int optional = pe + 24;
        if (data.u16le(optional) != PE32_PLUS_MAGIC) {
            throw new IOException("j2ll Windows targets require a PE32+ dynamic library");
        }
        long exportRva = data.u32le(optional + 112);
        if (exportRva == 0) {
            return List.of();
        }
        int sections = optional + optionalSize;
        int exportDirectory = rvaToOffset(data, exportRva, sections, sectionCount);
        int numberOfFunctions = data.checkedOffset(
                data.u32le(exportDirectory + 20),
                "PE export function count");
        int numberOfNames = data.checkedOffset(
                data.u32le(exportDirectory + 24),
                "PE export name count");
        int functions = rvaToOffset(data, data.u32le(exportDirectory + 28), sections, sectionCount);
        int names = numberOfNames == 0
                ? 0
                : rvaToOffset(data, data.u32le(exportDirectory + 32), sections, sectionCount);
        int ordinals = numberOfNames == 0
                ? 0
                : rvaToOffset(data, data.u32le(exportDirectory + 36), sections, sectionCount);
        boolean[] namedFunctions = new boolean[numberOfFunctions];
        ArrayList<String> exports = new ArrayList<>();
        for (int index = 0; index < numberOfNames; index++) {
            int entry = data.checkedOffset(names + index * 4L, "PE export name entry");
            long nameRva = data.u32le(entry);
            int name = rvaToOffset(data, nameRva, sections, sectionCount);
            int ordinal = data.u16le(data.checkedOffset(
                    ordinals + index * 2L,
                    "PE export ordinal entry"));
            if (ordinal >= numberOfFunctions) {
                throw new IOException("PE export ordinal is outside the function table: " + ordinal);
            }
            namedFunctions[ordinal] = true;
            exports.add(data.cString(name, data.length()));
        }
        for (int index = 0; index < numberOfFunctions; index++) {
            long functionRva = data.u32le(data.checkedOffset(
                    functions + index * 4L,
                    "PE export function entry"));
            if (functionRva != 0 && !namedFunctions[index]) {
                throw new IOException("PE DLL contains an ordinal-only export at index " + index);
            }
        }
        return exports.stream().filter(value -> !value.isBlank()).distinct().sorted().toList();
    }

    private int rvaToOffset(
            BinaryData data,
            long rva,
            int sectionTable,
            int sectionCount) throws IOException {
        for (int index = 0; index < sectionCount; index++) {
            int section = sectionTable + index * 40;
            data.require(section, 40);
            long virtualSize = data.u32le(section + 8);
            long virtualAddress = data.u32le(section + 12);
            long rawSize = data.u32le(section + 16);
            long rawOffset = data.u32le(section + 20);
            long mappedSize = Math.max(virtualSize, rawSize);
            if (rva >= virtualAddress && rva < virtualAddress + mappedSize) {
                return data.checkedOffset(rawOffset + (rva - virtualAddress), "PE RVA");
            }
        }
        throw new IOException("PE RVA is not mapped by any section: 0x" + Long.toHexString(rva));
    }
}
