package xyz.melodysky.packaging;

import java.util.Map;
import java.util.TreeMap;
import xyz.melodysky.toolchain.CIdentifier;
import xyz.melodysky.toolchain.CSourceEscaper;

public final class RegisterNativesTableBuilder {
    public String emit(NativeRegistrationPlan plan) {
        StringBuilder builder = new StringBuilder();
        Map<String, java.util.List<NativeRegistrationEntry>> byOwner = new TreeMap<>();
        for (NativeRegistrationEntry entry : plan.entries()) {
            byOwner.computeIfAbsent(entry.registrationOwner(), ignored -> new java.util.ArrayList<>()).add(entry);
        }
        for (Map.Entry<String, java.util.List<NativeRegistrationEntry>> ownerEntry : byOwner.entrySet()) {
            String tableName = "j2ll_natives_" + CIdentifier.forIdentity(ownerEntry.getKey());
            builder.append("static JNINativeMethod ")
                    .append(tableName)
                    .append("[] = {\n");
            for (NativeRegistrationEntry entry : ownerEntry.getValue()) {
                builder.append("    {\"")
                        .append(CSourceEscaper.stringContents(entry.methodName()))
                        .append("\", \"")
                        .append(CSourceEscaper.stringContents(entry.descriptor()))
                        .append("\", (void*)")
                        .append(entry.nativeSymbol())
                        .append("},\n");
            }
            builder.append("};\n");
            builder.append("static const int ")
                    .append(tableName)
                    .append("_count = ")
                    .append(ownerEntry.getValue().size())
                    .append(";\n\n");
        }
        return builder.toString();
    }

}
