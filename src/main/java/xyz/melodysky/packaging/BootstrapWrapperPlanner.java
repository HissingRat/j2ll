package xyz.melodysky.packaging;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

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
        String token = safeSymbol(owner);
        return new BootstrapWrapperPlan(
                owner,
                "j2ll_bootstrap_" + token,
                "j2ll_register_" + token,
                "j2ll/generated/" + token + "/NativeLoader");
    }

    private String safeSymbol(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')) {
                result.append(ch);
            } else {
                result.append('_');
                if (ch > 127) {
                    result.append(Integer.toHexString(ch).toLowerCase(Locale.ROOT));
                    result.append('_');
                }
            }
        }
        return result.toString();
    }
}
