package xyz.melodysky.process;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

public final class SubprocessRegistry {

    private static final Set<ProcessHandle> ACTIVE_PROCESSES = ConcurrentHashMap.newKeySet();
    private static volatile boolean shutdownHookInstalled = false;
    private static volatile boolean shutdownRequested = false;
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private SubprocessRegistry() {
    }

    public static Registration register(Process process) {
        Objects.requireNonNull(process, "process");
        ensureShutdownHookInstalled();
        ProcessHandle handle = process.toHandle();
        ACTIVE_PROCESSES.add(handle);
        return () -> ACTIVE_PROCESSES.remove(handle);
    }

    public static void destroyProcessTree(Process process) {
        if (process == null) {
            return;
        }
        destroyProcessTree(process.toHandle());
    }

    public static boolean isShutdownRequested() {
        return shutdownRequested;
    }

    public static void requestShutdownNow() {
        shutdownRequested = true;
        destroyAll();
    }

    static int activeProcessCountForTest() {
        return ACTIVE_PROCESSES.size();
    }

    static void destroyAllForTest() {
        destroyAll();
        ACTIVE_PROCESSES.clear();
        shutdownRequested = false;
    }

    private static void ensureShutdownHookInstalled() {
        if (shutdownHookInstalled) {
            return;
        }
        synchronized (SubprocessRegistry.class) {
            if (shutdownHookInstalled) {
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                requestShutdownNow();
            }, "native-obf-subprocess-shutdown"));
            shutdownHookInstalled = true;
        }
    }

    private static void destroyAll() {
        for (ProcessHandle handle : new ArrayList<>(ACTIVE_PROCESSES)) {
            destroyProcessTree(handle);
        }
    }

    private static void destroyProcessTree(ProcessHandle root) {
        if (root == null) {
            return;
        }
        if (WINDOWS) {
            destroyWindowsProcessTree(root);
            return;
        }

        ArrayList<ProcessHandle> descendants = new ArrayList<>(root.descendants().toList());
        Collections.reverse(descendants);
        for (ProcessHandle handle : descendants) {
            destroyQuietly(handle, false);
        }
        destroyQuietly(root, false);

        sleepQuietly(150L);

        for (ProcessHandle handle : descendants) {
            destroyQuietly(handle, true);
            ACTIVE_PROCESSES.remove(handle);
        }
        destroyQuietly(root, true);
        ACTIVE_PROCESSES.remove(root);
    }

    private static void destroyWindowsProcessTree(ProcessHandle root) {
        long pid = root.pid();
        try {
            Process taskkill = new ProcessBuilder(windowsTaskkillCommand(pid))
                    .redirectErrorStream(true)
                    .start();
            try (InputStream input = taskkill.getInputStream()) {
                input.readAllBytes();
            }
            taskkill.waitFor(5, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
            destroyQuietly(root, true);
        }

        sleepQuietly(150L);

        for (ProcessHandle handle : new ArrayList<>(root.descendants().toList())) {
            destroyQuietly(handle, true);
            ACTIVE_PROCESSES.remove(handle);
        }
        destroyQuietly(root, true);
        ACTIVE_PROCESSES.remove(root);
    }

    static java.util.List<String> windowsTaskkillCommand(long pid) {
        return java.util.List.of(
                "taskkill",
                "/PID",
                Long.toString(pid),
                "/T",
                "/F"
        );
    }

    private static void destroyQuietly(ProcessHandle handle, boolean forcibly) {
        try {
            if (handle.isAlive()) {
                if (forcibly) {
                    handle.destroyForcibly();
                } else {
                    handle.destroy();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
