package xyz.melodysky.packaging;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class NativeLoaderLoadHook {
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile Consumer<Class<?>> action = ignored -> {
    };

    private NativeLoaderLoadHook() {
    }

    public static void load(Class<?> anchor) {
        CALLS.incrementAndGet();
        action.accept(anchor);
    }

    static void reset() {
        CALLS.set(0);
        action = ignored -> {
        };
    }

    static void action(Consumer<Class<?>> nextAction) {
        action = Objects.requireNonNull(nextAction, "nextAction");
    }

    static int calls() {
        return CALLS.get();
    }
}
