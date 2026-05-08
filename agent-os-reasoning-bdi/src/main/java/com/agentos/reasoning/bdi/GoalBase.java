package com.agentos.reasoning.bdi;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class GoalBase {
    private final Deque<Literal> achievementGoals = new ConcurrentLinkedDeque<>();
    private final Set<Literal> maintenanceGoals = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void addAchievementGoal(Literal goal) { achievementGoals.addLast(goal); }
    public void addMaintenanceGoal(Literal goal) { maintenanceGoals.add(goal); }
    public Optional<Literal> pollAchievementGoal() { return Optional.ofNullable(achievementGoals.pollFirst()); }
    public boolean removeGoal(Literal goal) { return achievementGoals.remove(goal); }
    public Set<Literal> pendingAchievementGoals() { return new HashSet<>(achievementGoals); }
    public Set<Literal> pendingMaintenanceGoals() { return Set.copyOf(maintenanceGoals); }
    public boolean removeMaintenanceGoal(Literal goal) { return maintenanceGoals.remove(goal); }
    public void clear() { achievementGoals.clear(); maintenanceGoals.clear(); }
}
