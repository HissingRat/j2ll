package xyz.melodysky.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RuntimeTokenMapperTest {
    @Test
    void javaHashCollisionFixtureDoesNotCollide() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        RuntimeTokenMapper mapper = RuntimeTokenMapper.fromBytes(
                "build-one".getBytes(StandardCharsets.UTF_8));

        assertNotEquals(
                mapper.token(RuntimeTokenDomain.CLASS_OBJECT, "Aa"),
                mapper.token(RuntimeTokenDomain.CLASS_OBJECT, "BB"));
    }

    @Test
    void tokenIsBuildScopedAndDomainSeparated() {
        RuntimeTokenMapper first = RuntimeTokenMapper.fromBytes(
                "build-one".getBytes(StandardCharsets.UTF_8));
        RuntimeTokenMapper repeated = RuntimeTokenMapper.fromBytes(
                "build-one".getBytes(StandardCharsets.UTF_8));
        RuntimeTokenMapper second = RuntimeTokenMapper.fromBytes(
                "build-two".getBytes(StandardCharsets.UTF_8));

        long firstClass = first.token(
                RuntimeTokenDomain.CLASS_RUNTIME,
                "Lpkg/Owner;");
        assertEquals(
                firstClass,
                repeated.token(
                        RuntimeTokenDomain.CLASS_RUNTIME,
                        "Lpkg/Owner;"));
        assertNotEquals(
                firstClass,
                second.token(
                        RuntimeTokenDomain.CLASS_RUNTIME,
                        "Lpkg/Owner;"));
        assertNotEquals(
                firstClass,
                first.token(
                        RuntimeTokenDomain.CLASS_OBJECT,
                        "Lpkg/Owner;"));
    }

    @Test
    void collisionFailsClosedWithoutLeakingIdentities() {
        RuntimeTokenMapper mapper = new RuntimeTokenMapper(
                new byte[] { 1 },
                (key, domain, identity) -> 7L);
        mapper.token(RuntimeTokenDomain.FIELD_RUNTIME, "pkg/Owner#a!I");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> mapper.token(
                        RuntimeTokenDomain.FIELD_RUNTIME,
                        "pkg/Owner#b!I"));

        assertTrue(failure.getMessage().contains("RUNTIME_TOKEN_COLLISION"));
        assertTrue(!failure.getMessage().contains("pkg/Owner"));
    }

    @Test
    void helperSymbolIsHashOnlyAndDoesNotLeakOperationOrIdentity() {
        RuntimeTokenMapper mapper = RuntimeTokenMapper.fromBytes(
                "build-one".getBytes(StandardCharsets.UTF_8));

        String symbol = mapper.helperSymbol(
                RuntimeTokenDomain.FIELD_RUNTIME,
                "field_get_static_i32",
                "pkg/Owner#secret!I");

        assertTrue(symbol.matches("j2ll_h_[0-9a-f]{16}"));
        assertFalse(symbol.contains("field"));
        assertFalse(symbol.contains("static"));
        assertFalse(symbol.contains("secret"));
        assertFalse(symbol.contains("Owner"));
    }

    @Test
    void physicalOrderChangesAcrossBuildsWithoutPlaintextSort() {
        List<String> values =
                List.of("pkg/A", "pkg/B", "pkg/C", "pkg/D", "pkg/E");
        RuntimeTokenMapper first = RuntimeTokenMapper.fromBytes(
                "build-one".getBytes(StandardCharsets.UTF_8));
        RuntimeTokenMapper second = RuntimeTokenMapper.fromBytes(
                "build-two".getBytes(StandardCharsets.UTF_8));

        assertNotEquals(
                first.physicalOrder(
                        RuntimeTokenDomain.CLASS_RUNTIME,
                        values,
                        value -> value),
                second.physicalOrder(
                        RuntimeTokenDomain.CLASS_RUNTIME,
                        values,
                        value -> value));
    }
}
