package com.agentos.reasoning.bdi;

import java.util.*;
import java.util.regex.*;

public final class AslParser {

    public static List<Plan> parse(String source) {
        List<Plan> plans = new ArrayList<>();
        String[] lines = source.split("\n");
        StringBuilder currentPlan = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            // Check if this line starts a new plan (triggering event pattern)
            if (trimmed.matches("[+-]!?\\w+\\([^)]*\\)\\s*:.+")) {
                flushPlan(currentPlan.toString()).ifPresent(plans::add);
                currentPlan = new StringBuilder(trimmed);
            } else if (trimmed.endsWith(".")) {
                currentPlan.append(" ").append(trimmed);
                flushPlan(currentPlan.toString()).ifPresent(plans::add);
                currentPlan = new StringBuilder();
            } else {
                currentPlan.append(" ").append(trimmed);
            }
        }
        flushPlan(currentPlan.toString()).ifPresent(plans::add);
        return plans;
    }

    private static Optional<Plan> flushPlan(String line) {
        line = line.trim();
        if (line.isEmpty()) return Optional.empty();
        Matcher m = Pattern.compile(
            "([+-]!?\\w+\\([^)]*\\))\\s*:\\s*(.+?)\\s*<-\\s*(.+?)\\.\\s*$").matcher(line);
        if (m.find()) {
            return Optional.of(new Plan(m.group(1).trim(), m.group(2).trim(),
                parseBody(m.group(3).trim()), 0));
        }
        return Optional.empty();
    }
    static List<String> parseBody(String body) {
        return Arrays.stream(body.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
    public static Literal parseLiteral(String s) {
        s = s.trim();
        boolean negated = s.startsWith("not ");
        if (negated) s = s.substring(4);
        Matcher m = Pattern.compile("(\\w+)\\(([^)]*)\\)").matcher(s);
        if (!m.matches()) throw new IllegalArgumentException("bad literal: " + s);
        String[] terms = m.group(2).isEmpty() ? new String[0] : m.group(2).split(",");
        for (int i = 0; i < terms.length; i++) terms[i] = terms[i].trim();
        return new Literal(m.group(1), List.of(terms), negated);
    }
}
