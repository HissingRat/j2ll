package zoo.basic;

import zoo.Case;

public final class ExceptionBasicCase implements Case {
    @Override
    public String name() {
        return "ExceptionBasicCase";
    }

    @Override
    public String run() {
        int cleanup = 0;
        String first;
        try {
            throw new IllegalArgumentException("bad-arg");
        } catch (RuntimeException exception) {
            first = exception.getClass().getSimpleName() + ":" + exception.getMessage();
        } finally {
            cleanup++;
        }
        RuntimeException outer = new RuntimeException("outer");
        outer.initCause(new IllegalStateException("inner"));
        return first + ":" + cleanup + ":" + outer.getCause().getMessage() + ":" + singleExitFinally()
                + ":" + catchCode();
    }

    public static int catchCode() {
        try {
            throw new IllegalArgumentException("typed");
        } catch (IllegalArgumentException expected) {
            return 7;
        }
    }

    private static int singleExitFinally() {
        int value = 4;
        try {
            value += 8;
            return value;
        } finally {
            value += 16;
        }
    }
}
