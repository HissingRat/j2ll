package zoo.advanced;

import zoo.Case;

public final class ComplexFinallyBoundaryCase implements Case {
    @Override
    public String name() {
        return "ComplexFinallyBoundaryCase";
    }

    @Override
    public String run() {
        return multiExitFinally(2) + ":" + nestedFinally(false) + ":" + monitorFinally();
    }

    private static int multiExitFinally(int value) {
        int state = value;
        try {
            if (value > 0) {
                return state + 10;
            }
            return state - 10;
        } finally {
            state += 100;
        }
    }

    private static int nestedFinally(boolean fail) {
        int state = 1;
        try {
            try {
                if (fail) {
                    throw new IllegalStateException("nested");
                }
                state += 2;
            } finally {
                state += 4;
            }
        } finally {
            state += 8;
        }
        return state;
    }

    private static int monitorFinally() {
        Object lock = new Object();
        int state = 0;
        synchronized (lock) {
            try {
                state = 6;
            } finally {
                state += 7;
            }
        }
        return state;
    }
}
