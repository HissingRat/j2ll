package xyz.melodysky.ir.pass.protection;

public record MethodInliningOptions(
        boolean enabled,
        long seed,
        int maxCalleeInstructions,
        int maxSitesPerCaller) {
    public MethodInliningOptions {
        if (maxCalleeInstructions < 1) {
            throw new IllegalArgumentException("maxCalleeInstructions must be positive");
        }
        if (maxSitesPerCaller < 1) {
            throw new IllegalArgumentException("maxSitesPerCaller must be positive");
        }
    }

    public static MethodInliningOptions enabled(long seed) {
        return new MethodInliningOptions(true, seed, 24, 8);
    }

    public static MethodInliningOptions disabled(long seed) {
        return new MethodInliningOptions(false, seed, 24, 8);
    }
}
