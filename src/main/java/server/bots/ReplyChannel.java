package server.bots;

public enum ReplyChannel {
    MAP,
    PARTY,
    WHISPER,
    /** The dashboard's chat box (/api/chat): replies land in BotDashboardChat, nothing is said in game. */
    DASHBOARD
}
