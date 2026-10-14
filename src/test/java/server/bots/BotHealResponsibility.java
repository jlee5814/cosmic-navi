package server.bots;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Arm B of the heal A/B ({@link BotHealArena}): "keep my party alive" held as a standing responsibility. Lost to
 * today's rule ({@link BotHealPolicy.Reactive}) on potions and resupply trips, and its extra triggers made no
 * measurable difference when given today's top up (arm B2); see docs/bot/kb/kb_bot_heal_responsibility_ab.md.
 * Kept in test code so the experiment can be rerun.
 *
 * <p>A standing responsibility, not a trigger:
 * <ul>
 *   <li>Goal: nobody dies, then nobody sits under 30% HP, then nobody drinks a potion a heal could replace;
 *       keep enough MP to keep doing it.</li>
 *   <li>Scope: every party member on the map, not only those inside the box.</li>
 *   <li>Constraints: legal cast cadence (the caller's animation lock), MP reserve, a leash to the owner.</li>
 *   <li>Duration: as long as the policy is held.</li>
 *   <li>Reconsider when: one more hit like the last would put a member under 30% while they are still being
 *       hit (heal now if they are in the box, walk to them if not); MP drops under the reserve (stop spending
 *       MP on attacks); someone in the box is one remembered hit from their potion line (top up, unless the
 *       heal would mostly land on full HP).</li>
 * </ul>
 * Hits come from the HP the healer watches drop, as a player reads a party HP bar. Touch damage lands at
 * most once per invincibility window, longer than a heal cycle, so one more hit is the danger to plan for.
 */
final class BotHealResponsibility implements BotHealPolicy {
    static final double URGENT_RATIO = 0.30;
    /** A member hit this recently is still in contact and can take another. */
    static final long IN_CONTACT_MS = 3_000L;
    /** How long the biggest hit seen on a member is remembered. */
    static final long HIT_MEMORY_MS = 10_000L;
    static final int RESERVE_HEALS = 3;
    /** How far from the owner the healer will walk to reach someone. */
    static final int LEASH_PX = 350;

    /** Last HP seen, when the member was last hit, and the biggest hit remembered with when it landed. */
    private record Seen(int hp, long lastHitMs, int bigHit, long bigHitMs) {}

    private final Map<Integer, Seen> seen = new HashMap<>();
    /** Top up as today's rule does (anyone in the box under the target ratio) instead of at the potion line. */
    private final boolean eagerTopUp;

    BotHealResponsibility() {
        this(false);
    }

    BotHealResponsibility(boolean eagerTopUp) {
        this.eagerTopUp = eagerTopUp;
    }

    @Override
    public BotHealPolicy.Decision decide(BotHealPolicy.View v) {
        long now = v.nowMs();
        List<Member> all = members(v);
        for (Member m : all) {
            observe(m, now);
        }
        boolean saveMp = v.mp() < RESERVE_HEALS * v.healMpCost();
        if (v.mp() < v.healMpCost()) {
            return new BotHealPolicy.Decision(BotHealPolicy.Kind.HOLD, 0, true, "out of mp");
        }

        // Urgent: one more hit would put them under 30%. Heal anyone urgent in the box first; otherwise walk
        // to the most endangered one out of reach.
        Member reach = null;
        int reachMargin = Integer.MAX_VALUE;
        for (Member m : all) {
            if (!m.alive()) {
                continue;
            }
            int margin = m.hp() - nextHit(m, now) - (int) (URGENT_RATIO * m.maxHp());
            if (margin >= 0) {
                continue;
            }
            if (m == v.self() || v.inBox(m, v.self().x())) {
                return BotHealPolicy.Decision.cast(saveMp, "urgent " + m.id());
            }
            if (margin < reachMargin) {
                reach = m;
                reachMargin = margin;
            }
        }
        if (reach != null) {
            int x = reachX(v, reach);
            if (x != v.self().x()) {
                return new BotHealPolicy.Decision(BotHealPolicy.Kind.MOVE, x, saveMp, "reach " + reach.id());
            }
        }

        if (eagerTopUp) {
            Decision today = new BotHealPolicy.Reactive().decide(v);
            return today.kind() == BotHealPolicy.Kind.CAST ? BotHealPolicy.Decision.cast(saveMp, "top up") : new BotHealPolicy.Decision(BotHealPolicy.Kind.HOLD, 0, saveMp, "");
        }

        // Routine top up: a heal costs a little MP and restores thousands of HP across the box; a potion
        // restores a few hundred. So heal whoever in the box is one remembered hit from the line where bots
        // drink, unless the heal (split across the box) would mostly land on full HP.
        int affected = 1;
        boolean routine = false;
        for (Member m : all) {
            if (!m.alive() || (m != v.self() && !v.inBox(m, v.self().x()))) {
                continue;
            }
            if (m != v.self()) {
                affected++;
            }
            routine |= m.hp() - bigHit(m, now) < BotManager.cfg.AUTOPOT_HP_THRESH * m.maxHp();
        }
        int perMember = v.healPer().applyAsInt(affected);
        int missing = 0;
        for (Member m : all) {
            if (m.alive() && (m == v.self() || v.inBox(m, v.self().x()))) {
                missing += Math.min(perMember, Math.max(0, m.maxHp() - m.hp()));
            }
        }
        if (routine && !saveMp && missing >= perMember) {
            return BotHealPolicy.Decision.cast(false, "top up");
        }
        return new BotHealPolicy.Decision(BotHealPolicy.Kind.HOLD, 0, saveMp, saveMp ? "saving mp" : "");
    }

    private static List<Member> members(View v) {
        java.util.ArrayList<Member> all = new java.util.ArrayList<>(v.party().size() + 1);
        all.add(v.self());
        all.addAll(v.party());
        return all;
    }

    /** Where to stand to bring {@code m} into the box, kept within the leash of the owner. */
    private static int reachX(View v, Member m) {
        int half = Math.max(1, v.box().width / 2 - 20);
        int x = m.x() > v.self().x() ? m.x() - half : m.x() + half;
        Member owner = v.owner();
        if (owner != null && owner.alive()) {
            x = Math.clamp(x, owner.x() - LEASH_PX, owner.x() + LEASH_PX);
        }
        return x;
    }

    /** A drop between two looks is a hit (a heal or potion in between can hide part of one). */
    private void observe(Member m, long now) {
        if (!m.alive()) {
            seen.remove(m.id());
            return;
        }
        Seen s = seen.get(m.id());
        if (s == null) {
            seen.put(m.id(), new Seen(m.hp(), Long.MIN_VALUE / 2, 0, Long.MIN_VALUE / 2));
            return;
        }
        int drop = s.hp() - m.hp();
        if (drop <= 0) {
            seen.put(m.id(), new Seen(m.hp(), s.lastHitMs(), s.bigHit(), s.bigHitMs()));
            return;
        }
        boolean replace = drop >= s.bigHit() || now - s.bigHitMs() > HIT_MEMORY_MS;
        seen.put(m.id(), new Seen(m.hp(), now, replace ? drop : s.bigHit(), replace ? now : s.bigHitMs()));
    }

    /** The hit to plan for: the biggest remembered, if the member is still being hit. */
    private int nextHit(Member m, long now) {
        Seen s = seen.get(m.id());
        return s == null || now - s.lastHitMs() > IN_CONTACT_MS ? 0 : bigHit(m, now);
    }

    private int bigHit(Member m, long now) {
        Seen s = seen.get(m.id());
        return s == null || now - s.bigHitMs() > HIT_MEMORY_MS ? 0 : s.bigHit();
    }
}
