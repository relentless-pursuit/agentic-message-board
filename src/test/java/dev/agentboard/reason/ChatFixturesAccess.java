package dev.agentboard.reason;

import org.springframework.ai.chat.model.ChatResponse;

/** Lets tests in other packages build chat response fixtures. */
public final class ChatFixturesAccess {
    private ChatFixturesAccess() {}
    public static ChatResponse answer(String text) { return ChatFixtures.answer(text); }
}
