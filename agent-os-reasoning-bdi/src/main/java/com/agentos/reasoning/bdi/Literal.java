package com.agentos.reasoning.bdi;

import java.util.List;
import java.util.Objects;

public record Literal(String predicate, List<String> terms, boolean negated) {
    public Literal {
        terms = List.copyOf(terms);
    }
    public static Literal of(String predicate, String... terms) {
        return new Literal(predicate, List.of(terms), false);
    }
    public static Literal not(String predicate, String... terms) {
        return new Literal(predicate, List.of(terms), true);
    }
    public String functor() { return predicate + "/" + terms.size(); }
    @Override
    public String toString() {
        String s = predicate + "(" + String.join(",", terms) + ")";
        return negated ? "not " + s : s;
    }
}
