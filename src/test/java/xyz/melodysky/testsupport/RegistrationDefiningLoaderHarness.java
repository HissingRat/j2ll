package xyz.melodysky.testsupport;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Child process used to validate JNI_OnLoad defining-loader lookup. */
public final class RegistrationDefiningLoaderHarness {
    private RegistrationDefiningLoaderHarness() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "expected <fixture.jar> <native-library>");
        }
        URL jar = Path.of(args[0]).toUri().toURL();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (ChildFirstLoader loader = new ChildFirstLoader(jar)) {
            thread.setContextClassLoader(null);
            Class<?> owner = Class.forName(
                    "registration.fixture.Owner",
                    true,
                    loader);
            Object result = owner.getMethod(
                            "loadAndValue",
                            String.class)
                    .invoke(null, Path.of(args[1]).toAbsolutePath().toString());
            System.out.println(result);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static final class ChildFirstLoader extends URLClassLoader {
        private ChildFirstLoader(URL jar) {
            super(
                    new URL[] {jar},
                    ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> loadClass(
                String name,
                boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("registration.fixture.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
