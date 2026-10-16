package server.bots;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotGearPlanReportTest {
    private static BotGearPlanReport.Pick pick(int id, double gain, double meso) {
        return new BotGearPlanReport.Pick(id, 10, gain, new BotScrollManager.Acquisition("farm", meso));
    }

    @Test
    void picksRankByGainPerHourNotByGain() {
        var zakum = pick(1, 30.0, 3_600_000.0 * 300); // a big gain at 300 hours of solo boss time
        var gloves = pick(2, 3.0, 3_600_000.0 * 0.5); // a small gain at half an hour
        var shoes = pick(3, 3.0, 3_600_000.0 * 1);
        List<BotGearPlanReport.Pick> picks = new ArrayList<>(List.of(zakum, shoes, gloves));
        picks.sort(BotGearPlanReport.BEST_FIRST);
        assertEquals(List.of(2, 3, 1), picks.stream().map(BotGearPlanReport.Pick::itemId).toList());
        assertEquals(6.0, gloves.perHour(), 1e-9);
    }
}
