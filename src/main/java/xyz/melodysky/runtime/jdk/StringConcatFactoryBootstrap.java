package xyz.melodysky.runtime.jdk;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Handle;

public final class StringConcatFactoryBootstrap {
    private static final char ARGUMENT_PLACEHOLDER = '\u0001';
    private static final char CONSTANT_PLACEHOLDER = '\u0002';

    public StringConcatBootstrapPlan parse(String invokedName, Handle bootstrapMethod, Object[] bootstrapArguments) {
        if (!bootstrapMethod.getOwner().equals("java/lang/invoke/StringConcatFactory")) {
            return new StringConcatBootstrapPlan(false, false, List.of(), "not StringConcatFactory");
        }
        if (bootstrapMethod.getName().equals("makeConcat")) {
            return new StringConcatBootstrapPlan(true, true, List.of(), "makeConcat");
        }
        if (!bootstrapMethod.getName().equals("makeConcatWithConstants")) {
            return new StringConcatBootstrapPlan(true, false, List.of(), "unsupported StringConcatFactory method " + bootstrapMethod.getName());
        }
        if (bootstrapArguments.length == 0 || !(bootstrapArguments[0] instanceof String recipe)) {
            return new StringConcatBootstrapPlan(true, false, List.of(), "makeConcatWithConstants missing recipe");
        }
        return parseRecipe(recipe, java.util.Arrays.copyOfRange(bootstrapArguments, 1, bootstrapArguments.length));
    }

    private StringConcatBootstrapPlan parseRecipe(String recipe, Object[] constants) {
        ArrayList<StringConcatToken> tokens = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int operandIndex = 0;
        int constantIndex = 0;
        for (int index = 0; index < recipe.length(); index++) {
            char ch = recipe.charAt(index);
            if (ch == ARGUMENT_PLACEHOLDER || ch == CONSTANT_PLACEHOLDER) {
                flushLiteral(tokens, literal);
            }
            if (ch == ARGUMENT_PLACEHOLDER) {
                tokens.add(StringConcatToken.operand(operandIndex++));
            } else if (ch == CONSTANT_PLACEHOLDER) {
                if (constantIndex >= constants.length) {
                    return new StringConcatBootstrapPlan(true, false, List.of(), "recipe references missing static constant");
                }
                Object constant = constants[constantIndex++];
                if (!isSupportedRecipeConstant(constant)) {
                    return new StringConcatBootstrapPlan(true, false, List.of(), "unsupported recipe constant " + constant);
                }
                tokens.add(StringConcatToken.constant(String.valueOf(constant)));
            } else {
                literal.append(ch);
            }
        }
        flushLiteral(tokens, literal);
        return new StringConcatBootstrapPlan(true, true, tokens, "makeConcatWithConstants");
    }

    private void flushLiteral(List<StringConcatToken> tokens, StringBuilder literal) {
        if (!literal.isEmpty()) {
            tokens.add(StringConcatToken.constant(literal.toString()));
            literal.setLength(0);
        }
    }

    private boolean isSupportedRecipeConstant(Object constant) {
        return constant instanceof String
                || constant instanceof Integer
                || constant instanceof Long
                || constant instanceof Float
                || constant instanceof Double;
    }
}
