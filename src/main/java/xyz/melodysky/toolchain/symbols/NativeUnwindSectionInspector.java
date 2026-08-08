package xyz.melodysky.toolchain.symbols;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import xyz.melodysky.toolchain.TargetTriple;

/** Strict section-table inspection for target-native unwind metadata. */
public final class NativeUnwindSectionInspector {
    private static final long ELF_MAGIC = 0x464c457fL;
    private static final int ELF_CLASS_64 = 2;
    private static final int ELF_LITTLE_ENDIAN = 1;
    private static final int ELF_CURRENT_VERSION = 1;
    private static final int ELF_ET_DYN = 3;
    private static final long ELF_SHT_PROGBITS = 1;
    private static final int ELF_SHN_XINDEX = 0xffff;

    private static final int PE_DOS_SIGNATURE = 0x5a4d;
    private static final long PE_SIGNATURE = 0x00004550L;
    private static final int PE32_PLUS_MAGIC = 0x20b;
    private static final int PE32_PLUS_MINIMUM_SIZE = 112;
    private static final int PE_IMAGE_FILE_DLL = 0x2000;

    private static final long MACH_MH_MAGIC_64 = 0xfeedfacfL;
    private static final long MACH_MH_DYLIB = 0x6L;
    private static final long MACH_LC_SEGMENT_64 = 0x19L;
    private static final int MACH_HEADER_64_SIZE = 32;
    private static final int MACH_SEGMENT_COMMAND_64_SIZE = 72;
    private static final int MACH_SECTION_64_SIZE = 80;

    public NativeUnwindSectionInspection inspect(
            TargetTriple target,
            Path libraryPath) throws IOException {
        if (libraryPath == null) {
            throw new IOException("native unwind inspection path is missing");
        }
        return inspect(target, Files.readAllBytes(libraryPath));
    }

    public NativeUnwindSectionInspection inspect(
            TargetTriple target,
            byte[] binary) throws IOException {
        if (target == null) {
            throw new IOException("native unwind inspection target is missing");
        }
        if (binary == null) {
            throw new IOException("native unwind inspection bytes are missing");
        }
        BinaryData data = new BinaryData(binary);
        Map<String, Long> sections = switch (target) {
            case LINUX_X64, LINUX_ARM64 -> inspectElf(data, target);
            case WINDOWS_X64, WINDOWS_ARM64 -> inspectPe(data, target);
            case MACOS_X64, MACOS_ARM64 -> inspectMachO(data, target);
        };
        return new NativeUnwindSectionInspection(target, sections);
    }

    private Map<String, Long> inspectElf(
            BinaryData data,
            TargetTriple target) throws IOException {
        if (data.u32le(0) != ELF_MAGIC
                || data.u8(4) != ELF_CLASS_64
                || data.u8(5) != ELF_LITTLE_ENDIAN
                || data.u8(6) != ELF_CURRENT_VERSION) {
            throw new IOException(
                    "selected Linux target did not produce a little-endian ELF64 image");
        }
        if (data.u16le(16) != ELF_ET_DYN) {
            throw new IOException(
                    "selected Linux target produced an ELF image that is not ET_DYN");
        }
        int machine = data.u16le(18);
        int expectedMachine = target == TargetTriple.LINUX_X64 ? 62 : 183;
        if (machine != expectedMachine) {
            throw new IOException("ELF machine mismatch for "
                    + target.directoryName()
                    + ": expected "
                    + expectedMachine
                    + " but found "
                    + machine);
        }
        if (data.u32le(20) != ELF_CURRENT_VERSION) {
            throw new IOException("ELF header uses an unsupported version");
        }
        if (data.u16le(0x34) < 64) {
            throw new IOException("ELF64 header is smaller than the required layout");
        }

        long sectionTableValue = data.u64le(0x28);
        int sectionEntrySize = data.u16le(0x3a);
        int declaredSectionCount = data.u16le(0x3c);
        int declaredStringsIndex = data.u16le(0x3e);
        if (sectionTableValue == 0 || sectionEntrySize < 64) {
            throw new IOException("ELF64 image has no inspectable section table");
        }
        int sectionTable = data.checkedOffset(sectionTableValue, "ELF section table");
        data.require(sectionTable, sectionEntrySize);
        int sectionCount = declaredSectionCount == 0
                ? data.checkedOffset(data.u64le(sectionTable + 32), "ELF extended section count")
                : declaredSectionCount;
        if (sectionCount <= 0) {
            throw new IOException("ELF64 image has no section headers");
        }
        int stringsIndex = declaredStringsIndex == ELF_SHN_XINDEX
                ? data.checkedOffset(data.u32le(sectionTable + 40), "ELF extended string table index")
                : declaredStringsIndex;
        if (stringsIndex <= 0 || stringsIndex >= sectionCount) {
            throw new IOException("ELF section-name string table index is invalid: " + stringsIndex);
        }
        requireTable(data, sectionTableValue, sectionEntrySize, sectionCount, "ELF section table");

        int stringsSection = entryOffset(
                data,
                sectionTableValue,
                sectionEntrySize,
                stringsIndex,
                "ELF section-name string table header");
        int stringsOffset = data.checkedOffset(
                data.u64le(stringsSection + 24),
                "ELF section-name string table offset");
        int stringsSize = data.checkedOffset(
                data.u64le(stringsSection + 32),
                "ELF section-name string table size");
        if (stringsSize <= 0) {
            throw new IOException("ELF section-name string table is empty");
        }
        data.require(stringsOffset, stringsSize);

        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (int index = 0; index < sectionCount; index++) {
            int section = entryOffset(
                    data,
                    sectionTableValue,
                    sectionEntrySize,
                    index,
                    "ELF section header");
            long nameIndex = data.u32le(section);
            if (nameIndex >= stringsSize) {
                throw new IOException("ELF section name index is outside the string table: " + nameIndex);
            }
            String name = data.cString(
                    data.checkedOffset(stringsOffset + nameIndex, "ELF section name"),
                    stringsOffset + stringsSize);
            if (!name.equals(".eh_frame") && !name.equals(".eh_frame_hdr")) {
                continue;
            }
            if (data.u32le(section + 4) != ELF_SHT_PROGBITS) {
                throw new IOException("ELF unwind section is not SHT_PROGBITS: " + name);
            }
            long size = data.u64le(section + 32);
            long offset = data.u64le(section + 24);
            requireNonZeroPayloadOffset(offset, size, "ELF unwind section " + name);
            requirePayload(
                    data,
                    offset,
                    size,
                    "ELF unwind section " + name);
            addUnique(result, name, size);
        }
        return Map.copyOf(result);
    }

    private Map<String, Long> inspectPe(
            BinaryData data,
            TargetTriple target) throws IOException {
        if (data.u16le(0) != PE_DOS_SIGNATURE) {
            throw new IOException("selected Windows target did not produce an MZ executable image");
        }
        int pe = data.checkedOffset(data.u32le(0x3c), "PE header offset");
        if (data.u32le(pe) != PE_SIGNATURE) {
            throw new IOException("selected Windows target did not produce a PE image");
        }
        int machine = data.u16le(pe + 4);
        int expectedMachine = target == TargetTriple.WINDOWS_X64 ? 0x8664 : 0xaa64;
        if (machine != expectedMachine) {
            throw new IOException("PE machine mismatch for "
                    + target.directoryName()
                    + ": expected 0x"
                    + Integer.toHexString(expectedMachine)
                    + " but found 0x"
                    + Integer.toHexString(machine));
        }
        int sectionCount = data.u16le(pe + 6);
        int optionalSize = data.u16le(pe + 20);
        int characteristics = data.u16le(pe + 22);
        if ((characteristics & PE_IMAGE_FILE_DLL) == 0) {
            throw new IOException("selected Windows target produced a PE image that is not a DLL");
        }
        int optional = data.checkedOffset(pe + 24L, "PE optional header");
        data.require(optional, optionalSize);
        if (optionalSize < PE32_PLUS_MINIMUM_SIZE) {
            throw new IOException("PE32+ optional header is truncated");
        }
        if (data.u16le(optional) != PE32_PLUS_MAGIC) {
            throw new IOException("j2ll Windows targets require a PE32+ dynamic library");
        }
        if (sectionCount <= 0) {
            throw new IOException("PE32+ DLL has no section headers");
        }
        long sectionTableValue = optional + (long) optionalSize;
        requireTable(data, sectionTableValue, 40, sectionCount, "PE section table");

        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (int index = 0; index < sectionCount; index++) {
            int section = entryOffset(
                    data,
                    sectionTableValue,
                    40,
                    index,
                    "PE section header");
            String name = data.fixedAsciiString(section, 8);
            if (!name.equals(".pdata") && !name.equals(".xdata")) {
                continue;
            }
            long virtualSize = data.u32le(section + 8);
            long rawSize = data.u32le(section + 16);
            long effectiveSize = virtualSize == 0 ? rawSize : virtualSize;
            if (effectiveSize > 0 && rawSize == 0) {
                throw new IOException("PE unwind section has no file-backed data: " + name);
            }
            long rawOffset = data.u32le(section + 20);
            requireNonZeroPayloadOffset(rawOffset, rawSize, "PE unwind section " + name);
            requirePayload(
                    data,
                    rawOffset,
                    rawSize,
                    "PE unwind section " + name);
            addUnique(result, name, effectiveSize);
        }
        return Map.copyOf(result);
    }

    private Map<String, Long> inspectMachO(
            BinaryData data,
            TargetTriple target) throws IOException {
        if (data.u32le(0) != MACH_MH_MAGIC_64) {
            throw new IOException(
                    "selected macOS target did not produce a little-endian Mach-O 64 image");
        }
        long cpuType = data.u32le(4);
        long expectedCpu = target == TargetTriple.MACOS_X64 ? 0x01000007L : 0x0100000cL;
        if (cpuType != expectedCpu) {
            throw new IOException("Mach-O CPU type mismatch for "
                    + target.directoryName()
                    + ": expected 0x"
                    + Long.toHexString(expectedCpu)
                    + " but found 0x"
                    + Long.toHexString(cpuType));
        }
        if (data.u32le(12) != MACH_MH_DYLIB) {
            throw new IOException("selected macOS target produced a Mach-O image that is not MH_DYLIB");
        }
        int commandCount = data.checkedOffset(data.u32le(16), "Mach-O load command count");
        int commandBytes = data.checkedOffset(data.u32le(20), "Mach-O load command bytes");
        if (commandCount <= 0 || commandBytes <= 0) {
            throw new IOException("Mach-O dylib has no load commands");
        }
        int commandsEnd = data.checkedOffset(
                MACH_HEADER_64_SIZE + (long) commandBytes,
                "Mach-O load command boundary");
        data.require(MACH_HEADER_64_SIZE, commandBytes);

        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        int command = MACH_HEADER_64_SIZE;
        boolean sawSegment = false;
        for (int index = 0; index < commandCount; index++) {
            data.require(command, 8);
            long type = data.u32le(command);
            int size = data.checkedOffset(data.u32le(command + 4), "Mach-O load command size");
            if (size < 8 || command > commandsEnd - size) {
                throw new IOException("Mach-O load command exceeds the declared command area");
            }
            if (type == MACH_LC_SEGMENT_64) {
                sawSegment = true;
                inspectMachSegment(data, command, size, result);
            }
            command += size;
        }
        if (command != commandsEnd) {
            throw new IOException("Mach-O load command sizes do not match sizeofcmds");
        }
        if (!sawSegment) {
            throw new IOException("Mach-O dylib has no LC_SEGMENT_64 command");
        }
        return Map.copyOf(result);
    }

    private void inspectMachSegment(
            BinaryData data,
            int command,
            int commandSize,
            Map<String, Long> result) throws IOException {
        if (commandSize < MACH_SEGMENT_COMMAND_64_SIZE) {
            throw new IOException("Mach-O LC_SEGMENT_64 command is truncated");
        }
        int sectionCount = data.checkedOffset(
                data.u32le(command + 64),
                "Mach-O segment section count");
        long requiredSize = MACH_SEGMENT_COMMAND_64_SIZE
                + (long) sectionCount * MACH_SECTION_64_SIZE;
        if (requiredSize > commandSize) {
            throw new IOException("Mach-O LC_SEGMENT_64 section table exceeds its command");
        }
        for (int index = 0; index < sectionCount; index++) {
            int section = data.checkedOffset(
                    command
                            + MACH_SEGMENT_COMMAND_64_SIZE
                            + (long) index * MACH_SECTION_64_SIZE,
                    "Mach-O section header");
            data.require(section, MACH_SECTION_64_SIZE);
            String name = data.fixedAsciiString(section, 16);
            if (!name.equals("__eh_frame") && !name.equals("__unwind_info")) {
                continue;
            }
            long size = data.u64le(section + 40);
            long offset = data.u32le(section + 48);
            requireNonZeroPayloadOffset(offset, size, "Mach-O unwind section " + name);
            requirePayload(
                    data,
                    offset,
                    size,
                    "Mach-O unwind section " + name);
            addUnique(result, name, size);
        }
    }

    private void requireTable(
            BinaryData data,
            long offset,
            int entrySize,
            int entryCount,
            String label) throws IOException {
        long size;
        try {
            size = Math.multiplyExact((long) entrySize, entryCount);
        } catch (ArithmeticException exception) {
            throw new IOException(label + " size overflows", exception);
        }
        requirePayload(data, offset, size, label);
    }

    private int entryOffset(
            BinaryData data,
            long tableOffset,
            int entrySize,
            int index,
            String label) throws IOException {
        long offset;
        try {
            offset = Math.addExact(tableOffset, Math.multiplyExact((long) entrySize, index));
        } catch (ArithmeticException exception) {
            throw new IOException(label + " offset overflows", exception);
        }
        int checked = data.checkedOffset(offset, label);
        data.require(checked, entrySize);
        return checked;
    }

    private void requirePayload(
            BinaryData data,
            long offset,
            long size,
            String label) throws IOException {
        int checkedOffset = data.checkedOffset(offset, label + " offset");
        int checkedSize = data.checkedOffset(size, label + " size");
        data.require(checkedOffset, checkedSize);
    }

    private void requireNonZeroPayloadOffset(
            long offset,
            long size,
            String label) throws IOException {
        if (size > 0 && offset == 0) {
            throw new IOException(label + " has a zero file offset");
        }
    }

    private void addUnique(
            Map<String, Long> result,
            String name,
            long size) throws IOException {
        if (result.putIfAbsent(name, size) != null) {
            throw new IOException("native binary contains duplicate unwind section " + name);
        }
    }
}
