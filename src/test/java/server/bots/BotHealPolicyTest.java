package server.bots;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The heal decisions ({@link BotHealPolicy}) on hand built views; Heal lv30's box is 600 by 400. */
class BotHealPolicyTest {
    private static final Rectangle BOX_AT_500 = new Rectangle(200, -200, 600, 400);

    private static BotHealPolicy.Member m(int id, int x, int hp, int maxHp, boolean owner) {
        return new BotHealPolicy.Member(id, x, 0, hp, maxHp, hp > 0, owner);
    }

    private static BotHealPolicy.View view(long now, int mp, BotHealPolicy.Member self, BotHealPolicy.Member... party) {
        return new BotHealPolicy.View(now, self, List.of(party), mp, 2506, 52, n -> 2451 / n, BOX_AT_500);
    }

    @Test
    void todaysRuleHealsAnyoneInTheBoxUnderNinetyPercentAndNobodyOutsideIt() {
        BotHealPolicy reactive = new BotHealPolicy.Reactive();
        BotHealPolicy.Member cleric = m(4, 500, 817, 817, false);
        assertEquals(BotHealPolicy.Kind.HOLD, reactive.decide(view(0, 2506, cleric, m(1, 600, 2600, 2800, true))).kind());
        assertEquals(BotHealPolicy.Kind.CAST, reactive.decide(view(0, 2506, cleric, m(1, 600, 2400, 2800, true))).kind());
        assertEquals(BotHealPolicy.Kind.HOLD, reactive.decide(view(0, 2506, cleric, m(1, 900, 100, 2800, true))).kind(),
                "a dying owner just outside the box is invisible to today's rule");
        assertEquals(BotHealPolicy.Kind.CAST, reactive.decide(view(0, 2506, m(4, 500, 700, 817, false))).kind(), "self");
    }

    @Test
    void theResponsibilityWalksToAnEndangeredMemberOutOfReachWithinTheLeash() {
        BotHealPolicy b = new BotHealPolicy.KeepPartyAlive();
        BotHealPolicy.Member cleric = m(4, 500, 817, 817, false);
        b.decide(view(0, 2506, cleric, m(1, 900, 1600, 2800, true)));
        // Hit for 600 and still in contact: one more like it puts him under 30%, and he is outside the box.
        BotHealPolicy.Decision d = b.decide(view(500, 2506, cleric, m(1, 900, 1000, 2800, true)));
        assertEquals(BotHealPolicy.Kind.MOVE, d.kind(), d.reason());
        assertTrue(d.moveX() > 500 && d.moveX() <= 900, "toward the owner: " + d.moveX());
    }

    @Test
    void anUrgentMemberInTheBoxIsHealedBeforeWalkingToOneOutsideIt() {
        BotHealPolicy b = new BotHealPolicy.KeepPartyAlive();
        BotHealPolicy.Member cleric = m(4, 500, 817, 817, false);
        b.decide(view(0, 2506, cleric, m(1, 900, 1600, 2800, true), m(2, 600, 1300, 2500, false)));
        BotHealPolicy.Decision d = b.decide(view(500, 2506, cleric, m(1, 900, 1000, 2800, true),
                m(2, 600, 800, 2500, false)));
        assertEquals(BotHealPolicy.Kind.CAST, d.kind(), d.reason());
        assertEquals("urgent 2", d.reason());
    }

    @Test
    void itPlansForOneMoreHitLikeTheLastWhileTheMemberIsStillBeingHit() {
        BotHealPolicy b = new BotHealPolicy.KeepPartyAlive();
        BotHealPolicy.Member cleric = m(4, 500, 817, 817, false);
        b.decide(view(0, 2506, cleric, m(1, 600, 2000, 2800, true)));
        // 50% after a 600 hit: one more puts him at 29%.
        BotHealPolicy.Decision hit = b.decide(view(500, 2506, cleric, m(1, 600, 1400, 2800, true)));
        assertEquals("urgent 1", hit.reason());
        // The same 50% with no hit in the last three seconds is no emergency (a routine top up instead).
        BotHealPolicy.Decision calm = b.decide(view(5_000, 2506, cleric, m(1, 600, 1400, 2800, true)));
        assertEquals("top up", calm.reason());
    }

    @Test
    void itTopsUpBeforeThePotionLineSkipsChipsAndSavesMpUnderItsReserve() {
        BotHealPolicy b = new BotHealPolicy.KeepPartyAlive();
        BotHealPolicy.Member cleric = m(4, 500, 817, 817, false);
        // 2451 split over 4 is 612 each. A member 400 short is above the potion line: no cast for a chip. Under
        // the line where bots drink (70%), the heal tops him up instead of a potion.
        BotHealPolicy.Decision chip = b.decide(view(0, 2506, cleric, m(1, 600, 2400, 2800, true),
                m(2, 550, 2500, 2500, false), m(3, 450, 1100, 1100, false)));
        assertEquals(BotHealPolicy.Kind.HOLD, chip.kind(), chip.reason());
        BotHealPolicy.Decision topUp = new BotHealPolicy.KeepPartyAlive().decide(view(0, 2506, cleric,
                m(1, 600, 1900, 2800, true), m(2, 550, 2500, 2500, false), m(3, 450, 1100, 1100, false)));
        assertEquals(BotHealPolicy.Kind.CAST, topUp.kind(), topUp.reason());
        BotHealPolicy.Decision low = new BotHealPolicy.KeepPartyAlive().decide(view(0, 120, cleric,
                m(1, 600, 2800, 2800, true)));
        assertTrue(low.saveMp(), "under three heals of MP it stops spending MP on attacks");
    }
}
