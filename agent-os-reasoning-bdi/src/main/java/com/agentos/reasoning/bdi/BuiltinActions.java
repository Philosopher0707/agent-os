package com.agentos.reasoning.bdi;

import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

class BuiltinActions {
    private static final Logger log = LoggerFactory.getLogger(BuiltinActions.class);
    private final AgentContext ctx;
    BuiltinActions(AgentContext ctx) { this.ctx = ctx; }

    void execute(String action) {
        action = action.trim();
        if (action.startsWith(".send(")) executeSend(action);
        else if (action.startsWith(".println(")) System.out.println("[BDI] " + unquote(extractArg(action, 1)));
        else if (action.startsWith(".register_service(")) executeRegisterService(action);
    }

    private void executeSend(String action) {
        String targetName = extractArg(action, 1).trim();
        String perfStr = extractArg(action, 2).trim().toUpperCase();
        String content = unquote(extractArg(action, 3));

        // Resolve target via ServiceDirectory or Registry
        AgentId target = resolveAgent(targetName);

        ctx.send(ACLMessage.builder()
            .performative(ACLMessage.Performative.valueOf(perfStr))
            .sender(ctx.self()).receiver(target).content(content).build());
    }

    private AgentId resolveAgent(String name) {
        // 1. Try ServiceDirectory by service type
        var services = ctx.services();
        if (services != null) {
            var providers = services.search(name);
            if (!providers.isEmpty()) return providers.get(0).provider();
        }
        // 2. Try AgentRegistry by name
        var registry = ctx.registry();
        if (registry != null) {
            var info = registry.lookup(name);
            if (info.isPresent()) return info.get().agentId();
        }
        // 3. Fallback: create AgentId by name
        log.warn("BDI BuiltinActions: agent '{}' not found in directory or registry, using literal name", name);
        return AgentId.of(name);
    }

    private void executeRegisterService(String action) {
        String svcType = unquote(extractArg(action, 1));
        ctx.services().register(new com.agentos.kernel.directory.ServiceDescription(ctx.self(), svcType, Map.of()));
    }

    private static String extractArg(String action, int index) {
        String inner = action.substring(action.indexOf('(') + 1, action.lastIndexOf(')'));
        String[] parts = inner.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        return index <= parts.length ? parts[index - 1].trim() : "";
    }
    private static String unquote(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }
}
