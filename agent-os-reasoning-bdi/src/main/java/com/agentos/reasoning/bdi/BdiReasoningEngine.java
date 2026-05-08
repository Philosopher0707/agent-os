package com.agentos.reasoning.bdi;

import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import com.agentos.kernel.reasoning.ReasoningEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class BdiReasoningEngine implements ReasoningEngine {
    private static final Logger log = LoggerFactory.getLogger(BdiReasoningEngine.class);
    private final Map<Agent, BeliefBase> beliefBases = new ConcurrentHashMap<>();
    private final Map<Agent, GoalBase> goalBases = new ConcurrentHashMap<>();
    private final Map<Agent, PlanLibrary> planLibraries = new ConcurrentHashMap<>();
    private final Map<Agent, IntentionStack> intentions = new ConcurrentHashMap<>();
    private final Map<Agent, BuiltinActions> builtins = new ConcurrentHashMap<>();
    private final Map<Agent, AgentContext> contexts = new ConcurrentHashMap<>();
    private final Set<Agent> bdiAgents = ConcurrentHashMap.newKeySet();
    private final Set<Agent> processing = ConcurrentHashMap.newKeySet();
    private final Map<Agent, Literal> activeGoal = new ConcurrentHashMap<>(); // tracks goal for current plan

    @Override public String name() { return "bdi"; }
    @Override public boolean supports(Agent agent) { return bdiAgents.contains(agent); }
    @Override public void install(Agent agent) { bdiAgents.add(agent); }

    @Override
    public void start(Agent agent, AgentContext ctx) {
        contexts.put(agent, ctx);
        beliefBases.put(agent, new BeliefBase());
        goalBases.put(agent, new GoalBase());
        planLibraries.put(agent, new PlanLibrary());
        intentions.put(agent, new IntentionStack());
        builtins.put(agent, new BuiltinActions(ctx));
    }

    public BeliefBase beliefs(Agent agent) { return beliefBases.get(agent); }
    public GoalBase goals(Agent agent) { return goalBases.get(agent); }
    public PlanLibrary library(Agent agent) { return planLibraries.get(agent); }
    public IntentionStack intentionStack(Agent agent) { return intentions.get(agent); }

    @Override
    public void onMessage(Agent agent, ACLMessage msg) {
        BeliefBase bb = beliefBases.get(agent);
        if (bb == null) { agent.onMessage(msg); return; }

        var perf = msg.performative();
        String content = msg.content();

        bb.add(Literal.of("msg_from", msg.sender().name()));
        bb.add(Literal.of("msg_type", perf.name()));

        if (content != null) {
            if (content.contains("\"service\"") || content.contains("\"alert\"")) {
                String svc = extractJsonVal(content, "service");
                if (svc == null) {
                    svc = extractJsonVal(content.replace("\\\"", "\""), "service");
                }
                String status = extractJsonVal(content, "status");
                String alert = extractJsonVal(content, "alert");

                if (svc != null && status != null) {
                    bb.add(Literal.of("service_status", svc, status));
                    if ("DEGRADED".equals(status) || "DOWN".equals(status)) {
                        GoalBase gb = goalBases.get(agent);
                        if (gb != null) gb.addAchievementGoal(Literal.of("service_status", svc, status));
                    }
                }

                if (svc != null && alert != null && alert.contains("HIGH_CPU")) {
                    bb.add(Literal.of("alert", "high_cpu", svc));
                    GoalBase gb = goalBases.get(agent);
                    if (gb != null) gb.addAchievementGoal(Literal.of("alert", "high_cpu", svc));
                }
            }

            if (content.contains("SCALE_DONE") || (content.contains("restart") && content.contains("ok"))) {
                String svc = extractJsonVal(content, "service");
                if (svc != null) bb.add(Literal.of("recovery", "complete", svc));
            }
        }

        if (perf == ACLMessage.Performative.PROPOSE) {
            bb.add(Literal.of("proposal", msg.sender().name()));
            GoalBase gb = goalBases.get(agent);
            if (gb != null) gb.addAchievementGoal(Literal.of("msg_type", "PROPOSE"));
        }

        if (perf == ACLMessage.Performative.REFUSE) {
            log.warn("BDI: proposal refused by {}", msg.sender().name());
            bb.add(Literal.of("proposal_refused", msg.sender().name()));
        }

        // Process intentions with re-entry guard to prevent recursive loops
        if (processing.add(agent)) {
            try {
                processIntentions(agent);
            } finally {
                processing.remove(agent);
            }
        }
    }

    @Override
    public void step(Agent agent) {
        // Process maintenance goals on each tick
        processMaintenanceGoals(agent);
        if (processing.add(agent)) {
            try {
                processIntentions(agent);
            } finally {
                processing.remove(agent);
            }
        }
    }

    /** Process the intention stack — continue executing the current intention */
    private void processIntentions(Agent agent) {
        IntentionStack stack = intentions.get(agent);
        if (stack == null) return;

        // If there's an active intention, continue executing it
        if (!stack.isEmpty()) {
            Plan currentIntention = stack.current();
            if (currentIntention != null) {
                executePlan(agent, currentIntention);
                return;
            }
        }

        // No active intention — select a new one from pending goals
        selectNewIntention(agent);
    }

    /** Select a new intention from pending goals */
    private void selectNewIntention(Agent agent) {
        GoalBase gb = goalBases.get(agent);
        PlanLibrary lib = planLibraries.get(agent);
        BeliefBase bb = beliefBases.get(agent);
        IntentionStack stack = intentions.get(agent);
        if (gb == null || lib == null || stack == null) return;

        var pending = new ArrayList<>(gb.pendingAchievementGoals());
        for (var goal : pending) {
            var relevant = lib.relevant(goal, bb);
            if (relevant.isEmpty()) continue;

            gb.removeGoal(goal);
            activeGoal.put(agent, goal);  // track which goal triggered this plan
            Plan selected = relevant.get(0); // highest priority
            log.info("BDI: selected intention for goal {}: plan {}", goal, selected.triggeringEvent());
            stack.push(selected);
            executePlan(agent, selected);
            return;
        }
    }

    /** Execute a plan's body actions */
    private void executePlan(Agent agent, Plan plan) {
        BuiltinActions actions = builtins.get(agent);
        IntentionStack stack = intentions.get(agent);
        if (actions == null || stack == null) return;

        log.info("BDI: executing plan {} with {} actions", plan.triggeringEvent(), plan.body().size());
        boolean success = true;
        for (String action : plan.body()) {
            try {
                actions.execute(action);
            } catch (Exception e) {
                log.warn("BDI: plan action '{}' failed: {}", action, e.getMessage());
                success = false;
                break;
            }
        }

        // Pop the completed (or failed) intention
        stack.pop();

        if (!success) {
            var lib = planLibraries.get(agent);
            var bb = beliefBases.get(agent);
            GoalBase gb = goalBases.get(agent);
            // Re-queue the goal so it can be retried with alternative plans on next cycle
            Literal goal = activeGoal.remove(agent);
            if (goal != null && gb != null) {
                gb.addAchievementGoal(goal);
                log.info("BDI: plan {} failed, goal {} re-queued for alternative plan retry",
                    plan.triggeringEvent(), goal);
            } else {
                log.warn("BDI: plan {} failed, no goal tracked for retry", plan.triggeringEvent());
            }
        } else {
            activeGoal.remove(agent);  // plan succeeded, clear tracking
        }
    }

    /** Process maintenance goals — check if conditions still hold */
    private void processMaintenanceGoals(Agent agent) {
        GoalBase gb = goalBases.get(agent);
        BeliefBase bb = beliefBases.get(agent);
        PlanLibrary lib = planLibraries.get(agent);
        if (gb == null || bb == null || lib == null) return;

        for (var goal : gb.pendingMaintenanceGoals()) {
            // Check if the maintenance condition is violated
            if (!bb.holds(goal)) {
                log.info("BDI: maintenance goal {} violated, finding plan", goal);
                var relevant = lib.relevant(goal, bb);
                if (!relevant.isEmpty()) {
                    Plan plan = relevant.get(0);
                    executePlan(agent, plan);
                }
            }
        }
    }

    @Override
    public void stop(Agent agent) {
        bdiAgents.remove(agent);
        beliefBases.remove(agent); goalBases.remove(agent);
        planLibraries.remove(agent); intentions.remove(agent);
        builtins.remove(agent); contexts.remove(agent);
    }

    private static String extractJsonVal(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) { search = "\"" + key + "\":"; start = json.indexOf(search); }
        if (start < 0) return null;
        start += search.length();
        char c = json.charAt(start);
        if (c == '"') { start++; int end = json.indexOf("\"", start); return end > start ? json.substring(start, end) : null; }
        int end = json.indexOf(",", start);
        if (end < 0) end = json.indexOf("}", start);
        if (end < 0) return null;
        String val = json.substring(start, end).trim();
        if (val.endsWith("\"")) val = val.substring(0, val.length() - 1);
        return val;
    }
}
