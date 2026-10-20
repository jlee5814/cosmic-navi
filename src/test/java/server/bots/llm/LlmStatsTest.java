package server.bots.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmStatsTest {
    private static final long NOW = 10_000_000L;

    private boolean enabled, persist;
    private String backend, model;
    private Path store;

    @BeforeEach
    void save() {
        enabled = BotLlmConfig.enabled;
        backend = BotLlmConfig.backend;
        model = BotLlmConfig.model;
        persist = LlmStats.persist;
        store = LlmStats.store;
        LlmStats.persist = false;
        LlmStats.clear();
    }

    @AfterEach
    void restore() {
        BotLlmConfig.enabled = enabled;
        BotLlmConfig.backend = backend;
        BotLlmConfig.model = model;
        LlmStats.persist = persist;
        LlmStats.store = store;
        LlmStats.clear();
    }

    private static LlmStats.Call ok(long ageMs, long latency, int prompt, int completion, int cached) {
        return new LlmStats.Call(NOW - ageMs, "chat", LlmStats.Outcome.OK, latency, prompt, completion, cached);
    }

    @Test
    void sglangUsageWithCacheReportIsParsed() {
        String json = "{\"choices\":[{\"message\":{\"content\":\"yo\"}}],\"usage\":{\"prompt_tokens\":312,"
                + "\"total_tokens\":336,\"completion_tokens\":24,\"prompt_tokens_details\":{\"cached_tokens\":256}}}";
        assertEquals(new LlmStats.Usage(312, 24, 256), LlmStats.parseOpenAiUsage(json));
    }

    @Test
    void usageWithoutCacheReportLeavesCachedUnknown() {
        String json = "{\"usage\":{\"prompt_tokens\": 300, \"completion_tokens\": 12}}";
        assertEquals(new LlmStats.Usage(300, 12, -1), LlmStats.parseOpenAiUsage(json));
        assertEquals(LlmStats.Usage.NONE, LlmStats.parseOpenAiUsage("{\"choices\":[]}"));
        assertEquals(LlmStats.Usage.NONE, LlmStats.parseOpenAiUsage(null));
    }

    @Test
    void contentMentioningTokenNamesBeforeUsageIsIgnored() {
        String json = "{\"choices\":[{\"message\":{\"content\":\"prompt_tokens\"}}],"
                + "\"usage\":{\"prompt_tokens\":40,\"completion_tokens\":5}}";
        assertEquals(new LlmStats.Usage(40, 5, -1), LlmStats.parseOpenAiUsage(json));
    }

    @Test
    void ollamaCountsAreParsed() {
        String json = "{\"response\":\"hi\",\"done\":true,\"prompt_eval_count\":280,\"eval_count\":9}";
        assertEquals(new LlmStats.Usage(280, 9, -1), LlmStats.parseOllamaUsage(json));
    }

    @Test
    void percentileIsNearestRank() {
        long[] lat = {100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
        assertEquals(500, LlmStats.percentile(lat, 50));
        assertEquals(1000, LlmStats.percentile(lat, 95));
        assertEquals(1000, LlmStats.percentile(lat, 99));
        assertEquals(100, LlmStats.percentile(new long[] {100}, 99));
        assertEquals(0, LlmStats.percentile(new long[0], 50));
    }

    @Test
    void timeoutsCountInTheTailButNotInThroughput() {
        List<LlmStats.Call> calls = List.of(
                ok(1_000, 200, 300, 20, 240),
                ok(2_000, 200, 300, 20, 240),
                new LlmStats.Call(NOW - 3_000, "chat", LlmStats.Outcome.TIMEOUT, 15_000, -1, -1, -1));
        LlmStats.Summary s = LlmStats.summarize(calls, NOW, 60_000);
        assertEquals(3, s.calls());
        assertEquals(2, s.ok());
        assertEquals(1, s.timeouts());
        assertEquals(15_000, s.p99());
        assertEquals(15_000, s.max());
        assertEquals(100.0, s.outputTokPerSec(), 1e-9);  // 40 tokens over 400 ms
        assertEquals(0.8, s.cacheHitRatio(), 1e-9);
    }

    @Test
    void cacheRatioUsesOnlyCallsThatReportedIt() {
        List<LlmStats.Call> calls = List.of(ok(1_000, 100, 300, 10, 150), ok(1_000, 100, 900, 10, -1));
        LlmStats.Summary s = LlmStats.summarize(calls, NOW, 60_000);
        assertEquals(0.5, s.cacheHitRatio(), 1e-9);
        assertEquals(600.0, s.avgPromptTokens(), 1e-9);
    }

    @Test
    void callsOutsideTheWindowAreDropped() {
        List<LlmStats.Call> calls = List.of(ok(10_000, 100, 1, 1, 1), ok(120_000, 9_000, 1, 1, 1));
        assertEquals(1, LlmStats.summarize(calls, NOW, 60_000).calls());
        assertNull(LlmStats.summarize(calls, NOW, 1_000));
    }

    @Test
    void reportExplainsWhenChatIsOffOrIdle() {
        BotLlmConfig.enabled = false;
        assertTrue(LlmStats.report(NOW).get(0).contains("off"));
        BotLlmConfig.enabled = true;
        BotLlmConfig.backend = "openai";
        BotLlmConfig.model = "default";
        assertEquals(List.of("no llm calls in the last hour (openai default)"),
                LlmStats.report(System.currentTimeMillis()));
    }

    @Test
    void reportFitsInWhispersAndNudgesForTheCacheReport() {
        BotLlmConfig.enabled = true;
        BotLlmConfig.backend = "openai";
        BotLlmConfig.model = "default";
        LlmStats.record("chat", LlmStats.Outcome.OK, 120, new LlmStats.Usage(300, 24, -1));
        LlmStats.record("chat", LlmStats.Outcome.TIMEOUT, 15_000, null);
        List<String> lines = LlmStats.report(System.currentTimeMillis());
        assertEquals("llm last hour: 2 calls, 50% ok (1 timeouts, 0 errors), openai default", lines.get(0));
        assertEquals("latency p50 120ms, p95 15.0s, p99 15.0s, max 15.0s", lines.get(1));
        assertEquals("output 200 tok/s end to end, avg 300 in, 24 out", lines.get(2));
        assertEquals("no prefix cache report; start sglang with --enable-cache-report", lines.get(3));
        assertTrue(lines.stream().allMatch(l -> l.length() <= 120), lines.toString());
    }

    @Test
    void memoryKeepsOnlyTheNewestCalls() {
        for (int i = 0; i < LlmStats.CAPACITY + 5; i++) {
            LlmStats.record("chat", LlmStats.Outcome.OK, i, null);
        }
        List<LlmStats.Call> calls = LlmStats.snapshot();
        assertEquals(LlmStats.CAPACITY, calls.size());
        assertEquals(5, calls.get(0).latencyMs());
    }

    @Test
    void everyCallIsAppendedAsOneTsvRowUnderOneHeader(@TempDir Path dir) throws Exception {
        LlmStats.store = dir.resolve("llm-stats").resolve("calls.tsv");
        LlmStats.persist = true;
        BotLlmConfig.backend = "openai";
        BotLlmConfig.model = "qwen\t3";
        LlmStats.record("chat", LlmStats.Outcome.OK, 110, new LlmStats.Usage(300, 24, 256));
        LlmStats.record("structured", LlmStats.Outcome.TIMEOUT, 15_000, null);
        List<String> rows = Files.readAllLines(LlmStats.store);
        assertEquals(3, rows.size());
        assertEquals(LlmStats.TSV_HEADER, rows.get(0));
        assertTrue(rows.get(1).endsWith("\topenai\tqwen 3\tchat\tok\t110\t300\t24\t256"), rows.get(1));
        assertTrue(rows.get(2).endsWith("\tstructured\ttimeout\t15000\t-1\t-1\t-1"), rows.get(2));
        assertEquals(9, rows.get(1).split("\t", -1).length);
    }
}
