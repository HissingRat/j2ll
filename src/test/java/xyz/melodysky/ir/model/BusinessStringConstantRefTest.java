package xyz.melodysky.ir.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BusinessStringConstantRefTest {
    @Test
    void helperSymbolIsBuildScopedHashOnlyAndStableWithinOneBuild() {
        BusinessStringConstantRef constant =
                BusinessStringConstantRef.of("distinctive-sensitive-value");
        BusinessStringSymbolMapper first =
                BusinessStringSymbolMapper.fromBytes(new byte[] {1, 2, 3});
        BusinessStringSymbolMapper same =
                BusinessStringSymbolMapper.fromBytes(new byte[] {1, 2, 3});
        BusinessStringSymbolMapper second =
                BusinessStringSymbolMapper.fromBytes(new byte[] {4, 5, 6});

        assertEquals(
                constant.helperSymbol(first),
                constant.helperSymbol(same));
        assertNotEquals(
                constant.helperSymbol(first),
                constant.helperSymbol(second));
        assertFalse(constant.helperSymbol(first)
                .contains("distinctive-sensitive-value"));
    }

    @Test
    void modifiedUtf8PreservesNulAndUtf16SurrogateCodeUnitsForNewStringUtf() {
        BusinessStringConstantRef constant =
                BusinessStringConstantRef.of("a\u0000\uD83D\uDE00");

        assertArrayEquals(
                HexFormat.of().parseHex("61c080eda0bdedb880"),
                constant.modifiedUtf8Bytes());
    }

    @Test
    void v2EncryptedCarrierTokenIsKeyBoundAndValidated() {
        String value = "A";
        byte[] key = {0x01};
        long token = BusinessStringConstantRef.encryptedCarrierToken(
                value,
                key);
        IrValue result = new IrValue("%value", IrType.REFERENCE);
        String prefix = "j2ll_rt_string_constant|enc:v2:";

        IrInstruction valid = IrInstruction.operation(
                Optional.of(result),
                IrOpcode.CALL_RUNTIME_HELPER,
                List.of(),
                prefix + token + ":01:40");
        BusinessStringConstantRef parsed =
                BusinessStringConstantRef.fromInstruction(valid)
                        .orElseThrow();

        assertEquals(value, parsed.value());
        assertNotEquals(
                token,
                BusinessStringConstantRef.encryptedCarrierToken(
                        value,
                        new byte[] {0x02}));
        IrInstruction tampered = IrInstruction.operation(
                Optional.of(result),
                IrOpcode.CALL_RUNTIME_HELPER,
                List.of(),
                prefix + (token + 1L) + ":01:40");
        assertThrows(
                IllegalArgumentException.class,
                () -> BusinessStringConstantRef.fromInstruction(tampered));
    }

    @Test
    void v1EncryptedCarrierRemainsReadableForInternalPipelineCompatibility() {
        String value = "A";
        IrValue result = new IrValue("%value", IrType.REFERENCE);
        IrInstruction legacy = IrInstruction.operation(
                Optional.of(result),
                IrOpcode.CALL_RUNTIME_HELPER,
                List.of(),
                "j2ll_rt_string_constant|enc:v1:"
                        + BusinessStringConstantRef.integrityToken(value)
                        + ":01:40");

        assertEquals(
                value,
                BusinessStringConstantRef.fromInstruction(legacy)
                        .orElseThrow()
                        .value());
    }
}
