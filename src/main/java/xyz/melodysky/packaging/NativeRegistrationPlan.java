package xyz.melodysky.packaging;

import java.util.List;
import java.util.TreeSet;

public record NativeRegistrationPlan(List<NativeRegistrationEntry> entries) {
    public NativeRegistrationPlan {
        entries = List.copyOf(new TreeSet<>(entries));
    }
}
