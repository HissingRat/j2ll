package zoo.advanced;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import zoo.Case;

public final class VarHandleBoundaryMatrixCase implements Case {
    private int value = 11;

    @Override
    public String name() {
        return "VarHandleBoundaryMatrixCase";
    }

    @Override
    public String run() throws Exception {
        VarHandle handle = MethodHandles.lookup()
                .findVarHandle(VarHandleBoundaryMatrixCase.class, "value", int.class);
        int previous = getAndAdd(handle, this, 4);
        return previous + ":" + value;
    }

    public static int getAndAdd(VarHandle handle, Object receiver, int delta) {
        return (int) handle.getAndAdd(receiver, delta);
    }
}
