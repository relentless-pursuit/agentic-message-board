package dev.agentboard.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny rule language used in agent files, for example
 * when: "severity >= high" or filter: "type = alert and tag = security".
 * Rules are checked when an agent is loaded, so a typo is reported instead of silently ignored.
 */
public final class Condition {

    public static final List<String> SEVERITIES = List.of("low", "medium", "high", "critical");
    private static final List<String> FIELDS = List.of("severity", "type", "tag", "agent");
    private static final Pattern CLAUSE = Pattern.compile("^\\s*([a-z]+)\\s*(>=|<=|!=|=|>|<)\\s*([A-Za-z0-9._-]+)\\s*$");

    private final String text;
    private final List<Clause> clauses;

    private Condition(String text, List<Clause> clauses) {
        this.text = text;
        this.clauses = clauses;
    }

    public static Condition always() {
        return new Condition("always", List.of());
    }

    public static Condition parse(String text) {
        if (text == null || text.isBlank()) {
            return always();
        }
        List<Clause> clauses = new ArrayList<>();
        for (String part : text.trim().split("(?i)\\s+and\\s+")) {
            Matcher m = CLAUSE.matcher(part);
            if (!m.matches()) {
                throw new IllegalArgumentException("cannot read rule '" + part.trim() + "' (expected for example: severity >= high)");
            }
            String field = m.group(1);
            String op = m.group(2);
            String value = m.group(3).toLowerCase(Locale.ROOT);
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("unknown field '" + field + "' in rule (use one of " + FIELDS + ")");
            }
            boolean ordering = op.contains("<") || op.contains(">");
            if (ordering && !field.equals("severity")) {
                throw new IllegalArgumentException("'" + op + "' only works with severity");
            }
            if (field.equals("severity") && !SEVERITIES.contains(value)) {
                throw new IllegalArgumentException("severity must be one of " + SEVERITIES);
            }
            clauses.add(new Clause(field, op, value));
        }
        return new Condition(text.trim(), List.copyOf(clauses));
    }

    /** Facts keys: severity, type, agent (strings) and tags (collection of strings). */
    public boolean test(Map<String, ?> facts) {
        for (Clause c : clauses) {
            if (!c.test(facts)) {
                return false;
            }
        }
        return true;
    }

    public String text() {
        return text;
    }

    private record Clause(String field, String op, String value) {
        boolean test(Map<String, ?> facts) {
            if (field.equals("tag")) {
                Object tags = facts.get("tags");
                boolean has = tags instanceof Collection<?> c && c.stream().anyMatch(t -> value.equalsIgnoreCase(String.valueOf(t)));
                return op.equals("=") == has;
            }
            String actual = String.valueOf(facts.get(field)).toLowerCase(Locale.ROOT);
            if (field.equals("severity") && !op.equals("=") && !op.equals("!=")) {
                int a = SEVERITIES.indexOf(actual);
                int b = SEVERITIES.indexOf(value);
                if (a < 0) return false;
                return switch (op) {
                    case ">=" -> a >= b;
                    case "<=" -> a <= b;
                    case ">" -> a > b;
                    default -> a < b;
                };
            }
            return op.equals("=") == actual.equals(value);
        }
    }
}
