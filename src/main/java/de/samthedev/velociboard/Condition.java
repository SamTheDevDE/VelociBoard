package de.samthedev.velociboard;

import com.velocitypowered.api.proxy.Player;
import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;

record Condition(String placeholder, String operator, String expected, BigDecimal number) {
    private static final Pattern EXPRESSION = Pattern.compile(
            "^%([a-z][a-z0-9_]*)%\\s*(==|!=|>=|<=|>|<)\\s*(.+)$");

    static Condition parse(String expression, String location) {
        Matcher matcher = EXPRESSION.matcher(expression.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(location + ": expected '%placeholder% operator value'");
        }
        String right = matcher.group(3).trim();
        if (right.length() >= 2 && ((right.startsWith("\"") && right.endsWith("\""))
                || (right.startsWith("'") && right.endsWith("'")))) {
            right = right.substring(1, right.length() - 1);
        }
        String operator = matcher.group(2);
        BigDecimal number = numeric(right);
        if (!operator.equals("==") && !operator.equals("!=") && number == null) {
            throw new IllegalArgumentException(location + ": numeric comparison needs a number on the right");
        }
        return new Condition(matcher.group(1), operator, right, number);
    }

    boolean matches(Player player, PlaceholderRegistry placeholders, Map<String, Component> resolved) {
        String actual = placeholders.resolveText(player, placeholder, resolved);
        if (actual == null) {
            return false;
        }
        BigDecimal actualNumber = numeric(actual);
        int comparison;
        if (number != null && actualNumber != null) {
            comparison = actualNumber.compareTo(number);
        } else {
            comparison = actual.compareTo(expected);
        }
        return switch (operator) {
            case "==" -> comparison == 0;
            case "!=" -> comparison != 0;
            case ">" -> actualNumber != null && comparison > 0;
            case "<" -> actualNumber != null && comparison < 0;
            case ">=" -> actualNumber != null && comparison >= 0;
            case "<=" -> actualNumber != null && comparison <= 0;
            default -> false;
        };
    }

    private static BigDecimal numeric(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
