package server.bots;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotAgentClaimsTest {
    private final Path previous = BotAgentClaims.dir;

    @AfterEach
    void restore() {
        BotAgentClaims.dir = previous;
    }

    @Test
    void liveClaimsNameTheirBotsAndExpiredOrBrokenOnesNothing(@TempDir Path dir) throws IOException {
        BotAgentClaims.dir = dir;
        long now = 1_792_500_000_000L;
        // The shape tools/agents/agents.py writes (json.dump, indent 1).
        Files.writeString(dir.resolve("10201304-528ca7.json"), """
                {
                 "id": "10201304-528ca7",
                 "session": "cloud-claude-oct20",
                 "kind": "watch",
                 "subjects": [
                  "bot:255",
                  "map:800020130"
                 ],
                 "intent": "watching the Buddha freeze",
                 "issue": null,
                 "started": "2026-10-20 13:04",
                 "expires": 1792507200.25
                }""");
        Files.writeString(dir.resolve("old.json"),
                "{\"subjects\": [\"bot:254\"], \"expires\": 1792400000.0}");
        Files.writeString(dir.resolve("area.json"),
                "{\"subjects\": [\"area:combat\", \"file:BotFleetOps\"], \"expires\": 1792600000.0}");
        Files.writeString(dir.resolve("broken.json"), "{\"subjects\": [\"bot:256\"");
        assertEquals(Set.of(255), BotAgentClaims.claimedBots(now));
        assertEquals(Set.of(), BotAgentClaims.claimedBots(1_792_507_200_250L), "expired at its own second");
    }

    @Test
    void noClaimsFolderMeansNoClaims(@TempDir Path dir) {
        BotAgentClaims.dir = dir.resolve("missing");
        assertEquals(Set.of(), BotAgentClaims.claimedBots(0L));
    }
}
