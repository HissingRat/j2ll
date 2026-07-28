package xyz.melodysky.protection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.config.BinaryProtectionConfig;
import xyz.melodysky.config.IrProtectionConfig;
import xyz.melodysky.config.LlvmProtectionConfig;
import xyz.melodysky.config.ProtectionConfig;
import xyz.melodysky.config.ProtectionSeedMode;

class BuildProtectionIdentityTest {
    @Test
    void registryHasUniqueExplicitDomains() {
        Set<String> wireNames = new HashSet<>();

        for (BuildProtectionDomain domain
                : BuildProtectionDomain.values()) {
            assertFalse(domain.wireName().isBlank());
            assertFalse(domain.wireName().contains(" "));
            assertEquals(
                    domain.wireName(),
                    domain.wireName().toUpperCase(java.util.Locale.ROOT));
            assertTrue(
                    wireNames.add(domain.wireName()),
                    "duplicate KDF domain " + domain.wireName());
        }

        assertEquals(BuildProtectionDomain.values().length, wireNames.size());
        assertEquals(
                Set.of(
                        BuildProtectionDomain.IR_METHOD,
                        BuildProtectionDomain.IR_PROGRAM,
                        BuildProtectionDomain.FIELD,
                        BuildProtectionDomain.BUSINESS_STRING,
                        BuildProtectionDomain.METHOD_TABLE,
                        BuildProtectionDomain.WRAPPER,
                        BuildProtectionDomain.LLVM_SYMBOL,
                        BuildProtectionDomain.LLVM_PROTECTION,
                        BuildProtectionDomain.NATIVE_TEXT,
                        BuildProtectionDomain.BUSINESS_NATIVE_TEXT,
                        BuildProtectionDomain.REGISTRATION,
                        BuildProtectionDomain.REPORT_IDENTITY),
                Set.of(BuildProtectionDomain.values()));

        Arrays.stream(BuildProtectionIdentity.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("derive"))
                .map(Method::getParameterTypes)
                .forEach(parameterTypes -> assertEquals(
                        BuildProtectionDomain.class,
                        parameterTypes[0],
                        "production derivation API must require a registered domain"));
    }

    @Test
    void fixedRootAndDomainAreReproducible() {
        BuildProtectionIdentity first = identity(
                "fixed",
                ProtectionSeedMode.REPRODUCIBLE);
        BuildProtectionIdentity second = identity(
                "fixed",
                ProtectionSeedMode.REPRODUCIBLE);

        assertArrayEquals(
                first.deriveBytes(
                        BuildProtectionDomain.REGISTRATION,
                        "pkg/Owner#run!()V",
                        80),
                second.deriveBytes(
                        BuildProtectionDomain.REGISTRATION,
                        "pkg/Owner#run!()V",
                        80));
        assertEquals(first.identityHash(), second.identityHash());
        assertEquals(
                BuildProtectionMaterials.derive(first),
                BuildProtectionMaterials.derive(second));
    }

    @Test
    void everyDomainAndContextAreSeparated() {
        BuildProtectionIdentity identity = identity(
                "fixed",
                ProtectionSeedMode.REPRODUCIBLE);
        Set<String> outputs = new HashSet<>();

        for (BuildProtectionDomain domain
                : BuildProtectionDomain.values()) {
            String output = identity.deriveHex(domain, "same", 64);
            assertTrue(
                    outputs.add(output),
                    "KDF output collided for " + domain);
            assertNotEquals(
                    output,
                    identity.deriveHex(domain, "other", 64),
                    "context was not separated for " + domain);
        }
    }

    @Test
    void differentRootChangesEveryDomainAndMainlineMaterial() {
        BuildProtectionIdentity first = identity(
                "first-root",
                ProtectionSeedMode.REPRODUCIBLE);
        BuildProtectionIdentity second = identity(
                "second-root",
                ProtectionSeedMode.REPRODUCIBLE);

        for (BuildProtectionDomain domain
                : BuildProtectionDomain.values()) {
            assertFalse(Arrays.equals(
                    first.deriveBytes(domain, "same", 32),
                    second.deriveBytes(domain, "same", 32)));
        }
        assertNotEquals(
                BuildProtectionMaterials.derive(first),
                BuildProtectionMaterials.derive(second));
    }

    @Test
    void materialAndIdentityViewsDoNotExposeRawSeed() {
        String rawSeed = "raw-release-root-must-never-be-reported";
        BuildProtectionIdentity identity = identity(
                rawSeed,
                ProtectionSeedMode.REPRODUCIBLE);
        BuildProtectionMaterials materials =
                BuildProtectionMaterials.derive(identity);

        assertFalse(identity.toString().contains(rawSeed));
        assertFalse(identity.identityHash().contains(rawSeed));
        assertFalse(materials.toString().contains(rawSeed));
        assertFalse(
                java.util.HexFormat.of()
                        .formatHex(materials.nativeTextKey())
                        .contains(rawSeed));
        assertFalse(Arrays.equals(
                materials.nativeTextKey(),
                materials.businessNativeTextKey()));
        assertFalse(Arrays.equals(
                materials.businessNativeTextKey(),
                materials.registrationKey()));
        assertFalse(Arrays.equals(
                materials.nativeTextKey(),
                materials.registrationKey()));

        byte[] firstView = materials.nativeTextKey();
        firstView[0] ^= 0x7f;
        assertFalse(Arrays.equals(firstView, materials.nativeTextKey()));
        byte[] firstBusinessView = materials.businessNativeTextKey();
        firstBusinessView[0] ^= 0x7f;
        assertFalse(Arrays.equals(
                firstBusinessView,
                materials.businessNativeTextKey()));
        byte[] firstRegistrationView = materials.registrationKey();
        firstRegistrationView[0] ^= 0x7f;
        assertFalse(Arrays.equals(
                firstRegistrationView,
                materials.registrationKey()));
    }

    @Test
    void rejectsInvalidLengthAndNullDomain() {
        BuildProtectionIdentity identity = identity(
                "fixed",
                ProtectionSeedMode.REPRODUCIBLE);

        assertThrows(
                NullPointerException.class,
                () -> identity.deriveBytes(null, "context", 16));
        assertThrows(
                IllegalArgumentException.class,
                () -> identity.deriveHex(
                        BuildProtectionDomain.IR_METHOD,
                        "context",
                        15));
        assertThrows(
                IllegalArgumentException.class,
                () -> identity.deriveBytes(
                        BuildProtectionDomain.IR_METHOD,
                        "context",
                        0));
    }

    private BuildProtectionIdentity identity(
            String seed,
            ProtectionSeedMode mode) {
        return BuildProtectionIdentity.from(new ProtectionConfig(
                true,
                seed,
                mode,
                disabledIr(),
                disabledLlvm(),
                disabledBinary()));
    }

    private IrProtectionConfig disabledIr() {
        return new IrProtectionConfig(
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false);
    }

    private LlvmProtectionConfig disabledLlvm() {
        return new LlvmProtectionConfig(
                false,
                false,
                false,
                false,
                false,
                false);
    }

    private BinaryProtectionConfig disabledBinary() {
        return new BinaryProtectionConfig(
                false,
                false,
                false,
                false,
                false);
    }
}
