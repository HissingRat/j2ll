package xyz.melodysky.ir.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class IrExceptionHandlersTest {
    @Test
    void projectsOrderedHandlersThroughTheFirstCatchAll() {
        IrExceptionEdge first = edge("first", "java/lang/RuntimeException");
        IrExceptionEdge second = edge("second", "java/lang/Exception");
        IrExceptionEdge catchAll = edge("all", "<any>");
        IrExceptionEdge unreachable = edge("dead", "java/lang/Throwable");

        assertEquals(List.of(), IrExceptionHandlers.reachable(List.of()));
        assertEquals(
                List.of(first, second),
                IrExceptionHandlers.reachable(List.of(first, second)));
        assertEquals(
                List.of(first, catchAll),
                IrExceptionHandlers.reachable(
                        List.of(first, catchAll, unreachable)));
    }

    @Test
    void rejectsNullHandlerTablesAndEntries() {
        assertThrows(
                NullPointerException.class,
                () -> IrExceptionHandlers.reachable(null));
        assertThrows(
                NullPointerException.class,
                () -> IrExceptionHandlers.reachable(
                        java.util.Arrays.asList(
                                edge("typed", "java/lang/Exception"),
                                null)));
    }

    private IrExceptionEdge edge(String target, String catchType) {
        return new IrExceptionEdge(target, catchType, List.of());
    }
}
