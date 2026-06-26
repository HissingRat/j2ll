package xyz.melodysky.ir.ssa;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import xyz.melodysky.ir.model.IrValue;

public final class LocalState {
    private final Map<Integer, IrValue> locals = new HashMap<>();

    public LocalState() {
    }

    public LocalState(Map<Integer, IrValue> locals) {
        this.locals.putAll(locals);
    }

    public void set(int slot, IrValue value) {
        locals.put(slot, value);
    }

    public IrValue get(int slot) {
        IrValue value = locals.get(slot);
        if (value == null) {
            throw new IllegalStateException("local slot " + slot + " is undefined");
        }
        return value;
    }

    public Map<Integer, IrValue> snapshot() {
        return Map.copyOf(new TreeMap<>(locals));
    }
}
