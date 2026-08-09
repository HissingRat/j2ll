package xyz.melodysky.testsupport;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Child process used to validate JNI_OnLoad defining-loader lookup. */
public final class RegistrationDefiningLoaderHarness {
    private RegistrationDefiningLoaderHarness() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected <fixture.jar>");
        }
        URL jar = Path.of(args[0]).toUri().toURL();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (ChildFirstLoader loader = new ChildFirstLoader(jar)) {
            thread.setContextClassLoader(null);
            Class<?> tracker = Class.forName(
                    RegistrationDefiningLoaderFixture.TRACKER_BINARY_NAME,
                    false,
                    loader);
            requireCount(tracker, "nativeClinitOwnerCount", 0);
            requireCount(tracker, "constructorOwnerClinitCount", 0);

            Class<?> clinitOwner = Class.forName(
                    RegistrationDefiningLoaderFixture.CLINIT_OWNER_BINARY_NAME,
                    true,
                    loader);
            int clinitNativeCalls = (int) clinitOwner
                    .getMethod("nativeCalls")
                    .invoke(null);
            requireCount(tracker, "nativeClinitOwnerCount", 1);
            requireValue("native <clinit> helper calls", clinitNativeCalls, 1);

            Class<?> constructorOwner = Class.forName(
                    RegistrationDefiningLoaderFixture.CONSTRUCTOR_OWNER_BINARY_NAME,
                    false,
                    loader);
            Object instance = constructorOwner
                    .getConstructor(int.class)
                    .newInstance(7);
            int constructorValue = (int) constructorOwner
                    .getMethod("value")
                    .invoke(instance);
            int constructorNativeCalls = (int) constructorOwner
                    .getMethod("nativeCalls")
                    .invoke(null);
            requireCount(tracker, "constructorOwnerClinitCount", 1);
            requireValue("constructor value", constructorValue, 7);
            requireValue("native constructor helper calls", constructorNativeCalls, 1);

            System.out.println("clinit=" + clinitNativeCalls
                    + ",constructor=" + constructorNativeCalls
                    + ",value=" + constructorValue);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void requireCount(
            Class<?> tracker,
            String field,
            int expected) throws ReflectiveOperationException {
        requireValue(field, tracker.getField(field).getInt(null), expected);
    }

    private static void requireValue(
            String subject,
            int actual,
            int expected) {
        if (actual != expected) {
            throw new AssertionError(
                    subject + ": expected " + expected + " but was " + actual);
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
