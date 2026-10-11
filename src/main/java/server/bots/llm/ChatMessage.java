package server.bots.llm;

/** One message of a chat request: role is "system", "user" or "assistant". */
public record ChatMessage(String role, String content) {}
