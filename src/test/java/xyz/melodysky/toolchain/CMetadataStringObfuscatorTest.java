package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CMetadataStringObfuscatorTest {
    @Test
    void replacesStaticMetadataStringsAndDecodesBeforeRegistration() {
        String source = """
                #include <jni.h>
                #include <stdint.h>
                #include <stddef.h>

                static const char* owner = "xyz/example/SecretOwner";
                static const char* descriptor = "(Lxyz/example/SecretOwner;)V";
                JNIEXPORT jint JNICALL j2ll_register(JavaVM* vm) {
                    (void)vm;
                    return 0;
                }
                """;

        String obfuscated = new CMetadataStringObfuscator().obfuscate(source);

        assertFalse(obfuscated.contains("\"xyz/example/SecretOwner\""));
        assertFalse(obfuscated.contains("\"(Lxyz/example/SecretOwner;)V\""));
        assertTrue(obfuscated.contains("static unsigned char j2ll_ms_"));
        assertTrue(obfuscated.contains("j2ll_decode_metadata_strings();"));
    }

    @Test
    void outputIsDeterministicAndLeavesTheHexLookupArrayValid() {
        String source = """
                #include <jni.h>
                #include <stdint.h>

                static const char hex[] = "0123456789abcdef";
                static const char* message = "sensitive metadata";
                JNIEXPORT jint JNICALL j2ll_register(JavaVM* vm) {
                    (void)vm;
                    return 0;
                }
                """;
        CMetadataStringObfuscator obfuscator = new CMetadataStringObfuscator();

        String first = obfuscator.obfuscate(source);
        String second = obfuscator.obfuscate(source);

        assertEquals(first, second);
        assertTrue(first.contains("static const char hex[] = \"0123456789abcdef\";"));
        assertFalse(first.contains("\"sensitive metadata\""));
    }
}
