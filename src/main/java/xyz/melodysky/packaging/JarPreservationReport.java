package xyz.melodysky.packaging;

public record JarPreservationReport(
        boolean manifestPreserved,
        int serviceEntriesPreserved,
        boolean moduleInfoPreserved,
        boolean multiRelease,
        int versionedEntriesPreserved,
        String versionedClassPolicy) {
    public static JarPreservationReport empty() {
        return new JarPreservationReport(false, 0, false, false, 0, "baseClassesOnly");
    }
}
