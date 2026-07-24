package xyz.melodysky.toolchain.symbols;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class NativeBinaryPrivacyInspector {
    private static final int PE_SIGNATURE = 0x00004550;
    private static final int PE32_MAGIC = 0x10b;
    private static final int PE32_PLUS_MAGIC = 0x20b;
    private static final int IMAGE_DIRECTORY_ENTRY_DEBUG = 6;
    private static final int IMAGE_DEBUG_DIRECTORY_SIZE = 28;
    private static final int IMAGE_DEBUG_TYPE_CODEVIEW = 2;
    private static final int IMAGE_DEBUG_TYPE_REPRO = 16;

    public PePrivacyInfo inspectPe(Path binary) throws IOException {
        return inspectPe(Files.readAllBytes(binary));
    }

    public PePrivacyInfo inspectPe(byte[] bytes) throws IOException {
        int peOffset = checkedOffset(unsigned32(bytes, 0x3c), "PE header");
        if (unsigned32(bytes, peOffset) != PE_SIGNATURE) {
            throw new IOException("invalid PE signature");
        }

        int fileHeader = peOffset + 4;
        int sectionCount = unsigned16(bytes, fileHeader + 2);
        long symbolTablePointer = unsigned32(bytes, fileHeader + 8);
        long symbolCount = unsigned32(bytes, fileHeader + 12);
        int optionalHeaderSize = unsigned16(bytes, fileHeader + 16);
        int optionalHeader = fileHeader + 20;
        requireRange(bytes, optionalHeader, optionalHeaderSize, "PE optional header");

        int magic = unsigned16(bytes, optionalHeader);
        int numberOfDirectoriesOffset;
        int dataDirectoriesOffset;
        if (magic == PE32_PLUS_MAGIC) {
            numberOfDirectoriesOffset = 108;
            dataDirectoriesOffset = 112;
        } else if (magic == PE32_MAGIC) {
            numberOfDirectoriesOffset = 92;
            dataDirectoriesOffset = 96;
        } else {
            throw new IOException("unsupported PE optional header magic: 0x" + Integer.toHexString(magic));
        }

        int directoryEnd = dataDirectoriesOffset + (IMAGE_DIRECTORY_ENTRY_DEBUG + 1) * 8;
        if (optionalHeaderSize < directoryEnd) {
            return new PePrivacyInfo(symbolTablePointer, symbolCount, List.of());
        }
        long directoryCount = unsigned32(bytes, optionalHeader + numberOfDirectoriesOffset);
        if (directoryCount <= IMAGE_DIRECTORY_ENTRY_DEBUG) {
            return new PePrivacyInfo(symbolTablePointer, symbolCount, List.of());
        }

        int debugDirectory = optionalHeader + dataDirectoriesOffset + IMAGE_DIRECTORY_ENTRY_DEBUG * 8;
        long debugRva = unsigned32(bytes, debugDirectory);
        long debugSize = unsigned32(bytes, debugDirectory + 4);
        if (debugRva == 0 || debugSize == 0) {
            return new PePrivacyInfo(symbolTablePointer, symbolCount, List.of());
        }
        if (debugSize % IMAGE_DEBUG_DIRECTORY_SIZE != 0) {
            throw new IOException("PE debug directory size is not entry-aligned: " + debugSize);
        }

        int sectionTable = optionalHeader + optionalHeaderSize;
        int debugOffset = rvaToFileOffset(bytes, sectionTable, sectionCount, debugRva, debugSize);
        ArrayList<Integer> debugTypes = new ArrayList<>();
        int entryCount = Math.toIntExact(debugSize / IMAGE_DEBUG_DIRECTORY_SIZE);
        for (int index = 0; index < entryCount; index++) {
            int entry = debugOffset + index * IMAGE_DEBUG_DIRECTORY_SIZE;
            debugTypes.add(Math.toIntExact(unsigned32(bytes, entry + 12)));
        }
        return new PePrivacyInfo(symbolTablePointer, symbolCount, debugTypes);
    }

    public static boolean contains(byte[] bytes, byte[] needle) {
        if (needle.length == 0) {
            return true;
        }
        int lastStart = bytes.length - needle.length;
        for (int start = 0; start <= lastStart; start++) {
            int index = 0;
            while (index < needle.length && bytes[start + index] == needle[index]) {
                index++;
            }
            if (index == needle.length) {
                return true;
            }
        }
        return false;
    }

    private int rvaToFileOffset(
            byte[] bytes,
            int sectionTable,
            int sectionCount,
            long rva,
            long size) throws IOException {
        for (int index = 0; index < sectionCount; index++) {
            int section = sectionTable + index * 40;
            requireRange(bytes, section, 40, "PE section header");
            long virtualSize = unsigned32(bytes, section + 8);
            long virtualAddress = unsigned32(bytes, section + 12);
            long rawSize = unsigned32(bytes, section + 16);
            long rawPointer = unsigned32(bytes, section + 20);
            long mappedSize = Math.max(virtualSize, rawSize);
            if (rva < virtualAddress
                    || rva - virtualAddress > mappedSize
                    || size > mappedSize - (rva - virtualAddress)) {
                continue;
            }
            long offset = rawPointer + (rva - virtualAddress);
            int checked = checkedOffset(offset, "PE debug directory");
            requireRange(bytes, checked, size, "PE debug directory");
            return checked;
        }
        throw new IOException("PE debug directory RVA does not map to a section: 0x" + Long.toHexString(rva));
    }

    private int unsigned16(byte[] bytes, int offset) throws IOException {
        requireRange(bytes, offset, 2, "16-bit PE field");
        return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8;
    }

    private long unsigned32(byte[] bytes, int offset) throws IOException {
        requireRange(bytes, offset, 4, "32-bit PE field");
        return (bytes[offset] & 0xffL)
                | (bytes[offset + 1] & 0xffL) << 8
                | (bytes[offset + 2] & 0xffL) << 16
                | (bytes[offset + 3] & 0xffL) << 24;
    }

    private int checkedOffset(long offset, String field) throws IOException {
        if (offset < 0 || offset > Integer.MAX_VALUE) {
            throw new IOException(field + " is outside the supported file range: " + offset);
        }
        return (int) offset;
    }

    private void requireRange(byte[] bytes, int offset, long length, String field) throws IOException {
        if (offset < 0 || length < 0 || length > bytes.length || offset > bytes.length - length) {
            throw new IOException(field + " is truncated");
        }
    }

    public record PePrivacyInfo(long symbolTablePointer, long symbolCount, List<Integer> debugTypes) {
        public PePrivacyInfo {
            debugTypes = List.copyOf(debugTypes);
        }

        public boolean hasCoffSymbolTable() {
            return symbolTablePointer != 0 || symbolCount != 0;
        }

        public boolean hasCodeViewDebugEntry() {
            return debugTypes.contains(IMAGE_DEBUG_TYPE_CODEVIEW);
        }

        public boolean hasReproDebugEntry() {
            return debugTypes.contains(IMAGE_DEBUG_TYPE_REPRO);
        }
    }
}
