package zoo.advanced;

import java.io.Serializable;
import zoo.Case;

public final class AltMetafactoryBoundaryCase implements Case {
    @Override
    public String name() {
        return "AltMetafactoryBoundaryCase";
    }

    @Override
    public String run() {
        return supportedRunnableLambda("serial") + ":" + serializableLambda("serial", "-lambda");
    }

    public static String supportedRunnableLambda(String prefix) {
        Runnable action = () -> prefix.length();
        action.run();
        return prefix + "-lambda";
    }

    public static String serializableLambda(String prefix, String suffix) {
        Runnable action = (Runnable & Serializable) () -> {
            prefix.length();
            suffix.length();
        };
        action.run();
        return prefix + suffix + ":" + (action instanceof Serializable);
    }
}
