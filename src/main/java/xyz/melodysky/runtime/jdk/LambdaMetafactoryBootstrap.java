package xyz.melodysky.runtime.jdk;

import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import xyz.melodysky.diagnostic.DiagnosticCode;

public final class LambdaMetafactoryBootstrap {
    public static final int FLAG_SERIALIZABLE = 1;
    public static final int FLAG_MARKERS = 2;
    public static final int FLAG_BRIDGES = 4;

    public LambdaMetafactoryPlan parse(Handle bootstrapMethod, Object[] bootstrapArguments) {
        if (!bootstrapMethod.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
            return new LambdaMetafactoryPlan(false, false, Optional.empty(), "not LambdaMetafactory");
        }
        if (bootstrapMethod.getName().equals("altMetafactory")) {
            return parseAltMetafactory(bootstrapArguments);
        }
        if (!bootstrapMethod.getName().equals("metafactory")) {
            return new LambdaMetafactoryPlan(
                    true,
                    false,
                    Optional.empty(),
                    "unsupported LambdaMetafactory method " + bootstrapMethod.getName());
        }
        if (bootstrapArguments.length < 2 || !(bootstrapArguments[1] instanceof Handle implementationHandle)) {
            return new LambdaMetafactoryPlan(true, false, Optional.empty(), "metafactory missing implementation MethodHandle");
        }
        return new LambdaMetafactoryPlan(true, true, Optional.of(implementationHandle), "metafactory");
    }

    private LambdaMetafactoryPlan parseAltMetafactory(Object[] bootstrapArguments) {
        if (bootstrapArguments.length < 4 || !(bootstrapArguments[1] instanceof Handle implementationHandle)) {
            return unsupportedAlt("altMetafactory missing implementation MethodHandle");
        }
        if (!(bootstrapArguments[3] instanceof Integer flags)) {
            return unsupportedAlt("altMetafactory flags are not constant");
        }
        int index = 4;
        ArrayList<String> markers = new ArrayList<>();
        if ((flags & FLAG_MARKERS) != 0) {
            if (index >= bootstrapArguments.length || !(bootstrapArguments[index++] instanceof Integer markerCount)) {
                return unsupportedAlt("altMetafactory marker count missing");
            }
            for (int markerIndex = 0; markerIndex < markerCount; markerIndex++) {
                if (index >= bootstrapArguments.length || !(bootstrapArguments[index++] instanceof Type markerType)) {
                    return unsupportedAlt("altMetafactory marker type missing");
                }
                markers.add(markerType.getInternalName());
            }
        }
        ArrayList<String> bridges = new ArrayList<>();
        if ((flags & FLAG_BRIDGES) != 0) {
            if (index >= bootstrapArguments.length || !(bootstrapArguments[index++] instanceof Integer bridgeCount)) {
                return unsupportedAlt("altMetafactory bridge count missing");
            }
            for (int bridgeIndex = 0; bridgeIndex < bridgeCount; bridgeIndex++) {
                if (index >= bootstrapArguments.length || !(bootstrapArguments[index++] instanceof Type bridgeType)) {
                    return unsupportedAlt("altMetafactory bridge method type missing");
                }
                bridges.add(bridgeType.getDescriptor());
            }
        }
        int supportedFlags = FLAG_SERIALIZABLE | FLAG_MARKERS | FLAG_BRIDGES;
        if ((flags & ~supportedFlags) != 0) {
            return unsupportedAlt("unsupported altMetafactory flags " + flags);
        }
        return new LambdaMetafactoryPlan(
                true,
                true,
                Optional.of(implementationHandle),
                (flags & FLAG_SERIALIZABLE) != 0,
                markers,
                bridges,
                DiagnosticCode.ALT_METAFACTORY_UNSUPPORTED,
                "altMetafactory");
    }

    private LambdaMetafactoryPlan unsupportedAlt(String reason) {
        return new LambdaMetafactoryPlan(
                true,
                false,
                Optional.empty(),
                DiagnosticCode.ALT_METAFACTORY_UNSUPPORTED,
                reason);
    }
}
