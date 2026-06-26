package xyz.melodysky.analysis.callgraph;

import xyz.melodysky.jvm.MethodSignature;

public final class CallSiteIds {
    private CallSiteIds() {
    }

    public static String forInstruction(String owner, MethodSignature caller, int instructionIndex) {
        return owner + "#" + caller + "@" + instructionIndex;
    }
}
