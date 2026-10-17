package dev.agentboard.reason;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.UUID;

/** Spring AI chat responses as OpenAI would return them, for tests that must not call OpenAI. */
final class ChatFixtures {
    private ChatFixtures() {}

    static ChatResponse answer(String text) { return response(AssistantMessage.builder().content(text).build(), "STOP"); }

    static ChatResponse toolCalls(List<AssistantMessage.ToolCall> calls) {
        return response(AssistantMessage.builder().content("").toolCalls(calls).build(), "TOOL_CALLS");
    }

    static AssistantMessage.ToolCall call(String id, String name, String arguments) { return new AssistantMessage.ToolCall(id, "function", name, arguments); }

    static ChatResponse response(AssistantMessage message, String finishReason) {
        var metadata = ChatResponseMetadata.builder().id("resp_fixture_" + UUID.randomUUID()).model("fixture").usage(new DefaultUsage(10, 10)).build();
        return new ChatResponse(List.of(new Generation(message, ChatGenerationMetadata.builder().finishReason(finishReason).build())), metadata);
    }
}
