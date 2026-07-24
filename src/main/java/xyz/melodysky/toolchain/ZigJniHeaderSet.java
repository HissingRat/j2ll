package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class ZigJniHeaderSet {
    private static final String PORTABLE_JNI_MD = """
            #ifndef _JAVASOFT_JNI_MD_H_
            #define _JAVASOFT_JNI_MD_H_

            #if defined(_WIN32)
            #ifndef JNIEXPORT
            #define JNIEXPORT __declspec(dllexport)
            #endif
            #define JNIIMPORT __declspec(dllimport)
            #define JNICALL __stdcall
            typedef __int64 jlong;
            #else
            #ifndef JNIEXPORT
            #define JNIEXPORT __attribute__((visibility("default")))
            #endif
            #define JNIIMPORT
            #define JNICALL
            typedef long jlong;
            #endif

            typedef int jint;
            typedef signed char jbyte;

            #endif
            """;

    public List<Path> prepare(ZigBuildWorkspace workspace) throws IOException {
        Path javaInclude = Path.of(System.getProperty("java.home")).resolve("include");
        Path jniHeader = javaInclude.resolve("jni.h");
        if (!Files.isRegularFile(jniHeader)) {
            throw new IOException("current JDK does not provide JNI headers: " + jniHeader);
        }
        Path portableInclude = workspace.jniDirectory().resolve("include");
        Files.createDirectories(portableInclude);
        Files.copy(
                jniHeader,
                portableInclude.resolve("jni.h"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(
                portableInclude.resolve("jni_md.h"),
                PORTABLE_JNI_MD,
                StandardCharsets.UTF_8);
        return List.of(portableInclude);
    }
}
