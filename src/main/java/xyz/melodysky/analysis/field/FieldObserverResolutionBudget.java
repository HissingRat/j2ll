package xyz.melodysky.analysis.field;

/** Shared finite work budget for one field-observer provenance query. */
final class FieldObserverResolutionBudget {
    private static final int MAX_STEPS = 4096;

    private int remainingSteps = MAX_STEPS;

    boolean tryConsume() {
        if (remainingSteps == 0) {
            return false;
        }
        remainingSteps--;
        return true;
    }
}
