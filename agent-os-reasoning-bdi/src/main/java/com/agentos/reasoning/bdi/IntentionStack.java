package com.agentos.reasoning.bdi;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

class IntentionStack {
    private final Deque<Plan> stack = new ConcurrentLinkedDeque<>();
    void push(Plan plan) { stack.addLast(plan); }
    Plan current() { return stack.peekLast(); }
    void pop() { stack.pollLast(); }
    boolean isEmpty() { return stack.isEmpty(); }
    void clear() { stack.clear(); }
}
