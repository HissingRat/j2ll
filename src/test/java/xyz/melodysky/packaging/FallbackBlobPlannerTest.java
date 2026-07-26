package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.NativeFieldStorageKind;
import xyz.melodysky.ir.model.NativeFieldSlotRef;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class FallbackBlobPlannerTest {
    private static String fallbackFieldSentinel = "java-field";

    @Test
    void plansNativeEmbeddedFallbackBlobManifestAndClassloaderReusePolicy() {
        NativeEmbeddedFallbackBlob blob = new FallbackBlobPlanner().plan(List.of(new FallbackBlobInput(
                        "substring__8f3a21c0d4e5f607",
                        "pkg/Foo#substring!(Ljava/lang/String;)Ljava/lang/String;",
                        "pkg/Foo")))
                .get(0);

        assertEquals("substring__8f3a21c0d4e5f607", blob.originalMethodId());
        assertEquals("pkg/Foo#substring!(Ljava/lang/String;)Ljava/lang/String;", blob.originalMethodKey());
        assertEquals("pkg/J2llFallback$substring__8f3a21c0d4e5f607", blob.helperClassName());
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;", blob.fallbackInvokeDescriptor());
        assertEquals("JVM_HELPER_FALLBACK", blob.fallbackReasonCode());
        assertEquals("j2ll-rle-byte-pairs-v1", blob.compressionAlgorithm());
        assertEquals("xor-sha256-key-stream-v1", blob.encryptionAlgorithm());
        assertEquals("fallbackBlobEncodingV1", blob.encodingVersion());
        assertEquals("nativeEmbeddedClassBlob", blob.storageTarget());
        if (Runtime.version().feature() >= 15) {
            assertEquals("HiddenClass", blob.definitionMechanism());
            assertEquals("FALLBACK_HIDDEN_CLASS", blob.definitionMechanismReasonCode());
            assertTrue(blob.ownerLookupSupported());
        } else {
            assertEquals("DefineClass", blob.definitionMechanism());
            assertEquals("FALLBACK_HIDDEN_CLASS_UNAVAILABLE", blob.definitionMechanismReasonCode());
            assertFalse(blob.ownerLookupSupported());
        }
        assertEquals("FALLBACK_CACHE_REUSE", blob.cacheReasonCode());
        assertEquals("lazyPerClassLoaderReuse", blob.classloaderReusePolicy());
        assertEquals("process", blob.cacheScope());
        assertEquals("fallbackId+definingClassLoaderIdentity", blob.cacheKey());
        assertEquals("processLifetime", blob.cacheLifetime());
        assertEquals("globalRefPerFallbackClassAndClassLoader", blob.globalReferencePolicy());
        assertEquals(64, blob.sha256().length());
        assertEquals(blob.encodedSha256(), blob.sha256());
        assertEquals(64, blob.originalSha256().length());
        assertEquals(64, blob.encodedSha256().length());
        assertTrue(blob.originalSize() > 0);
        assertTrue(blob.encodedSize() > 0);
        assertTrue(blob.sha256().matches("[0-9a-f]+"));
    }

    @Test
    void codecEncodesFallbackClassBytesAndRejectsHashMismatch() {
        FallbackHelperClass helperClass = new FallbackHelperClassFactory().create(
                "substring__1234",
                "pkg/Foo#substring!(Ljava/lang/String;)Ljava/lang/String;",
                "pkg/Foo");
        FallbackBlobCodec codec = new FallbackBlobCodec();
        EncodedFallbackBlob encoded = codec.encode(helperClass.bytes(), "seed\npkg/Foo#substring");

        assertFalse(Arrays.equals(helperClass.bytes(), encoded.encodedBytes()));
        assertTrue(Arrays.equals(helperClass.bytes(), codec.decode(encoded)));

        EncodedFallbackBlob tampered = new EncodedFallbackBlob(
                encoded.originalBytes(),
                encoded.encodedBytes(),
                encoded.keyBytes(),
                encoded.originalSha256(),
                "0".repeat(64),
                encoded.compressionAlgorithm(),
                encoded.encryptionAlgorithm(),
                encoded.encodingVersion());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(tampered));
    }

    @Test
    void codecRejectsWrongFallbackKeyAndCorruptedCompressedPayload() throws Exception {
        FallbackBlobCodec codec = new FallbackBlobCodec();
        EncodedFallbackBlob encoded = codec.encode(new byte[] {1, 1, 1, 2, 2, 3}, "fallback-id-a");
        EncodedFallbackBlob wrongKey = new EncodedFallbackBlob(
                encoded.originalBytes(),
                encoded.encodedBytes(),
                codec.encode(new byte[] {9}, "fallback-id-b").keyBytes(),
                encoded.originalSha256(),
                encoded.encodedSha256(),
                encoded.compressionAlgorithm(),
                encoded.encryptionAlgorithm(),
                encoded.encodingVersion());

        IllegalArgumentException wrongKeyError =
                assertThrows(IllegalArgumentException.class, () -> codec.decode(wrongKey));
        assertTrue(wrongKeyError.getMessage().startsWith("fallback blob compressed payload")
                || wrongKeyError.getMessage().equals("fallback blob decoded length exceeds compressed payload capacity")
                || wrongKeyError.getMessage().equals("fallback blob original SHA-256 mismatch"));

        EncodedFallbackBlob shortPayload = codec.encode(new byte[] {4, 5, 6}, "fallback-id-c");
        byte[] encodedBytes = shortPayload.encodedBytes();
        byte[] keyBytes = shortPayload.keyBytes();
        byte[] corruptedCompressed = new byte[] {0, 0, 0};
        for (int index = 0; index < corruptedCompressed.length; index++) {
            int stream = (keyBytes[index % keyBytes.length] & 0xff) ^ ((index * 31 + (index >>> 3)) & 0xff);
            encodedBytes[index] = (byte) ((corruptedCompressed[index] & 0xff) ^ stream);
        }
        byte[] corruptedEncoded = java.util.Arrays.copyOf(encodedBytes, corruptedCompressed.length);
        String corruptedSha256 = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(corruptedEncoded));
        EncodedFallbackBlob corrupted = new EncodedFallbackBlob(
                shortPayload.originalBytes(),
                corruptedEncoded,
                keyBytes,
                shortPayload.originalSha256(),
                corruptedSha256,
                shortPayload.compressionAlgorithm(),
                shortPayload.encryptionAlgorithm(),
                shortPayload.encodingVersion());

        IllegalArgumentException corruptedError =
                assertThrows(IllegalArgumentException.class, () -> codec.decode(corrupted));
        assertEquals("fallback blob compressed payload is too short", corruptedError.getMessage());
    }

    @Test
    void codecRejectsRleDecodedLengthBeyondCompressedCapacity() throws Exception {
        FallbackBlobCodec codec = new FallbackBlobCodec();
        EncodedFallbackBlob seed = codec.encode(new byte[] {1}, "fallback-id-capacity");
        byte[] compressed = new byte[] {
                0, 0, 1, 0,
                1, 7
        };
        byte[] encodedBytes = xorLikeRuntime(compressed, seed.keyBytes());
        String encodedSha256 = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encodedBytes));
        EncodedFallbackBlob overflow = new EncodedFallbackBlob(
                seed.originalBytes(),
                encodedBytes,
                seed.keyBytes(),
                seed.originalSha256(),
                encodedSha256,
                seed.compressionAlgorithm(),
                seed.encryptionAlgorithm(),
                seed.encodingVersion());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> codec.decode(overflow));
        assertEquals("fallback blob decoded length exceeds compressed payload capacity", error.getMessage());
    }

    @Test
    void javaFallbackSupportDefinesOwnerPackageHiddenClass() throws Exception {
        FallbackHelperClass helperClass = new FallbackHelperClassFactory().create(
                "substring__1234",
                "xyz/melodysky/packaging/FallbackBlobPlannerTest#substring!(Ljava/lang/String;)Ljava/lang/String;",
                "xyz/melodysky/packaging/FallbackBlobPlannerTest");

        Class<?> hidden = defineHiddenFallback(FallbackBlobPlannerTest.class, helperClass.bytes());
        Method method = hidden.getDeclaredMethod(FallbackHelperClassFactory.HELPER_METHOD_NAME, String.class);

        assertTrue(hidden.isHidden());
        assertEquals("bc", method.invoke(null, "abc"));
    }

    @Test
    void helperGenerationCopiesOriginalFallbackMethodBody() throws Exception {
        ParsedMethod parsedMethod = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "xyz/melodysky/packaging/FallbackOriginal.class",
                        AsmFixtureBuilder.classWithUnsupportedJdkStringCall("xyz/melodysky/packaging/FallbackOriginal"),
                        "fixture"))
                .artifact()
                .orElseThrow()
                .methods()
                .stream()
                .filter(method -> method.name().equals("substring"))
                .findFirst()
                .orElseThrow();

        FallbackHelperClass helperClass = new FallbackHelperClassFactory().create("copy__1234", parsedMethod);
        new ClassReader(helperClass.bytes());
        Class<?> hidden = defineHiddenFallback(FallbackBlobPlannerTest.class, helperClass.bytes());
        Method method = hidden.getDeclaredMethod(FallbackHelperClassFactory.HELPER_METHOD_NAME, String.class);

        assertEquals("bc", method.invoke(null, "abc"));
    }

    @Test
    void copiedFallbackBodyPreservesProtectedNestedArrayExceptionFlow() throws Exception {
        String owner = "xyz/melodysky/packaging/FallbackBlobPlannerTest";
        ParsedMethod parsedMethod = new AsmClassParser()
                .parse(new ClassFileEntry(
                        owner + ".class",
                        AsmFixtureBuilder.classWithProtectedReferenceArrayAllocation(owner, "[B"),
                        "fixture"))
                .artifact()
                .orElseThrow()
                .methods()
                .stream()
                .filter(method -> method.name().equals("array"))
                .findFirst()
                .orElseThrow();

        FallbackHelperClass helperClass = new FallbackHelperClassFactory().create("matrix__1234", parsedMethod);
        Class<?> hidden = defineHiddenFallback(FallbackBlobPlannerTest.class, helperClass.bytes());
        Method method = hidden.getDeclaredMethod(FallbackHelperClassFactory.HELPER_METHOD_NAME, int.class);

        assertEquals(3, method.invoke(null, 3));
        assertEquals(-1, method.invoke(null, -1));
    }

    @Test
    void copiedFallbackRoutesReferenceFieldThroughPassedSidecarWithoutLocalCollision()
            throws Exception {
        String owner = "xyz/melodysky/packaging/FallbackBlobPlannerTest";
        ParsedMethod parsedMethod = new AsmClassParser()
                .parse(new ClassFileEntry(
                        owner + ".class",
                        fallbackReferenceFieldFixture(owner),
                        "fixture"))
                .artifact()
                .orElseThrow()
                .methods()
                .stream()
                .filter(method -> method.name().equals("swap"))
                .findFirst()
                .orElseThrow();
        FallbackSidecarFieldAccess access = new FallbackSidecarFieldAccess(
                new FieldId(
                        owner,
                        "fallbackFieldSentinel",
                        "Ljava/lang/String;"),
                new NativeFieldSlotRef(
                        NativeFieldStorageKind.REFERENCE,
                        "j2ll_nfs_test",
                        0),
                1,
                1);

        FallbackHelperClass helperClass =
                new FallbackHelperClassFactory().create(
                        "sidecar__1234",
                        parsedMethod,
                        List.of(access));
        ClassNode helperNode = new ClassNode();
        new ClassReader(helperClass.bytes()).accept(helperNode, 0);
        assertFalse(hasFieldAccess(
                helperNode,
                owner,
                "fallbackFieldSentinel",
                "Ljava/lang/String;"));
        assertEquals(1, countOpcode(helperNode, Opcodes.AALOAD));
        assertEquals(1, countOpcode(helperNode, Opcodes.AASTORE));
        assertEquals(
                "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
                helperNode.methods.stream()
                        .filter(candidate -> candidate.name.equals(
                                FallbackHelperClassFactory.HELPER_METHOD_NAME))
                        .findFirst()
                        .orElseThrow()
                        .desc);

        NativeEmbeddedFallbackBlob plannedBlob = new FallbackBlobPlanner()
                .plan(List.of(new FallbackBlobInput(
                        "sidecar__1234",
                        parsedMethod.methodKey(),
                        parsedMethod.owner(),
                        parsedMethod.name(),
                        parsedMethod.descriptor(),
                        parsedMethod.accessFlags().isStatic(),
                        parsedMethod.methodNode(),
                        "UNSUPPORTED_NESTED_FINALLY",
                        List.of(access))))
                .get(0);
        assertEquals(
                "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
                plannedBlob.fallbackInvokeDescriptor());

        Class<?> hidden = defineHiddenFallback(
                FallbackBlobPlannerTest.class,
                helperClass.bytes());
        Method method = hidden.getDeclaredMethod(
                FallbackHelperClassFactory.HELPER_METHOD_NAME,
                String.class,
                Object[].class);
        Object[] sidecar = new Object[] {"sidecar-old"};
        fallbackFieldSentinel = "java-field";

        assertEquals("sidecar-old", method.invoke(null, "sidecar-new", sidecar));
        assertEquals("sidecar-new", sidecar[0]);
        assertEquals("java-field", fallbackFieldSentinel);
    }

    @Test
    void hiddenClassCapabilityResolverSelectsStableFallbackReasons() {
        FallbackDefinitionCapabilityResolver resolver = new FallbackDefinitionCapabilityResolver();

        FallbackDefinitionCapability oldJdk = resolver.resolve(11, true);
        FallbackDefinitionCapability missingLookup = resolver.resolve(17, false);
        FallbackDefinitionCapability hidden = resolver.resolve(17, true);

        assertEquals("DefineClass", oldJdk.definitionMechanism());
        assertEquals("FALLBACK_HIDDEN_CLASS_UNAVAILABLE", oldJdk.reasonCode());
        assertEquals("DefineClass", missingLookup.definitionMechanism());
        assertEquals("FALLBACK_HIDDEN_CLASS_UNSUPPORTED_ACCESS", missingLookup.reasonCode());
        assertEquals("HiddenClass", hidden.definitionMechanism());
        assertEquals("FALLBACK_HIDDEN_CLASS", hidden.reasonCode());
    }

    private byte[] xorLikeRuntime(byte[] input, byte[] key) {
        byte[] output = new byte[input.length];
        for (int index = 0; index < input.length; index++) {
            int stream = (key[index % key.length] & 0xff) ^ ((index * 31 + (index >>> 3)) & 0xff);
            output[index] = (byte) ((input[index] & 0xff) ^ stream);
        }
        return output;
    }

    private byte[] fallbackReferenceFieldFixture(String owner) {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                owner,
                null,
                "java/lang/Object",
                null);
        writer.visitField(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                        "fallbackFieldSentinel",
                        "Ljava/lang/String;",
                        null,
                        null)
                .visitEnd();
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "swap",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        method.visitCode();
        method.visitFieldInsn(
                Opcodes.GETSTATIC,
                owner,
                "fallbackFieldSentinel",
                "Ljava/lang/String;");
        method.visitVarInsn(Opcodes.ASTORE, 1);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(
                Opcodes.PUTSTATIC,
                owner,
                "fallbackFieldSentinel",
                "Ljava/lang/String;");
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private boolean hasFieldAccess(
            ClassNode helper,
            String owner,
            String name,
            String descriptor) {
        for (var method : helper.methods) {
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof FieldInsnNode field
                        && field.owner.equals(owner)
                        && field.name.equals(name)
                        && field.desc.equals(descriptor)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countOpcode(ClassNode helper, int opcode) {
        int count = 0;
        for (var method : helper.methods) {
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction.getOpcode() == opcode) {
                    count++;
                }
            }
        }
        return count;
    }

    private Class<?> defineHiddenFallback(Class<?> owner, byte[] classBytes) throws IllegalAccessException {
        return MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
                .defineHiddenClass(classBytes, true, MethodHandles.Lookup.ClassOption.NESTMATE)
                .lookupClass();
    }
}
