package dev.agentboard.reason;

import java.util.List;

/**
 * The reasoning layer's answer. Code, not OpenAI, decides what to do with it.
 *
 * @param post whether this is worth telling people
 * @param engine "openai" or "template"
 * @param note short explanation for the run log
 */
public record Reasoning(boolean post, String title, String body, String severity, List<String> tags, String engine, String note) {

    public Reasoning withNote(String newNote) {
        return new Reasoning(post, title, body, severity, tags, engine, newNote);
    }
}
