package server.bots;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * How a support bot decides to heal. A seam so the live cleric ({@link BotCombatManager#tickSupportHealing})
 * and the heal arena sim run the same decision code: {@link Reactive} is today's rule, {@link KeepPartyAlive}
 * holds the party's survival as a standing goal.
 */
interface BotHealPolicy {

    Decision decide(View v);

    /** One party member as the healer sees it. {@code self} is the healer. */
    record Member(int id, int x, int y, int hp, int maxHp, boolean alive, boolean owner) {
        double ratio() {
            return maxHp <= 0 ? 1.0 : (double) hp / maxHp;
        }
    }

    /**
     * What the healer knows on one decision. {@code party} holds the other members on its map. {@code box} is
     * the Heal hitbox around the healer now (WZ lt/rb, the box {@code StatEffect.applyTo} heals). Heal restores
     * {@code healPer(affected)} ({@code StatEffect.healPerTarget}) to each of the {@code affected} members inside
     * it, healer included.
     */
    record View(long nowMs, Member self, List<Member> party, int mp, int maxMp, int healMpCost,
                IntUnaryOperator healPer, Rectangle box) {
        boolean inBox(Member m, int atX) {
            Rectangle b = new Rectangle(box);
            b.translate(atX - self.x(), 0);
            return b.contains(m.x(), m.y());
        }

        Member owner() {
            for (Member m : party) {
                if (m.owner()) {
                    return m;
                }
            }
            return null;
        }
    }

    enum Kind { HOLD, CAST, MOVE }

    /**
     * {@code CAST} heals now. {@code MOVE} walks toward {@code moveX} to bring someone into the box (the caster
     * casts on a later decision). {@code HOLD} leaves the tick to normal play. {@code saveMp} asks the caller
     * not to spend MP on attacks.
     */
    record Decision(Kind kind, int moveX, boolean saveMp, String reason) {
        static final Decision HOLD = new Decision(Kind.HOLD, 0, false, "");

        static Decision cast(boolean saveMp, String reason) {
            return new Decision(Kind.CAST, 0, saveMp, reason);
        }
    }

    /** Today's rule: heal whenever the healer or any member inside the box is under the target ratio. */
    final class Reactive implements BotHealPolicy {
        @Override
        public Decision decide(View v) {
            double target = BotCombatManager.cfg.SUPPORT_HEAL_TARGET_RATIO;
            if (needs(v.self(), target)) {
                return Decision.cast(false, "self");
            }
            for (Member m : v.party()) {
                if (m.alive() && v.inBox(m, v.self().x()) && needs(m, target)) {
                    return Decision.cast(false, "member " + m.id());
                }
            }
            return Decision.HOLD;
        }

        /** Same integer threshold as the live check: HP below round(maxHp x ratio). */
        static boolean needs(Member m, double ratio) {
            return m.alive() && m.maxHp() > 0 && m.hp() < Math.round(m.maxHp() * ratio);
        }
    }

    /**
     * "Keep my party alive": a standing responsibility, not a trigger.
     * <ul>
     *   <li>Goal: nobody dies, then nobody sits under 30% HP, then nobody drinks a potion a heal could replace;
     *       keep enough MP to keep doing it.</li>
     *   <li>Scope: every party member on the map, not only those inside the box.</li>
     *   <li>Constraints: legal cast cadence (the caller's animation lock), MP reserve, a leash to the owner.</li>
     *   <li>Duration: as long as the policy is held.</li>
     *   <li>Reconsider when: a member's HP projected over the next second falls into danger (heal before the
     *       hit lands, not after); an endangered member is out of reach (walk to them); MP drops under the
     *       reserve (stop spending MP on attacks); someone in the box is one seen hit from their potion line (top
     *       up, unless the heal would mostly land on full HP).</li>
     * </ul>
     * Damage rates come from the HP the healer watches change, as a player sees a party HP bar.
     */
    final class KeepPartyAlive implements BotHealPolicy {
        /** Cast lock plus a decision of reaction: how far ahead a heal has to look. */
        static final long HORIZON_MS = 1_000L;
        static final double URGENT_RATIO = 0.45;
        static final int RESERVE_HEALS = 3;
        /** How far from the owner the healer will walk to reach someone. */
        static final int LEASH_PX = 350;
        /** Damage per second decays toward the last window with this weight. */
        static final double DPS_SMOOTHING = 0.5;

        /** Last HP seen, when, the smoothed damage rate, and the biggest single drop seen (about one hit). */
        private record Seen(int hp, long atMs, double dps, int bigHit) {}

        private final Map<Integer, Seen> seen = new HashMap<>();

        @Override
        public Decision decide(View v) {
            observe(v.self(), v.nowMs());
            for (Member m : v.party()) {
                observe(m, v.nowMs());
            }
            boolean saveMp = v.mp() < RESERVE_HEALS * v.healMpCost();
            if (v.mp() < v.healMpCost()) {
                return new Decision(Kind.HOLD, 0, true, "out of mp");
            }

            // Most endangered member by projected HP: who will be in trouble by the time a heal lands.
            Member worst = null;
            double worstProjected = Double.MAX_VALUE;
            for (Member m : members(v)) {
                double p = projectedRatio(m);
                if (m.alive() && p < worstProjected) {
                    worst = m;
                    worstProjected = p;
                }
            }
            if (worst == null) {
                return Decision.HOLD;
            }
            if (worstProjected < URGENT_RATIO) {
                if (worst == v.self() || v.inBox(worst, v.self().x())) {
                    return Decision.cast(saveMp, "urgent " + worst.id());
                }
                int x = reachX(v, worst);
                if (x != v.self().x()) {
                    return new Decision(Kind.MOVE, x, saveMp, "reach " + worst.id());
                }
            }

            // Routine top up: a heal costs a little MP and restores thousands of HP across the box; a potion
            // restores a few hundred. So heal whoever in the box is one hit from the line where bots drink, unless
            // the heal (split across the box) would mostly land on full HP.
            int affected = 1;
            boolean routine = false;
            for (Member m : members(v)) {
                if (!m.alive() || (m != v.self() && !v.inBox(m, v.self().x()))) {
                    continue;
                }
                if (m != v.self()) {
                    affected++;
                }
                routine |= projectedHp(m) - bigHit(m) < BotManager.cfg.AUTOPOT_HP_THRESH * m.maxHp();
            }
            int perMember = v.healPer().applyAsInt(affected);
            int missing = 0;
            for (Member m : members(v)) {
                if (m.alive() && (m == v.self() || v.inBox(m, v.self().x()))) {
                    missing += Math.min(perMember, Math.max(0, m.maxHp() - projectedHp(m)));
                }
            }
            if (routine && !saveMp && missing >= perMember) {
                return Decision.cast(false, "top up");
            }
            return new Decision(Kind.HOLD, 0, saveMp, saveMp ? "saving mp" : "");
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

        private void observe(Member m, long now) {
            Seen s = seen.get(m.id());
            if (!m.alive()) {
                seen.remove(m.id());
                return;
            }
            if (s == null) {
                seen.put(m.id(), new Seen(m.hp(), now, 0.0, 0));
                return;
            }
            long dt = now - s.atMs();
            if (dt < 200) {
                return; // too short a window to read a rate; keep the last one
            }
            int drop = Math.max(0, s.hp() - m.hp());
            double windowDps = drop * 1000.0 / dt;
            double dps = s.dps() + DPS_SMOOTHING * (windowDps - s.dps());
            seen.put(m.id(), new Seen(m.hp(), now, dps, Math.max(s.bigHit(), drop)));
        }

        private int projectedHp(Member m) {
            Seen s = seen.get(m.id());
            double dps = s == null ? 0 : s.dps();
            return (int) Math.max(0, m.hp() - dps * HORIZON_MS / 1000.0);
        }

        private int bigHit(Member m) {
            Seen s = seen.get(m.id());
            return s == null ? 0 : s.bigHit();
        }

        private double projectedRatio(Member m) {
            return m.maxHp() <= 0 ? 1.0 : (double) projectedHp(m) / m.maxHp();
        }
    }
}
