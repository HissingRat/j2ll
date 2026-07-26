package xyz.melodysky.analysis.field;

@FunctionalInterface
public interface FieldAccessPathResolver {
    FieldAccessImplementationPath finalPathFor(String methodKey);
}
