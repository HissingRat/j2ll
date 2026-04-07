package xyz.melodysky.compiletime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class LoaderUnpack {
    private static final String NATIVE_DIR = "%NATIVE_DIR%";

    public static void ensureLoaded() {
    }

    public static native void registerNativesForClass(int index, Class<?> clazz);

    static {
        String osName = System.getProperty("os.name").toLowerCase();
        String platform = System.getProperty("os.arch").toLowerCase();

        String platformTypeName;
        switch (platform) {
            case "x86_64":
            case "amd64":
                platformTypeName = "x64";
                break;
            case "aarch64":
                platformTypeName = "arm64";
                break;
            case "arm":
                platformTypeName = "arm32";
                break;
            case "x86":
                platformTypeName = "x86";
                break;
            default:
                platformTypeName = "raw" + platform;
                break;
        }

        String osTypeName;
        if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            osTypeName = "linux.so";
        } else if (osName.contains("win")) {
            osTypeName = "windows.dll";
        } else if (osName.contains("mac")) {
            osTypeName = "macos.dylib";
        } else {
            osTypeName = "raw" + osName;
        }

        StringBuilder libFileNameBuilder = new StringBuilder();
        libFileNameBuilder.append('/');
        libFileNameBuilder.append(NATIVE_DIR);
        libFileNameBuilder.append('/');
        libFileNameBuilder.append(platformTypeName);
        libFileNameBuilder.append('-');
        libFileNameBuilder.append(osTypeName);
        String libFileName = libFileNameBuilder.toString();

        File libFile;
        try {
            libFile = File.createTempFile("lib", null);
            libFile.deleteOnExit();
            if (!libFile.exists()) {
                throw new IOException();
            }
        } catch (IOException iOException) {
            throw new UnsatisfiedLinkError("Failed to create temp file");
        }
        byte[] arrayOfByte = new byte[2048];
        try {
            InputStream inputStream = LoaderUnpack.class.getResourceAsStream(libFileName);
            if (inputStream == null) {
                throw new UnsatisfiedLinkError("Failed to open lib file: " + libFileName);
            }
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(libFile);
                try {
                    int size;
                    while ((size = inputStream.read(arrayOfByte)) != -1) {
                        fileOutputStream.write(arrayOfByte, 0, size);
                    }
                    fileOutputStream.close();
                } catch (Throwable throwable) {
                    try {
                        fileOutputStream.close();
                    } catch (Throwable throwable1) {
                        throwable.addSuppressed(throwable1);
                    }
                    throw throwable;
                }
                inputStream.close();
            } catch (Throwable throwable) {
                try {
                    inputStream.close();
                } catch (Throwable throwable1) {
                    throwable.addSuppressed(throwable1);
                }
                throw throwable;
            }
        } catch (IOException exception) {
            throw new UnsatisfiedLinkError("Failed to copy file: " + exception.getMessage());
        }
        System.load(libFile.getAbsolutePath());
    }
}
