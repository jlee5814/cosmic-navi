package server.bots.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Every LLM call the bots make, measured: wall clock latency, outcome, and the token counts the server
 * reports. The newest {@link #CAPACITY} calls stay in memory for {@code navi llm}; each call is also
 * appended to {@link #store} as one TSV row so a benchmark can read the whole history offline.
 *
 * <p>Latency is end to end (request sent to body received, prefill included), since that is what a
 * player waits for. Timeouts count at the latency they reached, so the tail is never hidden.
 */
public final class LlmStats {
    private static final Logger log = LoggerFactory.getLogger(LlmStats.class);

    enum Outcome { OK, HTTP_ERROR, TIMEOUT, FAILED }

    /** Token counts are -1 when the server did not report them. */
    record Call(long atMs, String kind, Outcome outcome, long latencyMs,
                int promptTokens, int completionTokens, int cachedTokens) {
    }

    record Usage(int promptTokens, int completionTokens, int cachedTokens) {
        static final Usage NONE = new Usage(-1, -1, -1);
    }

    static final int CAPACITY = 2048;
    static final long REPORT_WINDOW_MS = 60 * 60_000L;
    static final String TSV_HEADER =
            "at_ms\tbackend\tmodel\tkind\toutcome\tlatency_ms\tprompt_tokens\tcompletion_tokens\tcached_tokens";

    static volatile Path store = Path.of("cache", "llm-stats", "calls.tsv");
    static volatile boolean persist = true;

    private static final ArrayDeque<Call> calls = new ArrayDeque<>(CAPACITY);

    private LlmStats() {}

    /** Records one finished call. Never throws: a failed write only costs the TSV row. */
    static void record(String kind, Outcome outcome, long latencyMs, Usage usage) {
        Usage u = usage == null ? Usage.NONE : usage;
        Call call = new Call(System.currentTimeMillis(), kind, outcome, Math.max(0, latencyMs),
                u.promptTokens(), u.completionTokens(), u.cachedTokens());
        synchronized (calls) {
            if (calls.size() == CAPACITY) calls.removeFirst();
            calls.addLast(call);
        }
        if (persist) append(call);
    }

    static List<Call> snapshot() {
        synchronized (calls) {
            return new ArrayList<>(calls);
        }
    }

    static void clear() {
        synchronized (calls) {
            calls.clear();
        }
    }

    // ---- usage parsing -------------------------------------------------------------------------------

    /**
     * OpenAI compatible usage block. SGLang reports prefix cache reuse as
     * {@code usage.prompt_tokens_details.cached_tokens} when started with {@code --enable-cache-report};
     * without it the cached count is -1.
     */
    static Usage parseOpenAiUsage(String json) {
        if (json == null) return Usage.NONE;
        int at = json.indexOf("\"usage\"");
        if (at < 0) return Usage.NONE;
        return new Usage(intField(json, "prompt_tokens", at), intField(json, "completion_tokens", at),
                intField(json, "cached_tokens", at));
    }

    /** Ollama /api/generate: prompt_eval_count and eval_count; it has no cache report. */
    static Usage parseOllamaUsage(String json) {
        if (json == null) return Usage.NONE;
        return new Usage(intField(json, "prompt_eval_count", 0), intField(json, "eval_count", 0), -1);
    }

    /** The integer after {@code "key":} at or past {@code from}, or -1 when absent or not a number. */
    static int intField(String json, String key, int from) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle, from);
        if (i < 0) return -1;
        i += needle.length();
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == ':')) i++;
        int start = i;
        while (i < json.length() && Character.isDigit(json.charAt(i))) i++;
        if (i == start) return -1;
        try {
            return Integer.parseInt(json.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---- summary ---------------------------------------------------------------------------------------

    record Summary(int calls, int ok, int timeouts, int errors, long p50, long p95, long p99, long max,
                   double outputTokPerSec, double avgPromptTokens, double avgCompletionTokens,
                   double cacheHitRatio) {
    }

    /** Calls newer than {@code windowMs}; null when there are none. Ratios are NaN when the server
     *  reported nothing to compute them from. */
    static Summary summarize(List<Call> all, long nowMs, long windowMs) {
        List<Call> in = all.stream().filter(c -> nowMs - c.atMs() <= windowMs).toList();
        if (in.isEmpty()) return null;
        long[] lat = in.stream().mapToLong(Call::latencyMs).sorted().toArray();
        int ok = 0, timeouts = 0, errors = 0;
        long okLatency = 0, completion = 0, promptSum = 0, completionSum = 0, cached = 0, cachedPrompt = 0;
        int promptN = 0, completionN = 0;
        for (Call c : in) {
            switch (c.outcome()) {
                case OK -> ok++;
                case TIMEOUT -> timeouts++;
                default -> errors++;
            }
            if (c.outcome() != Outcome.OK) continue;
            if (c.completionTokens() >= 0) {
                completion += c.completionTokens();
                okLatency += c.latencyMs();
                completionSum += c.completionTokens();
                completionN++;
            }
            if (c.promptTokens() >= 0) {
                promptSum += c.promptTokens();
                promptN++;
                if (c.cachedTokens() >= 0) {
                    cached += c.cachedTokens();
                    cachedPrompt += c.promptTokens();
                }
            }
        }
        return new Summary(in.size(), ok, timeouts, errors,
                percentile(lat, 50), percentile(lat, 95), percentile(lat, 99), lat[lat.length - 1],
                okLatency > 0 ? completion * 1000.0 / okLatency : Double.NaN,
                promptN > 0 ? (double) promptSum / promptN : Double.NaN,
                completionN > 0 ? (double) completionSum / completionN : Double.NaN,
                cachedPrompt > 0 ? (double) cached / cachedPrompt : Double.NaN);
    }

    /** Nearest rank percentile of an ascending array. */
    static long percentile(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1];
    }

    /** Lines for {@code navi llm}, at most four. */
    public static List<String> report(long nowMs) {
        if (!BotLlmConfig.enabled) {
            return List.of("bot llm chat is off on this server (BOT_LLM_ENABLED)");
        }
        Summary s = summarize(snapshot(), nowMs, REPORT_WINDOW_MS);
        String where = BotLlmConfig.backend + " " + BotLlmConfig.model;
        if (s == null) {
            return List.of("no llm calls in the last hour (" + where + ")");
        }
        List<String> lines = new ArrayList<>(4);
        StringBuilder head = new StringBuilder()
                .append("llm last hour: ").append(s.calls()).append(s.calls() == 1 ? " call, " : " calls, ")
                .append(Math.round(100.0 * s.ok() / s.calls())).append("% ok");
        if (s.timeouts() > 0 || s.errors() > 0) {
            head.append(" (").append(s.timeouts()).append(" timeouts, ").append(s.errors()).append(" errors)");
        }
        lines.add(head.append(", ").append(where).toString());
        lines.add("latency p50 " + ms(s.p50()) + ", p95 " + ms(s.p95()) + ", p99 " + ms(s.p99())
                + ", max " + ms(s.max()));
        if (!Double.isNaN(s.outputTokPerSec())) {
            lines.add(String.format(Locale.ROOT, "output %.0f tok/s end to end, avg %.0f in, %.0f out",
                    s.outputTokPerSec(), s.avgPromptTokens(), s.avgCompletionTokens()));
        }
        if (!Double.isNaN(s.cacheHitRatio())) {
            lines.add(String.format(Locale.ROOT, "prefix cache served %.0f%% of prompt tokens",
                    100 * s.cacheHitRatio()));
        } else if (BotLlmConfig.isOpenAi()) {
            lines.add("no prefix cache report; start sglang with --enable-cache-report");
        }
        return lines;
    }

    static String ms(long v) {
        return v < 1000 ? v + "ms" : String.format(Locale.ROOT, "%.1fs", v / 1000.0);
    }

    // ---- persistence -----------------------------------------------------------------------------------

    static String tsvRow(Call c) {
        return String.join("\t", Arrays.asList(
                Long.toString(c.atMs()), clean(BotLlmConfig.backend), clean(BotLlmConfig.model), clean(c.kind()),
                c.outcome().name().toLowerCase(Locale.ROOT), Long.toString(c.latencyMs()),
                Integer.toString(c.promptTokens()), Integer.toString(c.completionTokens()),
                Integer.toString(c.cachedTokens())));
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\t\\r\\n]", " ");
    }

    private static synchronized void append(Call call) {
        Path path = store;
        try {
            Path dir = path.getParent();
            if (dir != null) Files.createDirectories(dir);
            boolean fresh = !Files.exists(path) || Files.size(path) == 0;
            String text = (fresh ? TSV_HEADER + "\n" : "") + tsvRow(call) + "\n";
            Files.writeString(path, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("llm stats: append failed: {}", e.toString());
        }
    }
}
