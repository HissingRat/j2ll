package xyz.melodysky.packaging;

import java.util.List;
import java.util.TreeSet;
import xyz.melodysky.toolchain.CIdentifier;

public final class BootstrapWrapperPlanner {
    public List<BootstrapWrapperPlan> plan(NativeRegistrationPlan registrationPlan) {
        TreeSet<String> owners = new TreeSet<>();
        for (NativeRegistrationEntry entry : registrationPlan.entries()) {
            owners.add(entry.registrationOwner());
        }
        return owners.stream()
                .map(this::planOwner)
                .toList();
    }

    private BootstrapWrapperPlan planOwner(String owner) {
        String token = CIdentifier.forIdentity(owner);
        return new BootstrapWrapperPlan(
                owner,
                "j2ll_bootstrap_" + token,
                "j2ll_register_" + token);
    }
}
