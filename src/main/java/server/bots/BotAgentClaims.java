package server.bots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bots named in a live agent claim (tools/agents/agents.py: an agent watching or fixing them). The claims folder on
 * the Mac is mounted read only into the server at {@code agents/claims}; one JSON file per claim with
 * {@code subjects} such as {@code bot:255} and {@code expires} in epoch seconds. Fleet ops leaves a claimed bot's
 * auto rescue alone, so a return scroll doesn't pull a bot off the spot an agent is studying. No folder (a server
 * run without the mount) means no claims.
 */
final class BotAgentClaims {
    private static final Logger log = LoggerFactory.getLogger(BotAgentClaims.class);
    static Path dir = Path.of("agents", "claims");

    private BotAgentClaims() {}

    /** Ids of the bots named in a claim that hasn't expired at {@code nowMs}. A file that won't parse is skipped. */
    static Set<Integer> claimedBots(long nowMs) {
        Set<Integer> out = new HashSet<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> claim = (Map<String, Object>) BotJson.parse(Files.readString(file));
                    if (!(claim.get("expires") instanceof Double expires) || expires * 1000 <= nowMs
                            || !(claim.get("subjects") instanceof List<?> subjects)) {
                        continue;
                    }
                    for (Object s : subjects) {
                        if (s instanceof String subject && subject.startsWith("bot:")) {
                            out.add(Integer.parseInt(subject.substring(4)));
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    log.debug("agent claims: skipped {}", file, e);
                }
            }
        } catch (IOException e) {
            log.debug("agent claims: can't read {}", dir, e);
        }
        return out;
    }
}
