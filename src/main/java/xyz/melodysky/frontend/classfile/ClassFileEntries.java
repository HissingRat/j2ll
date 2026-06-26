package xyz.melodysky.frontend.classfile;

import java.util.Comparator;
import java.util.List;

public final class ClassFileEntries {
    private ClassFileEntries() {
    }

    public static boolean isClassEntry(String entryName) {
        return entryName != null && entryName.endsWith(".class") && !entryName.endsWith("/");
    }

    public static List<ClassFileEntry> stableSorted(List<ClassFileEntry> entries) {
        return entries.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
