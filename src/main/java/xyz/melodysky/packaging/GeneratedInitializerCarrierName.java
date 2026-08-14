package xyz.melodysky.packaging;

import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.pass.protection.ProtectionRandom;

/** Derives build-scoped hash-only Java names for initializer native carriers. */
final class GeneratedInitializerCarrierName {
    private static final int HASH_HEX_CHARS = 32;
    private static final char[] JAVA_IDENTIFIER_NIBBLES =
            "abcdefghijklmnop".toCharArray();

    private final ProtectionRandom random;

    GeneratedInitializerCarrierName(long buildScopedSeed) {
        random = new ProtectionRandom(buildScopedSeed);
    }

    String constructor(ParsedMethod method) {
        return derive("CONSTRUCTOR_NATIVE_CARRIER", method);
    }

    String classInitializer(ParsedMethod method) {
        return derive("CLASS_INITIALIZER_NATIVE_CARRIER", method);
    }

    private String derive(String purpose, ParsedMethod method) {
        String hexadecimal = random.token(
                purpose,
                method.owner() + "#" + method.name() + "!" + method.descriptor(),
                HASH_HEX_CHARS);
        char[] identifier = new char[hexadecimal.length()];
        for (int index = 0; index < hexadecimal.length(); index++) {
            int nibble = Character.digit(hexadecimal.charAt(index), 16);
            if (nibble < 0) {
                throw new IllegalStateException(
                        "protection token is not lowercase hexadecimal");
            }
            identifier[index] = JAVA_IDENTIFIER_NIBBLES[nibble];
        }
        return new String(identifier);
    }
}
