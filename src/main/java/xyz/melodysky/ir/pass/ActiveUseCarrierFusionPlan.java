package xyz.melodysky.ir.pass;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import xyz.melodysky.ir.model.IrOpcode;

/**
 * Exact same-block class-initialization carrier chains that may be collapsed
 * into the original JVM-backed operation followed by an active-use marker.
 */
public record ActiveUseCarrierFusionPlan(List<Site> sites) {
    public ActiveUseCarrierFusionPlan {
        sites = List.copyOf(Objects.requireNonNull(sites, "sites"));
        Set<String> occupied = new HashSet<>();
        for (Site site : sites) {
            for (int index = site.carrierStartIndex();
                    index <= site.activeUseIndex();
                    index++) {
                if (!occupied.add(site.blockName() + "\0" + index)) {
                    throw new IllegalArgumentException(
                            "active-use carrier fusion sites overlap");
                }
            }
        }
        sites = sites.stream()
                .sorted(Comparator.comparing(Site::blockName)
                        .thenComparingInt(Site::carrierStartIndex))
                .toList();
    }

    public boolean isEmpty() {
        return sites.isEmpty();
    }

    public record Site(
            String blockName,
            int carrierStartIndex,
            int activeUseIndex,
            String classSymbol,
            IrOpcode activeUseOpcode) {
        public Site {
            Objects.requireNonNull(blockName, "blockName");
            Objects.requireNonNull(classSymbol, "classSymbol");
            Objects.requireNonNull(activeUseOpcode, "activeUseOpcode");
            if (blockName.isBlank()
                    || !classSymbol.startsWith("class:L")
                    || !classSymbol.endsWith(";")
                    || classSymbol.length() <= "class:L;".length()
                    || classSymbol.substring(
                                    "class:L".length(),
                                    classSymbol.length() - 1)
                            .indexOf(';') >= 0
                    || (activeUseOpcode != IrOpcode.GET_STATIC
                            && activeUseOpcode != IrOpcode.PUT_STATIC
                            && activeUseOpcode != IrOpcode.CALL_STATIC)
                    || carrierStartIndex < 0
                    || activeUseIndex != carrierStartIndex + 4) {
                throw new IllegalArgumentException(
                        "invalid active-use carrier fusion site");
            }
        }
    }
}
