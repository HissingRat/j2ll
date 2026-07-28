package xyz.melodysky.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Small javac-produced corpus for protected JVM/JNI exception-flow tests.
 *
 * <p>The methods deliberately keep a non-parameter local live into their
 * handlers. This mirrors the bytecode shape that previously produced
 * {@code UNSUPPORTED_EXCEPTION_STATE_MERGE}, while String helpers provide a
 * deterministic JNI pending-exception source.</p>
 */
public final class ProtectedExceptionFlowFixture {
    public static final String OPS_INTERNAL_NAME = "pkg/ProtectedExceptionOps";
    public static final String MAIN_CLASS = "pkg.ProtectedExceptionMain";

    private ProtectedExceptionFlowFixture() {}

    public static List<String> selectors() {
        return List.of(
                OPS_INTERNAL_NAME + "#typedAndContinue!(Ljava/lang/String;I)I",
                OPS_INTERNAL_NAME + "#catchAllAndContinue!(Ljava/lang/String;I)I",
                OPS_INTERNAL_NAME + "#typedOrRethrow!(Ljava/lang/String;I)I",
                OPS_INTERNAL_NAME + "#finallyAndRethrow!(Ljava/lang/String;I)I");
    }

    public static String expectedOutput() {
        return """
                typed-ok=5
                typed-null=110
                typed-bounds=217
                catch-all=310
                typed-caught=420
                typed-unmatched=NullPointerException
                finally-ok=19/15
                finally-unmatched=NullPointerException/17
                """;
    }

    public static Path compileJar(Path root) throws IOException {
        Path sourceRoot = root.resolve("source");
        Path classesRoot = root.resolve("classes");
        Path packageRoot = sourceRoot.resolve("pkg");
        Files.createDirectories(packageRoot);
        Files.createDirectories(classesRoot);
        Path opsSource = packageRoot.resolve("ProtectedExceptionOps.java");
        Path mainSource = packageRoot.resolve("ProtectedExceptionMain.java");
        Files.writeString(opsSource, opsSource(), StandardCharsets.UTF_8);
        Files.writeString(mainSource, mainSource(), StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "protected exception-flow fixture requires a JDK");
        int exitCode = compiler.run(
                null,
                null,
                null,
                "--release",
                "17",
                "-g",
                "-d",
                classesRoot.toString(),
                opsSource.toString(),
                mainSource.toString());
        assertEquals(0, exitCode, "protected exception-flow fixture compilation");

        Path jar = root.resolve("protected-exception-flow.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                var files = Files.walk(classesRoot)) {
            for (Path classFile : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted(Comparator.comparing(path -> classesRoot.relativize(path).toString()))
                    .toList()) {
                String entryName = classesRoot.relativize(classFile)
                        .toString()
                        .replace('\\', '/');
                JarEntry entry = new JarEntry(entryName);
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(Files.readAllBytes(classFile));
                output.closeEntry();
            }
        }
        return jar;
    }

    public static byte[] classBytes(Path jar, String internalName) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(internalName + ".class");
            if (entry == null) {
                throw new IOException("missing fixture class " + internalName);
            }
            return jarFile.getInputStream(entry).readAllBytes();
        }
    }

    private static String opsSource() {
        return """
                package pkg;

                public final class ProtectedExceptionOps {
                    private static int cleanupMarker;

                    private ProtectedExceptionOps() {}

                    public static int typedAndContinue(String text, int marker) {
                        int throwSiteLocal = marker + 7;
                        int result;
                        try {
                            result = text.substring(marker, text.length()).length();
                        } catch (NullPointerException ignored) {
                            result = throwSiteLocal + 100;
                        } catch (IndexOutOfBoundsException ignored) {
                            result = throwSiteLocal + 200;
                        }
                        return result + 1;
                    }

                    public static int catchAllAndContinue(String text, int marker) {
                        int throwSiteLocal = marker * 3;
                        int result;
                        try {
                            result = text.substring(marker, text.length()).length();
                        } catch (Throwable ignored) {
                            result = throwSiteLocal + 300;
                        }
                        return result + 1;
                    }

                    public static int typedOrRethrow(String text, int marker) {
                        int throwSiteLocal = marker + 11;
                        try {
                            return text.substring(marker, text.length()).length() + throwSiteLocal;
                        } catch (IndexOutOfBoundsException ignored) {
                            return throwSiteLocal + 400;
                        }
                    }

                    public static int finallyAndRethrow(String text, int marker) {
                        int throwSiteLocal = marker + 13;
                        try {
                            return text.substring(marker, text.length()).length() + throwSiteLocal;
                        } finally {
                            cleanupMarker = throwSiteLocal;
                        }
                    }

                    public static int lateStoreInProtectedBlock(String text, int marker) {
                        String handlerLocal = null;
                        try {
                            int length = text.length();
                            handlerLocal = "assigned";
                            return text.substring(marker, length).length();
                        } catch (Throwable ignored) {
                            return handlerLocal == null ? marker + 500 : marker + 600;
                        }
                    }

                    public static int unprotectedLengthAndMarker(String text, int marker) {
                        int length = text.length();
                        cleanupMarker = marker;
                        return length + marker;
                    }

                    public static int cleanupMarker() {
                        return cleanupMarker;
                    }
                }
                """;
    }

    private static String mainSource() {
        return """
                package pkg;

                public final class ProtectedExceptionMain {
                    private ProtectedExceptionMain() {}

                    public static void main(String[] args) {
                        System.out.println("typed-ok="
                                + ProtectedExceptionOps.typedAndContinue("abcdef", 2));
                        System.out.println("typed-null="
                                + ProtectedExceptionOps.typedAndContinue(null, 2));
                        System.out.println("typed-bounds="
                                + ProtectedExceptionOps.typedAndContinue("abc", 9));
                        System.out.println("catch-all="
                                + ProtectedExceptionOps.catchAllAndContinue(null, 3));
                        System.out.println("typed-caught="
                                + ProtectedExceptionOps.typedOrRethrow("abc", 9));
                        try {
                            ProtectedExceptionOps.typedOrRethrow(null, 2);
                            System.out.println("typed-unmatched=missing");
                        } catch (NullPointerException expected) {
                            System.out.println("typed-unmatched="
                                    + expected.getClass().getSimpleName());
                        }
                        System.out.println("finally-ok="
                                + ProtectedExceptionOps.finallyAndRethrow("abcdef", 2)
                                + "/"
                                + ProtectedExceptionOps.cleanupMarker());
                        try {
                            ProtectedExceptionOps.finallyAndRethrow(null, 4);
                            System.out.println("finally-unmatched=missing");
                        } catch (NullPointerException expected) {
                            System.out.println("finally-unmatched="
                                    + expected.getClass().getSimpleName()
                                    + "/"
                                    + ProtectedExceptionOps.cleanupMarker());
                        }
                    }
                }
                """;
    }
}
