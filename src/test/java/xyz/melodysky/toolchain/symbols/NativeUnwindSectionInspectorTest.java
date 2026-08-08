package xyz.melodysky.toolchain.symbols;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.TargetTriple;

class NativeUnwindSectionInspectorTest {
    private static final int ELF_SECTION_TABLE = 0x80;
    private static final int ELF_SECTION_SIZE = 64;
    private static final int PE_HEADER = 0x80;
    private static final int PE_OPTIONAL = PE_HEADER + 24;
    private static final int PE_SECTION_TABLE = PE_OPTIONAL + 0xf0;
    private static final int MACH_COMMAND = 32;

    @Test
    void readsElf64FrameSectionsAndSizes() throws Exception {
        NativeUnwindSectionInspection inspection = new NativeUnwindSectionInspector().inspect(
                TargetTriple.LINUX_X64,
                elf64WithUnwindSections());

        assertEquals(
                Map.of(".eh_frame", 24L, ".eh_frame_hdr", 12L),
                inspection.sectionSizes());
        assertTrue(inspection.hasNonEmptyUnwindSection());
        assertEquals(36L, inspection.totalSize());
    }

    @Test
    void readsPe32PlusExceptionSectionsAndSizes() throws Exception {
        NativeUnwindSectionInspection inspection = new NativeUnwindSectionInspector().inspect(
                TargetTriple.WINDOWS_X64,
                pe32PlusWithUnwindSections());

        assertEquals(Map.of(".pdata", 24L, ".xdata", 12L), inspection.sectionSizes());
        assertTrue(inspection.hasNonEmptyUnwindSection());
        assertEquals(36L, inspection.totalSize());
    }

    @Test
    void readsMachO64FrameSectionsAndSizes() throws Exception {
        NativeUnwindSectionInspection inspection = new NativeUnwindSectionInspector().inspect(
                TargetTriple.MACOS_X64,
                machO64WithUnwindSections());

        assertEquals(
                Map.of("__eh_frame", 24L, "__unwind_info", 32L),
                inspection.sectionSizes());
        assertTrue(inspection.hasNonEmptyUnwindSection());
        assertEquals(56L, inspection.totalSize());
    }

    @Test
    void acceptsAValidImageWithoutTargetUnwindSections() throws Exception {
        byte[] binary = elf64WithUnwindSections();
        int strings = 0x300;
        putAscii(binary, strings + 11, ".no_frame");
        putAscii(binary, strings + 21, ".no_frame_hdr");

        NativeUnwindSectionInspection inspection = new NativeUnwindSectionInspector().inspect(
                TargetTriple.LINUX_X64,
                binary);

        assertEquals(Map.of(), inspection.sectionSizes());
        assertFalse(inspection.hasNonEmptyUnwindSection());
        assertEquals(0L, inspection.totalSize());
    }

    @Test
    void rejectsTargetArchitectureMismatch() {
        IOException elfFailure = assertThrows(
                IOException.class,
                () -> new NativeUnwindSectionInspector().inspect(
                        TargetTriple.LINUX_ARM64,
                        elf64WithUnwindSections()));
        IOException peFailure = assertThrows(
                IOException.class,
                () -> new NativeUnwindSectionInspector().inspect(
                        TargetTriple.WINDOWS_ARM64,
                        pe32PlusWithUnwindSections()));
        IOException machFailure = assertThrows(
                IOException.class,
                () -> new NativeUnwindSectionInspector().inspect(
                        TargetTriple.MACOS_ARM64,
                        machO64WithUnwindSections()));

        assertTrue(elfFailure.getMessage().contains("machine mismatch"));
        assertTrue(peFailure.getMessage().contains("machine mismatch"));
        assertTrue(machFailure.getMessage().contains("CPU type mismatch"));
    }

    @Test
    void rejectsElfNameOrPayloadOutsideTheFile() {
        byte[] invalidName = elf64WithUnwindSections();
        put32(invalidName, ELF_SECTION_TABLE + 2 * ELF_SECTION_SIZE, 0xffff);
        byte[] invalidPayload = elf64WithUnwindSections();
        put64(invalidPayload, ELF_SECTION_TABLE + 2 * ELF_SECTION_SIZE + 24, 0x3f8);

        IOException nameFailure = assertThrows(
                IOException.class,
                () -> new NativeUnwindSectionInspector().inspect(
                        TargetTriple.LINUX_X64,
                        invalidName));
        IOException payloadFailure = assertThrows(
                IOException.class,
                () -> new NativeUnwindSectionInspector().inspect(
                        TargetTriple.LINUX_X64,
                        invalidPayload));

        assertTrue(nameFailure.getMessage().contains("name index"));
        assertTrue(payloadFailure.getMessage().contains("exceeds file bounds"));
    }

    @Test
    void rejectsPePayloadOutsideTheFile() {
        byte[] binary = pe32PlusWithUnwindSections();
        int pdata = PE_SECTION_TABLE + 40;
        put32(binary, pdata + 20, 0x3f8);

        IOException failure = assertThrows(
                IOException.class,
                () -> new NativeUnwindSectionInspector().inspect(
                        TargetTriple.WINDOWS_X64,
                        binary));

        assertTrue(failure.getMessage().contains("exceeds file bounds"));
    }

    @Test
    void rejectsMachOLoadCommandOutsideDeclaredArea() {
        byte[] binary = machO64WithUnwindSections();
        put32(binary, MACH_COMMAND + 4, 400);

        IOException failure = assertThrows(
                IOException.class,
                () -> new NativeUnwindSectionInspector().inspect(
                        TargetTriple.MACOS_X64,
                        binary));

        assertTrue(failure.getMessage().contains("declared command area"));
    }

    @Test
    void rejectsTruncatedOrWrongFormatInputsAsIoFailures() {
        NativeUnwindSectionInspector inspector = new NativeUnwindSectionInspector();
        for (TargetTriple target : TargetTriple.values()) {
            assertThrows(IOException.class, () -> inspector.inspect(target, new byte[4]));
        }
        assertThrows(
                IOException.class,
                () -> inspector.inspect(TargetTriple.WINDOWS_X64, elf64WithUnwindSections()));
        assertThrows(IOException.class, () -> inspector.inspect(null, new byte[64]));
        assertThrows(IOException.class, () -> inspector.inspect(TargetTriple.LINUX_X64, (byte[]) null));
    }

    @Test
    void resultOwnsAnImmutableSortedSectionMap() {
        java.util.LinkedHashMap<String, Long> source = new java.util.LinkedHashMap<>();
        source.put(".xdata", 8L);
        source.put(".pdata", 16L);

        NativeUnwindSectionInspection inspection =
                new NativeUnwindSectionInspection(TargetTriple.WINDOWS_X64, source);
        source.clear();

        assertEquals(java.util.List.of(".pdata", ".xdata"), inspection.sectionSizes().keySet().stream().toList());
        assertThrows(
                UnsupportedOperationException.class,
                () -> inspection.sectionSizes().put(".other", 1L));
    }

    private byte[] elf64WithUnwindSections() {
        byte[] bytes = new byte[1024];
        bytes[0] = 0x7f;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;
        bytes[5] = 1;
        bytes[6] = 1;
        put16(bytes, 16, 3);
        put16(bytes, 18, 62);
        put32(bytes, 20, 1);
        put64(bytes, 0x28, ELF_SECTION_TABLE);
        put16(bytes, 0x34, 64);
        put16(bytes, 0x3a, ELF_SECTION_SIZE);
        put16(bytes, 0x3c, 4);
        put16(bytes, 0x3e, 1);

        int strings = 0x300;
        bytes[strings] = 0;
        putAscii(bytes, strings + 1, ".shstrtab");
        putAscii(bytes, strings + 11, ".eh_frame");
        putAscii(bytes, strings + 21, ".eh_frame_hdr");
        int stringsSize = 35;

        int stringsSection = ELF_SECTION_TABLE + ELF_SECTION_SIZE;
        put32(bytes, stringsSection, 1);
        put32(bytes, stringsSection + 4, 3);
        put64(bytes, stringsSection + 24, strings);
        put64(bytes, stringsSection + 32, stringsSize);

        int frame = ELF_SECTION_TABLE + 2 * ELF_SECTION_SIZE;
        put32(bytes, frame, 11);
        put32(bytes, frame + 4, 1);
        put64(bytes, frame + 24, 0x340);
        put64(bytes, frame + 32, 24);

        int frameHeader = ELF_SECTION_TABLE + 3 * ELF_SECTION_SIZE;
        put32(bytes, frameHeader, 21);
        put32(bytes, frameHeader + 4, 1);
        put64(bytes, frameHeader + 24, 0x380);
        put64(bytes, frameHeader + 32, 12);
        return bytes;
    }

    private byte[] pe32PlusWithUnwindSections() {
        byte[] bytes = new byte[1024];
        put16(bytes, 0, 0x5a4d);
        put32(bytes, 0x3c, PE_HEADER);
        put32(bytes, PE_HEADER, 0x00004550);
        put16(bytes, PE_HEADER + 4, 0x8664);
        put16(bytes, PE_HEADER + 6, 3);
        put16(bytes, PE_HEADER + 20, 0xf0);
        put16(bytes, PE_HEADER + 22, 0x2000);
        put16(bytes, PE_OPTIONAL, 0x20b);

        putPeSection(bytes, PE_SECTION_TABLE, ".text", 20, 0x200, 32);
        putPeSection(bytes, PE_SECTION_TABLE + 40, ".pdata", 24, 0x240, 32);
        putPeSection(bytes, PE_SECTION_TABLE + 80, ".xdata", 12, 0x280, 32);
        return bytes;
    }

    private byte[] machO64WithUnwindSections() {
        byte[] bytes = new byte[1024];
        int commandSize = 72 + 3 * 80;
        put32(bytes, 0, 0xfeedfacfL);
        put32(bytes, 4, 0x01000007L);
        put32(bytes, 12, 6);
        put32(bytes, 16, 1);
        put32(bytes, 20, commandSize);

        put32(bytes, MACH_COMMAND, 0x19);
        put32(bytes, MACH_COMMAND + 4, commandSize);
        putFixedAscii(bytes, MACH_COMMAND + 8, 16, "__TEXT");
        put32(bytes, MACH_COMMAND + 64, 3);
        putMachSection(bytes, MACH_COMMAND + 72, ".text", 16, 0x180);
        putMachSection(bytes, MACH_COMMAND + 152, "__eh_frame", 24, 0x200);
        putMachSection(bytes, MACH_COMMAND + 232, "__unwind_info", 32, 0x240);
        return bytes;
    }

    private void putPeSection(
            byte[] bytes,
            int offset,
            String name,
            int virtualSize,
            int rawOffset,
            int rawSize) {
        putFixedAscii(bytes, offset, 8, name);
        put32(bytes, offset + 8, virtualSize);
        put32(bytes, offset + 16, rawSize);
        put32(bytes, offset + 20, rawOffset);
    }

    private void putMachSection(
            byte[] bytes,
            int offset,
            String name,
            int size,
            int fileOffset) {
        putFixedAscii(bytes, offset, 16, name);
        putFixedAscii(bytes, offset + 16, 16, "__TEXT");
        put64(bytes, offset + 40, size);
        put32(bytes, offset + 48, fileOffset);
    }

    private void putFixedAscii(
            byte[] bytes,
            int offset,
            int size,
            String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        if (encoded.length > size) {
            throw new IllegalArgumentException("fixture string is too long: " + value);
        }
        System.arraycopy(encoded, 0, bytes, offset, encoded.length);
    }

    private void putAscii(byte[] bytes, int offset, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, bytes, offset, encoded.length);
        bytes[offset + encoded.length] = 0;
    }

    private void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private void put32(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private void put64(byte[] bytes, int offset, long value) {
        put32(bytes, offset, value);
        put32(bytes, offset + 4, value >>> 32);
    }
}
