package zoo.basic;

import zoo.Case;

public final class MonitorExceptionBasicCase implements Case {
    private static final Object LOCK = new Object();
    private static final IllegalArgumentException TYPED = new IllegalArgumentException("typed");
    private static int counter;

    @Override
    public String name() {
        return "MonitorExceptionBasicCase";
    }

    @Override
    public String run() throws Exception {
        counter = 0;
        synchronizedIncrement(2);
        String typed = synchronizedThrowAndRecover();
        int reacquired;
        synchronized (LOCK) {
            counter += 5;
            reacquired = counter;
        }
        sleepOnce();
        return typed + ":" + reacquired + ":" + catchAllCode(false) + ":" + catchAllCode(true);
    }

    public static synchronized void synchronizedIncrement(int amount) {
        counter += amount;
    }

    public static String synchronizedThrowAndRecover() {
        try {
            synchronized (LOCK) {
                counter += 3;
                int zero = 0;
                counter += 1 / zero;
            }
        } catch (ArithmeticException expected) {
            return "monitor";
        }
        return "none";
    }

    public static int catchAllCode(boolean throwError) {
        try {
            if (throwError) {
                int zero = 0;
                return 1 / zero;
            }
            throwPassed(TYPED);
            return -1;
        } catch (IllegalArgumentException expected) {
            return 17;
        } catch (Throwable expected) {
            return 23;
        }
    }

    public static void throwPassed(RuntimeException exception) {
        throw exception;
    }

    public static void sleepOnce() throws InterruptedException {
        Thread.sleep(1L);
    }
}
