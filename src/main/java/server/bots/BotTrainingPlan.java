package server.bots;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Training targets stop farming; they do not freeze XP or change player leveling mechanics. */
final class BotTrainingPlan {
    private static final Logger log = LoggerFactory.getLogger(BotTrainingPlan.class);

    /** How long a roster bot stands idle at its cap before it parks itself. */
    static final long PARK_AFTER_IDLE_MS = 10 * 60_000L;

    // Seams: persisting the config needs a DB pool and logging out needs a live BotManager.
    static java.util.function.BiConsumer<Integer, String> saveConfig =
            (id, config) -> BotConfigService.getInstance().save(id, config);
    static java.util.function.IntConsumer logout = id -> BotManager.getInstance().logoutManagedBot(id);

    private BotTrainingPlan() {}

    static boolean complete(BotEntry entry, Character bot) {
        BotPersonality p = entry.personality;
        return p != null && p.trainingLevelTarget() > 0 && bot.getLevel() >= p.trainingLevelTarget()
                && BotBuildManager.autoAdvanceTarget(entry, bot) == null;
    }

    static void stopIfComplete(BotEntry entry, Character bot) {
        if (complete(entry, bot) && (entry.grinding || BotAutopilotManager.isActive(entry))) {
            BotManager.getInstance().issueStop(entry);
        }
    }

    /**
     * Park a self owned roster bot that has stood idle at its training cap for {@link #PARK_AFTER_IDLE_MS}:
     * store rosterActive=false, which is what the owner did by hand (the boot roster restore skips it), then
     * log it out gracefully. A bot someone else controls, one without a roster role, or one already parked
     * or logging out is left alone. The owner unparks by setting rosterActive back to true.
     */
    static void parkIfIdleAtCap(BotEntry entry, Character bot, boolean atCap, long nowMs) {
        BotPersonality p = entry.personality;
        boolean eligible = atCap && p != null && p.rosterActive() && !p.rosterRole().isBlank()
                && entry.owner == bot && !entry.loggingOut;
        if (!eligible) {
            entry.capIdleSinceMs = 0L;
            return;
        }
        if (entry.capIdleSinceMs == 0L) {
            entry.capIdleSinceMs = nowMs;
            return;
        }
        if (nowMs - entry.capIdleSinceMs < PARK_AFTER_IDLE_MS) {
            return;
        }
        BotPersonality parked = p.withRosterActive(false);
        try {
            saveConfig.accept(bot.getId(), parked.serialize());
        } catch (RuntimeException e) {
            entry.capIdleSinceMs = nowMs; // try again after another idle window
            log.warn("bot-park: could not save rosterActive=false for {}", bot.getName(), e);
            return;
        }
        entry.personality = parked;
        log.info("bot-park: {} idle at training cap {}; parked (rosterActive=false), logging out",
                bot.getName(), p.trainingLevelTarget());
        logout.accept(bot.getId());
    }
}
