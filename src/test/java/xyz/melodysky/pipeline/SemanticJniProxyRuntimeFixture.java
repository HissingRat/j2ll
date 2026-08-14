package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.testsupport.RealJ2llHostTestSupport;

/** Input JAR and config builder for semantic-surface JNI proxy E2E tests. */
final class SemanticJniProxyRuntimeFixture {
    static final List<String> PROXY_METHODS = List.of(
            "staticIdentity",
            "instanceIdentity",
            "intArrayIdentity",
            "objectArrayIdentity",
            "allocateObject",
            "allocateBytes",
            "readStaticField",
            "readInstanceField",
            "divide",
            "remainder",
            "callStringValueOf",
            "alwaysThrow");
    static final List<String> WRAPPED_METHODS = List.of(
            "<clinit>",
            "<init>",
            "readStaticFromInstance",
            "synchronizedIdentity",
            "narrowBoolean",
            "narrowByte",
            "narrowChar",
            "narrowShort");

    private final Path temp;

    SemanticJniProxyRuntimeFixture(Path temp) {
        this.temp = temp;
    }

    Path writeJar() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null,
                "a full JDK is required for the semantic-proxy fixture");
        Path sourceDirectory = temp.resolve("fixture-src/pkg");
        Path classesDirectory = temp.resolve("fixture-classes");
        Files.createDirectories(sourceDirectory);
        Files.createDirectories(classesDirectory);
        Path operations = sourceDirectory.resolve("SemanticProxyOps.java");
        Path main = sourceDirectory.resolve("SemanticProxyMain.java");
        Files.writeString(
                operations,
                SemanticJniProxyJavaSources.operations(),
                StandardCharsets.UTF_8);
        Files.writeString(
                main,
                SemanticJniProxyJavaSources.main(),
                StandardCharsets.UTF_8);
        int exitCode = compiler.run(
                null,
                null,
                null,
                "--release",
                "17",
                "-d",
                classesDirectory.toString(),
                operations.toString(),
                main.toString());
        assertEquals(0, exitCode, "failed to compile semantic-proxy fixture");

        Path jar = temp.resolve("semantic-proxy.jar");
        try (JarOutputStream output =
                        new JarOutputStream(Files.newOutputStream(jar));
                var paths = Files.walk(classesDirectory)) {
            for (Path classFile : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                JarEntry entry = new JarEntry(
                        classesDirectory.relativize(classFile)
                                .toString()
                                .replace(File.separatorChar, '/'));
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(Files.readAllBytes(classFile));
                output.closeEntry();
            }
        }
        return jar;
    }

    ResolvedConfig config(Path inputJar) {
        JsonObject json = JsonParser.parseString("""
                {
                  "schemaVersion": 1,
                  "jarFile": "%s",
                  "classPath": [],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "CLOSED_WORLD",
                  "outputDirectory": "out",
                  "whiteList": [
                    "pkg/SemanticProxyOps#<clinit>!()V",
                    "pkg/SemanticProxyOps#<init>!()V",
                    "pkg/SemanticProxyOps#staticIdentity!(Ljava/lang/Object;)Ljava/lang/Object;",
                    "pkg/SemanticProxyOps#instanceIdentity!(Ljava/lang/Object;)Ljava/lang/Object;",
                    "pkg/SemanticProxyOps#intArrayIdentity!([I)[I",
                    "pkg/SemanticProxyOps#objectArrayIdentity!([Ljava/lang/Object;)[Ljava/lang/Object;",
                    "pkg/SemanticProxyOps#allocateObject!()Ljava/lang/Object;",
                    "pkg/SemanticProxyOps#allocateBytes!(I)[B",
                    "pkg/SemanticProxyOps#readStaticField!()I",
                    "pkg/SemanticProxyOps#readInstanceField!()I",
                    "pkg/SemanticProxyOps#readStaticFromInstance!()I",
                    "pkg/SemanticProxyOps#divide!(II)I",
                    "pkg/SemanticProxyOps#remainder!(II)I",
                    "pkg/SemanticProxyOps#callStringValueOf!(Ljava/lang/Object;)Ljava/lang/String;",
                    "pkg/SemanticProxyOps#alwaysThrow!()I",
                    "pkg/SemanticProxyOps#synchronizedIdentity!(I)I",
                    "pkg/SemanticProxyOps#narrowBoolean!(Z)Z",
                    "pkg/SemanticProxyOps#narrowByte!(B)B",
                    "pkg/SemanticProxyOps#narrowChar!(C)C",
                    "pkg/SemanticProxyOps#narrowShort!(S)S"
                  ],
                  "blackList": [],
                  "target": %s,
                  "embeddedLibraryDirectory": "semantic_proxy_test",
                  "signaturePolicy": "fail",
                  "signing": null,
                  "intermediates": {
                    "enabled": true,
                    "includeDebugDumps": true,
                    "includePerClassIr": true,
                    "includePerClassLlvm": true,
                    "includePerClassC": true
                  },
                  "protection": {
                    "enabled": true,
                    "seed": "semantic-jni-proxy-real-host-e2e",
                    "ir": {
                      "enabled": false,
                      "controlFlowFlattening": false,
                      "fakeBranches": false,
                      "basicBlockSplitting": false,
                      "constantEncryption": false,
                      "stringEncryption": false,
                      "methodInlining": false,
                      "methodSplitting": false,
                      "callIndirection": false,
                      "fieldInternalization": false,
                      "methodInternalization": false,
                      "publicMethodInternalizationAllowList": [],
                      "methodTableHiding": false,
                      "blockNameObfuscation": false
                    },
                    "llvm": {
                      "enabled": false,
                      "nameObfuscation": false,
                      "opaquePredicates": false,
                      "blockLayoutPerturbation": false,
                      "indirectCalls": false,
                      "globalLayout": false
                    },
                    "binary": {
                      "enabled": true,
                      "hideInternalSymbols": true,
                      "strip": true,
                      "removePdb": true,
                      "symbolAudit": true,
                      "retainUnwindInfo": false
                    }
                  }
                }
                """.formatted(
                inputJar.toString().replace("\\", "\\\\"),
                RealJ2llHostTestSupport.hostTargetJson())).getAsJsonObject();
        return new ConfigLoader().load(json, temp).config().orElseThrow();
    }
}
