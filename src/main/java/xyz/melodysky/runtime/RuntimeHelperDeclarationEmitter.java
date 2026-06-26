package xyz.melodysky.runtime;

import java.util.stream.Collectors;

public final class RuntimeHelperDeclarationEmitter {
    public String emit(RuntimeHelperCatalog catalog) {
        StringBuilder builder = new StringBuilder();
        for (RuntimeHelper helper : catalog.helpers()) {
            builder.append("declare ")
                    .append(helper.llvmReturnType())
                    .append(" @")
                    .append(helper.llvmSymbol())
                    .append('(')
                    .append(String.join(", ", helper.llvmParameterTypes()))
                    .append(") ; ")
                    .append(helper.name())
                    .append('\n');
        }
        return builder.toString();
    }
}
