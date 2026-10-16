package server.bots;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotGearMetaReportTest {
    private static BotGearMetaReport.Candidate c(int id, int req, double score) {
        return new BotGearMetaReport.Candidate(id, req, score, "drop");
    }

    @Test
    void theBestPieceAtEachLevelIsCompressedIntoReigns() {
        var low = c(1, 10, 3.0);
        var strong = c(2, 30, 5.0);
        var later = c(3, 50, 4.0); // never beats the level 30 piece: no reign
        List<BotGearMetaReport.Reign> r = BotGearMetaReport.reigns(List.of(later, strong, low), 10, 60, 10);
        assertEquals(2, r.size());
        assertEquals(List.of(1, 10, 20), List.of(r.get(0).itemId(), r.get(0).from(), r.get(0).to()));
        assertEquals(List.of(2, 30, 60), List.of(r.get(1).itemId(), r.get(1).from(), r.get(1).to()));
    }

    @Test
    void tiesGoToTheLowerLevelThenTheLowerIdAndGapsHaveNoReign() {
        var a = c(7, 20, 4.0);
        var b = c(5, 20, 4.0);
        var early = c(9, 15, 4.0);
        List<BotGearMetaReport.Reign> r = BotGearMetaReport.reigns(List.of(a, b, early), 10, 30, 10);
        assertEquals(1, r.size()); // nothing wearable at 10; the level 15 piece holds from 20 on
        assertEquals(List.of(9, 20, 30), List.of(r.get(0).itemId(), r.get(0).from(), r.get(0).to()));
    }
}
