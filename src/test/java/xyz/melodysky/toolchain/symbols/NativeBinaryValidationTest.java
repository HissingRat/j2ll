package xyz.melodysky.toolchain.symbols;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.TargetTriple;

class NativeBinaryValidationTest {
    @Test
    void rejectsExecutablePeThatIsNotMarkedAsDll() {
        byte[] bytes = peHeader(0);

        IOException failure = assertThrows(
                IOException.class,
                () -> new PeExportTable().read(new BinaryData(bytes), TargetTriple.WINDOWS_X64));

        assertTrue(failure.getMessage().contains("not a DLL"));
    }

    @Test
    void rejectsPeDllWithOrdinalOnlyExport() {
        byte[] bytes = peHeader(0x2000);
        put16(bytes, 0x98, 0x20b);
        put32(bytes, 0x98 + 112, 0x1000);
        int section = 0x98 + 0xf0;
        put32(bytes, section + 8, 0x200);
        put32(bytes, section + 12, 0x1000);
        put32(bytes, section + 16, 0x200);
        put32(bytes, section + 20, 0x200);
        put32(bytes, 0x200 + 20, 1);
        put32(bytes, 0x200 + 28, 0x1040);
        put32(bytes, 0x240, 0x1100);

        IOException failure = assertThrows(
                IOException.class,
                () -> new PeExportTable().read(new BinaryData(bytes), TargetTriple.WINDOWS_X64));

        assertTrue(failure.getMessage().contains("ordinal-only export"));
    }

    @Test
    void rejectsElfExecutableThatIsNotDynamicLibraryShape() {
        byte[] bytes = new byte[64];
        bytes[0] = 0x7f;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;
        bytes[5] = 1;
        put16(bytes, 16, 2);
        put16(bytes, 18, 62);

        IOException failure = assertThrows(
                IOException.class,
                () -> new ElfExportTable().read(new BinaryData(bytes), TargetTriple.LINUX_X64));

        assertTrue(failure.getMessage().contains("not ET_DYN"));
    }

    @Test
    void rejectsMachOExecutableThatIsNotDylib() {
        byte[] bytes = new byte[32];
        put32(bytes, 0, 0xfeedfacfL);
        put32(bytes, 4, 0x01000007L);
        put32(bytes, 12, 2);

        IOException failure = assertThrows(
                IOException.class,
                () -> new MachOExportTable().read(new BinaryData(bytes), TargetTriple.MACOS_X64));

        assertTrue(failure.getMessage().contains("not MH_DYLIB"));
    }

    @Test
    void pePrivacyInspectionAllowsReproDebugEntryWithoutCoffSymbols() throws Exception {
        byte[] bytes = peWithDebugTypes(16);

        NativeBinaryPrivacyInspector.PePrivacyInfo info =
                new NativeBinaryPrivacyInspector().inspectPe(bytes);

        assertFalse(info.hasCoffSymbolTable());
        assertFalse(info.hasCodeViewDebugEntry());
        assertTrue(info.hasReproDebugEntry());
        assertEquals(java.util.List.of(16), info.debugTypes());
    }

    @Test
    void pePrivacyInspectionDetectsCoffSymbolsAndCodeView() throws Exception {
        byte[] bytes = peWithDebugTypes(16, 2);
        put32(bytes, 0x8c, 0x380);
        put32(bytes, 0x90, 3);

        NativeBinaryPrivacyInspector.PePrivacyInfo info =
                new NativeBinaryPrivacyInspector().inspectPe(bytes);

        assertTrue(info.hasCoffSymbolTable());
        assertTrue(info.hasCodeViewDebugEntry());
        assertTrue(info.hasReproDebugEntry());
    }

    private byte[] peWithDebugTypes(int... debugTypes) {
        byte[] bytes = peHeader(0x2000);
        put16(bytes, 0x98, 0x20b);
        put32(bytes, 0x98 + 108, 16);
        int debugDataDirectory = 0x98 + 112 + 6 * 8;
        put32(bytes, debugDataDirectory, 0x1100);
        put32(bytes, debugDataDirectory + 4, (long) debugTypes.length * 28);
        int section = 0x98 + 0xf0;
        put32(bytes, section + 8, 0x200);
        put32(bytes, section + 12, 0x1000);
        put32(bytes, section + 16, 0x200);
        put32(bytes, section + 20, 0x200);
        int debugOffset = 0x300;
        for (int index = 0; index < debugTypes.length; index++) {
            put32(bytes, debugOffset + index * 28 + 12, debugTypes[index]);
        }
        return bytes;
    }

    private byte[] peHeader(int characteristics) {
        byte[] bytes = new byte[1024];
        put16(bytes, 0, 0x5a4d);
        put32(bytes, 0x3c, 0x80);
        put32(bytes, 0x80, 0x00004550);
        put16(bytes, 0x84, 0x8664);
        put16(bytes, 0x86, 1);
        put16(bytes, 0x94, 0xf0);
        put16(bytes, 0x96, characteristics);
        return bytes;
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
}
