package com.example.genwriter.agent.tool;

public final class SessionContextHolder {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_SPAN_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_AGENT_NAME = new ThreadLocal<>();

    private SessionContextHolder() {}

    public static void set(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static void set(String sessionId, String currentSpanId, String currentAgentName) {
        SESSION_ID.set(sessionId);
        CURRENT_SPAN_ID.set(currentSpanId);
        CURRENT_AGENT_NAME.set(currentAgentName);
    }

    public static String get() {
        return SESSION_ID.get();
    }

    public static String getCurrentSpanId() {
        return CURRENT_SPAN_ID.get();
    }

    public static String getCurrentAgentName() {
        return CURRENT_AGENT_NAME.get();
    }

    public static ContextSnapshot snapshot() {
        return new ContextSnapshot(get(), getCurrentSpanId(), getCurrentAgentName());
    }

    public static void restore(ContextSnapshot snapshot) {
        if (snapshot == null) {
            clear();
            return;
        }
        SESSION_ID.set(snapshot.sessionId());
        CURRENT_SPAN_ID.set(snapshot.currentSpanId());
        CURRENT_AGENT_NAME.set(snapshot.currentAgentName());
    }

    public static void clear() {
        SESSION_ID.remove();
        CURRENT_SPAN_ID.remove();
        CURRENT_AGENT_NAME.remove();
    }

    public record ContextSnapshot(String sessionId, String currentSpanId, String currentAgentName) {}
}
