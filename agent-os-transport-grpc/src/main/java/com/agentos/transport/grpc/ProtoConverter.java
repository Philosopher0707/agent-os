package com.agentos.transport.grpc;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.messaging.ACLMessage;
import java.util.UUID;
import java.util.stream.Collectors;

final class ProtoConverter {

    static AclMessageProto toProto(ACLMessage msg) {
        return AclMessageProto.newBuilder()
            .setPerformative(msg.performative().name())
            .setSenderName(msg.sender().name())
            .setSenderId(msg.sender().id().toString())
            .addAllReceiverNames(msg.receivers().stream().map(AgentId::name).toList())
            .addAllReceiverIds(msg.receivers().stream().map(a -> a.id().toString()).toList())
            .setReplyToName(msg.replyTo() != null ? msg.replyTo().name() : "")
            .setReplyToId(msg.replyTo() != null ? msg.replyTo().id().toString() : "")
            .setConversationId(msg.conversationId() != null ? msg.conversationId() : "")
            .setProtocol(msg.protocol() != null ? msg.protocol() : "")
            .setLanguage(msg.language() != null ? msg.language() : "json")
            .setEncoding(msg.encoding() != null ? msg.encoding() : "UTF-8")
            .setContent(msg.content() != null ? msg.content() : "")
            .setTimestampEpochMillis(msg.timestamp().toEpochMilli())
            .build();
    }

    static ACLMessage fromProto(AclMessageProto proto) {
        var builder = ACLMessage.builder()
            .performative(ACLMessage.Performative.valueOf(proto.getPerformative()))
            .sender(buildAgentId(proto.getSenderName(), proto.getSenderId()))
            .conversationId(proto.getConversationId())
            .protocol(proto.getProtocol())
            .language(proto.getLanguage())
            .encoding(proto.getEncoding())
            .content(proto.getContent());

        // Preserve receiver UUIDs
        int receiverCount = proto.getReceiverNamesCount();
        for (int i = 0; i < receiverCount; i++) {
            String name = proto.getReceiverNames(i);
            String idStr = i < proto.getReceiverIdsCount() ? proto.getReceiverIds(i) : "";
            builder.receiver(buildAgentId(name, idStr));
        }

        // Preserve replyTo UUID
        if (!proto.getReplyToName().isBlank()) {
            builder.replyTo(buildAgentId(proto.getReplyToName(), proto.getReplyToId()));
        }

        return builder.build();
    }

    private static AgentId buildAgentId(String name, String idStr) {
        if (idStr != null && !idStr.isBlank()) {
            try {
                return new AgentId(name, UUID.fromString(idStr));
            } catch (IllegalArgumentException e) {
                // fall through
            }
        }
        return AgentId.of(name);
    }
}
