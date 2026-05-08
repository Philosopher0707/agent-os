package com.agentos.reasoning.bdi;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class BeliefBase {
    private final Set<Literal> beliefs = new CopyOnWriteArraySet<>();

    public void add(Literal belief) {
        Literal opposite = new Literal(belief.predicate(), belief.terms(), !belief.negated());
        beliefs.remove(opposite);
        beliefs.add(belief);
    }
    public void remove(Literal belief) { beliefs.remove(belief); }
    public boolean holds(Literal literal) {
        if (literal.negated()) return !beliefs.contains(new Literal(literal.predicate(), literal.terms(), false));
        return beliefs.contains(literal);
    }
    public Set<Literal> all() { return Set.copyOf(beliefs); }
    public void clear() { beliefs.clear(); }
    public int size() { return beliefs.size(); }
}
