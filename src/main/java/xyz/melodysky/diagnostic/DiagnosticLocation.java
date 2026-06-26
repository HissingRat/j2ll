package xyz.melodysky.diagnostic;

public record DiagnosticLocation(
        String className,
        String methodName,
        String descriptor,
        Integer instructionOffset,
        String artifactId) {
    private static final DiagnosticLocation NONE = new DiagnosticLocation(null, null, null, null, null);

    public static DiagnosticLocation none() {
        return NONE;
    }

    public static DiagnosticLocation classLocation(String className) {
        return new DiagnosticLocation(className, null, null, null, className);
    }

    public static DiagnosticLocation methodLocation(String className, String methodName, String descriptor) {
        return new DiagnosticLocation(className, methodName, descriptor, null,
                className + "#" + methodName + "!" + descriptor);
    }

    public DiagnosticLocation withInstructionOffset(int offset) {
        return new DiagnosticLocation(className, methodName, descriptor, offset, artifactId);
    }
}
