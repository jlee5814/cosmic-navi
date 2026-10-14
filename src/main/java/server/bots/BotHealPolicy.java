package server.bots;

import java.awt.Rectangle;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * How a support bot decides to heal. A seam so the live cleric ({@link BotCombatManager#tickSupportHealing})
 * and the heal arena sim ({@code BotHealArena}, test code) run the same decision code. {@link Reactive} is today's
 * rule and the live one; the arena compares it with a one shot command and a standing "keep the party alive"
 * responsibility ({@code BotHealResponsibility}), see docs/bot/kb/kb_bot_heal_responsibility_ab.md.
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
            float target = BotCombatManager.cfg.SUPPORT_HEAL_TARGET_RATIO;
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

        /** The live check's threshold, in float as it was: HP below round(maxHp x ratio). */
        static boolean needs(Member m, float ratio) {
            return m.alive() && m.maxHp() > 0 && m.hp() < Math.round(m.maxHp() * ratio);
        }
    }
}
