package com.agentos.reasoning.bdi;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlanLibrary {
    private static final Logger log = LoggerFactory.getLogger(PlanLibrary.class);
    private final List<Plan> plans = new CopyOnWriteArrayList<>();

    public void add(Plan plan) { plans.add(plan); }
    public void addAll(Collection<Plan> p) { plans.addAll(p); }

    /**
     * Find plans relevant to a triggering event, filtered by context conditions
     * evaluated against the current belief base.
     */
    public List<Plan> relevant(Literal triggeringEvent, BeliefBase beliefs) {
        String eventStr = triggeringEvent.toString();
        return plans.stream()
            .filter(p -> matchesTrigger(p.triggeringEvent(), eventStr))
            .filter(p -> evaluateContext(p.context(), beliefs))
            .sorted(Comparator.comparingInt(Plan::priority).reversed())
            .collect(Collectors.toList());
    }

    /** Legacy method for backward compatibility (no belief-based context filtering) */
    public List<Plan> relevant(Literal triggeringEvent) {
        return relevant(triggeringEvent, null);
    }

    private boolean matchesTrigger(String triggerPattern, String actual) {
        String stripped = triggerPattern;
        if (stripped.startsWith("+!") || stripped.startsWith("-!")) stripped = stripped.substring(2);
        else if (stripped.startsWith("+") || stripped.startsWith("-")) stripped = stripped.substring(1);
        return stripped.equals(actual);
    }

    /**
     * Evaluate a plan's context condition against the belief base.
     * The context is a logical expression like "true", "belief1 & belief2", etc.
     * Currently supports: "true" (always), simple literal check.
     */
    private boolean evaluateContext(String context, BeliefBase beliefs) {
        if (context == null || context.isBlank() || "true".equalsIgnoreCase(context.trim())) {
            return true;
        }
        if (beliefs == null) return true;

        // Handle NOT: "!literal(...)" or "not literal(...)"
        String trimmed = context.trim();
        if (trimmed.startsWith("!")) {
            try {
                return !beliefs.holds(AslParser.parseLiteral(trimmed.substring(1)));
            } catch (Exception e) { return false; }
        }
        if (trimmed.startsWith("not ")) {
            try {
                return !beliefs.holds(AslParser.parseLiteral(trimmed.substring(4)));
            } catch (Exception e) { return false; }
        }

        // Handle AND: "literal1 & literal2"
        if (trimmed.contains("&")) {
            for (String part : trimmed.split("&")) {
                try {
                    if (!beliefs.holds(AslParser.parseLiteral(part.trim()))) return false;
                } catch (Exception e) { return false; }
            }
            return true;
        }

        // Handle OR: "literal1 | literal2"
        if (trimmed.contains("|")) {
            for (String part : trimmed.split("\\|")) {
                try {
                    if (beliefs.holds(AslParser.parseLiteral(part.trim()))) return true;
                } catch (Exception e) { /* continue */ }
            }
            return false;
        }

        // Simple literal
        try {
            return beliefs.holds(AslParser.parseLiteral(trimmed));
        } catch (Exception e) {
            log.warn("BDI: unparseable context '{}', treating as false", trimmed);
            return false;
        }
    }

    public void clear() { plans.clear(); }
    public int size() { return plans.size(); }
}
