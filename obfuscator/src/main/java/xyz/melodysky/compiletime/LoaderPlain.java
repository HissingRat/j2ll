package xyz.melodysky.compiletime;

public class LoaderPlain {
    public static void ensureLoaded() {
    }

    public static native void registerNativesForClass(int index, Class<?> clazz);

    static {
        System.loadLibrary("%LIB_NAME%");
    }
}
