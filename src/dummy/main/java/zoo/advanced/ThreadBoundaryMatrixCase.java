package zoo.advanced;

import zoo.Case;

public final class ThreadBoundaryMatrixCase implements Case {
    @Override
    public String name() {
        return "ThreadBoundaryMatrixCase";
    }

    @Override
    public String run() throws Exception {
        Worker worker = new Worker();
        Thread thread = constructOnly(worker);
        startOnly(thread);
        joinOnly(thread);
        return "joined-" + worker.value + ":" + waitBoundary() + ":" + notifyBoundary();
    }

    public static Thread constructOnly(Runnable worker) {
        return new Thread(worker);
    }

    public static void startOnly(Thread thread) {
        thread.start();
    }

    public static void joinOnly(Thread thread) throws InterruptedException {
        thread.join();
    }

    public static void waitOnly(Object lock) throws InterruptedException {
        lock.wait(1L);
    }

    public static void notifyOnly(Object lock) {
        lock.notify();
    }

    private static String waitBoundary() {
        Object lock = new Object();
        try {
            waitOnly(lock);
            return "none";
        } catch (IllegalMonitorStateException expected) {
            return expected.getClass().getSimpleName();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    private static String notifyBoundary() {
        Object lock = new Object();
        try {
            notifyOnly(lock);
            return "none";
        } catch (IllegalMonitorStateException expected) {
            return expected.getClass().getSimpleName();
        }
    }

    private static final class Worker implements Runnable {
        private int value;

        @Override
        public void run() {
            value = 7;
        }
    }
}
