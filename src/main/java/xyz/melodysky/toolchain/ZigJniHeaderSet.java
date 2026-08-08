package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class ZigJniHeaderSet {
    private static final String LIBC_FREE_STDIO = """
            #ifndef J2LL_LIBC_FREE_STDIO_H
            #define J2LL_LIBC_FREE_STDIO_H
            /* jni.h includes stdio.h but does not use its declarations. */
            #endif
            """;
    private static final String LIBC_FREE_STDLIB = """
            #ifndef J2LL_LIBC_FREE_STDLIB_H
            #define J2LL_LIBC_FREE_STDLIB_H
            #include <stddef.h>
            #endif
            """;
    private static final String LIBC_FREE_STRING = """
            #ifndef J2LL_LIBC_FREE_STRING_H
            #define J2LL_LIBC_FREE_STRING_H
            #include <stddef.h>
            #endif
            """;
    private static final String LIBC_FREE_MATH = """
            #ifndef J2LL_LIBC_FREE_MATH_H
            #define J2LL_LIBC_FREE_MATH_H
            #define isnan(value) __builtin_isnan(value)
            #endif
            """;
    private static final String PORTABLE_JNI_MD = """
            #ifndef _JAVASOFT_JNI_MD_H_
            #define _JAVASOFT_JNI_MD_H_

            #if defined(_WIN32)
            #ifndef JNIEXPORT
            #define JNIEXPORT __declspec(dllexport)
            #endif
            #define JNIIMPORT __declspec(dllimport)
            typedef long long jlong;
            #else
            #ifndef JNIEXPORT
            #define JNIEXPORT __attribute__((visibility("default")))
            #endif
            #define JNIIMPORT
            typedef long jlong;
            #endif

            typedef int jint;
            typedef signed char jbyte;

            #ifndef JNICALL
            #define JNICALL
            #endif

            #endif
            """;

    public List<Path> prepare(ZigBuildWorkspace workspace) throws IOException {
        return prepare(workspace, NativeLibcRequirementPlan.retaining());
    }

    public List<Path> prepare(
            ZigBuildWorkspace workspace,
            NativeLibcRequirementPlan libcRequirement) throws IOException {
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
        if (libcRequirement.required()) {
            return List.of(portableInclude);
        }
        Path libcFreeInclude = portableInclude.resolve("libc-free");
        Files.createDirectories(libcFreeInclude);
        Files.writeString(
                libcFreeInclude.resolve("stdio.h"),
                LIBC_FREE_STDIO,
                StandardCharsets.UTF_8);
        Files.writeString(
                libcFreeInclude.resolve("stdlib.h"),
                LIBC_FREE_STDLIB,
                StandardCharsets.UTF_8);
        Files.writeString(
                libcFreeInclude.resolve("string.h"),
                LIBC_FREE_STRING,
                StandardCharsets.UTF_8);
        Files.writeString(
                libcFreeInclude.resolve("math.h"),
                LIBC_FREE_MATH,
                StandardCharsets.UTF_8);
        return List.of(portableInclude, libcFreeInclude);
    }
}
