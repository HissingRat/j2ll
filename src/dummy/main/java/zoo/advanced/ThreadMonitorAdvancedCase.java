package zoo.advanced;

import zoo.Case;

public final class ThreadMonitorAdvancedCase implements Case {
    private int counter;

    @Override
    public String name() {
        return "ThreadMonitorAdvancedCase";
    }

    @Override
    public String run() throws Exception {
        Object lock = new Object();
        synchronized (lock) {
            counter += 2;
        }
        synchronizedIncrement();
        Thread thread = new Thread(() -> {
            synchronized (this) {
                counter += 5;
            }
        });
        thread.start();
        thread.join();
        String waitBoundary;
        try {
            lock.wait(1L);
            waitBoundary = "none";
        } catch (IllegalMonitorStateException expected) {
            waitBoundary = expected.getClass().getSimpleName();
        }
        return counter + ":" + waitBoundary;
    }

    private synchronized void synchronizedIncrement() {
        counter += 3;
    }
}
