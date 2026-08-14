package xyz.melodysky.ir.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** JVM first-match projection of an ordered IR exception-handler table. */
public final class IrExceptionHandlers {
    private static final String CATCH_ALL = "<any>";

    private IrExceptionHandlers() {}

    public static List<IrExceptionEdge> reachable(
            List<IrExceptionEdge> declaredHandlers) {
        Objects.requireNonNull(declaredHandlers, "declaredHandlers");
        ArrayList<IrExceptionEdge> reachable = new ArrayList<>();
        for (IrExceptionEdge handler : declaredHandlers) {
            IrExceptionEdge nonNull = Objects.requireNonNull(
                    handler,
                    "declared handler");
            reachable.add(nonNull);
            if (CATCH_ALL.equals(nonNull.catchType())) {
                break;
            }
        }
        return List.copyOf(reachable);
    }
}
