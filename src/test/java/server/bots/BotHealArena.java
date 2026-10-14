package server.bots;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import server.StatEffect;
import server.bots.combat.BotDefenseDataProvider;
import server.combat.CombatFormulaProvider;
import server.life.LifeFactory;
import server.life.MonsterStats;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A seeded party fight on one flat platform, to compare how a cleric heals ({@link BotHealPolicy}) with every
 * other thing held equal. Owner (a human warrior), a warrior bot, an archer bot and the cleric grind a
 * respawning mob.
 *
 * <p>From the game's own code and data (the SSOT): the mob's level, touch damage, accuracy, HP and exp (Mob.wz
 * via {@link LifeFactory}); Heal's MP cost, box, split per member ({@link StatEffect#healPerTarget}) and
 * animation lock, and Energy Bolt's MP cost (Skill.wz); touch damage and the mob's hit chance
 * ({@link BotDefenseDataProvider#physicalTouchDamageAt}, {@link CombatFormulaProvider#calculateBotAvoidChance});
 * the hit invincibility window and heal walk window ({@link BotCombatManager.Config}); bot potion thresholds
 * ({@link BotManager.Config}).
 *
 * <p>Assumed, and listed in {@link Assumptions}: the 1D platform, movement and mob chasing, member stats and
 * damage, potion sizes and the owner's human reaction to low HP.
 */
final class BotHealArena {

    /** Everything not read from the game. */
    static final class Assumptions {
        static final int PLATFORM_PX = 1600;
        static final int WALK_PX_PER_S = 120;          // BotPhysicsEngine.walkStep: 6 px a 50 ms tick
        static final int MOB_PX_PER_S = 70;
        static final int TOUCH_PX = 35;
        static final int MOB_CHASE_PX = 400;           // a hit mob turns on its attacker within this range
        static final long MOB_RESPAWN_MS = 7_000L;
        static final long DEATH_DOWNTIME_MS = 60_000L; // respawn in town and walk back
        static final int HP_POT = 300;                 // White Potion
        static final int MP_POT = 300;                 // Mana Elixir
        static final long BOT_POT_GAP_MS = 300L;
        static final double OWNER_POT_RATIO = 0.5;     // a human pots later than a bot's autopot
        static final long OWNER_REACT_MIN_MS = 600L, OWNER_REACT_MAX_MS = 2_000L;
        static final double OWNER_LAPSE_CHANCE = 0.2;  // looking at chat, mid combo, out of hotkey reach
        static final long OWNER_LAPSE_MIN_MS = 2_000L, OWNER_LAPSE_MAX_MS = 5_000L;
        static final long OWNER_POT_GAP_MS = 400L;
        static final double OWNER_ASK_HEAL_RATIO = 0.5; // arm A1: the owner types heal
        static final long OWNER_ASK_MIN_MS = 800L, OWNER_ASK_MAX_MS = 2_000L;
        static final int CLERIC_FOLLOW_PX = 100;
        static final int ENERGY_BOLT_RANGE_PX = 300;
        static final int ARCHER_RANGE_PX = 350, ARCHER_MIN_PX = 150;
    }

    enum Arm { A1_COMMAND, A2_REACTIVE, B_RESPONSIBILITY }

    enum Role { OWNER, MELEE_BOT, RANGED_BOT, CLERIC }

    record Result(Arm arm, int seed, long durationMs, int deaths, double secondsUnder30, long exp, int hpPots,
                  int mpPots, int heals) {}

    private static final int HEAL = 2301002;
    private static final int ENERGY_BOLT = 2001005;
    private static final long TICK_MS = 50L;
    private static final long AI_MS = 100L;

    /** One member. The mock carries the stats the damage and avoid formulas read. */
    private static final class Fighter {
        final int id;
        final Role role;
        final Character stats;
        final int maxHp;
        final int dmgPerHit;
        final long attackGapMs;
        int hp;
        int x;
        int mp, maxMp;
        boolean alive = true;
        long backAtMs;
        long iframeUntilMs;
        long lockedUntilMs;
        long nextAttackMs;
        long nextPotMs;
        long ownerPotDueMs = -1; // owner: when the scheduled pot press lands
        Mob target;

        Fighter(int id, Role role, Character stats, int maxHp, int dmgPerHit, long attackGapMs, int x) {
            this.id = id;
            this.role = role;
            this.stats = stats;
            this.maxHp = maxHp;
            this.hp = maxHp;
            this.dmgPerHit = dmgPerHit;
            this.attackGapMs = attackGapMs;
            this.x = x;
        }
    }

    private static final class Mob {
        final int spawnX;
        int x;
        int hp;
        boolean alive = true;
        long respawnAtMs;
        Fighter chasing;
        int wanderDir = 1;

        Mob(int x, int hp) {
            this.spawnX = x;
            this.x = x;
            this.hp = hp;
        }
    }

    private final Arm arm;
    private final int seed;
    private final Random rng;
    private final int mobId;
    private final int mobLevel, mobPad, mobAcc, mobHp, mobExp;
    private final List<Fighter> party = new ArrayList<>();
    private final List<Mob> mobs = new ArrayList<>();
    private final Fighter owner, cleric;
    private final StatEffect heal;
    private final int healLockMs;
    private final int boltMp;
    private final BotHealPolicy policy;
    private long now;
    private int deaths, hpPots, mpPots, heals;
    private long exp;
    private double underMs;
    private long nextHealMs;
    // Arm A1: the owner's outstanding heal command.
    private long askDueMs = -1;
    private boolean askPending;

    BotHealArena(Arm arm, int seed, int mobId, int mobsPer1000Px) {
        this.arm = arm;
        this.seed = seed;
        this.rng = new Random(seed);
        this.mobId = mobId;
        MonsterStats m = LifeFactory.getCachedMonsterStats(mobId);
        this.mobLevel = m.getLevel();
        this.mobPad = m.getPADamage();
        this.mobAcc = m.getAccuracy();
        this.mobHp = m.getHp();
        this.mobExp = m.getExp();
        Skill healSkill = SkillFactory.getSkill(HEAL);
        this.heal = healSkill.getEffect(30);
        this.healLockMs = Math.max(600, healSkill.getAnimationTime());
        this.boltMp = SkillFactory.getSkill(ENERGY_BOLT).getEffect(20).getMpCon();

        // Member stats for a level 43 to 45 party; the cleric is SipsBuddy12 as live on 2026-10-14.
        owner = add(Role.OWNER, Job.FIGHTER, 45, 180, 40, 4, 4, 250, 2800, 900, 700L, 700);
        add(Role.MELEE_BOT, Job.PAGE, 43, 170, 40, 4, 4, 230, 2500, 800, 700L, 760);
        add(Role.RANGED_BOT, Job.HUNTER, 43, 40, 170, 4, 4, 90, 1100, 700, 600L, 450);
        cleric = add(Role.CLERIC, Job.CLERIC, 43, 4, 4, 223, 4, 60, 817, 450, 750L, 600);
        cleric.mp = cleric.maxMp = 2506;

        int count = Math.max(1, Assumptions.PLATFORM_PX * mobsPer1000Px / 1000);
        for (int i = 0; i < count; i++) {
            mobs.add(new Mob((int) ((i + 0.5) * Assumptions.PLATFORM_PX / count), mobHp));
        }
        policy = switch (arm) {
            case A1_COMMAND -> null;
            case A2_REACTIVE -> new BotHealPolicy.Reactive();
            case B_RESPONSIBILITY -> new BotHealPolicy.KeepPartyAlive();
        };
    }

    private Fighter add(Role role, Job job, int level, int str, int dex, int intel, int luk, int wdef, int maxHp,
                        int dmg, long gap, int x) {
        Character c = mock(Character.class);
        when(c.getJob()).thenReturn(job);
        when(c.getLevel()).thenReturn(level);
        when(c.getTotalStr()).thenReturn(str);
        when(c.getTotalDex()).thenReturn(dex);
        when(c.getTotalInt()).thenReturn(intel);
        when(c.getTotalLuk()).thenReturn(luk);
        when(c.getTotalWdef()).thenReturn(wdef);
        Fighter f = new Fighter(party.size() + 1, role, c, maxHp, dmg, gap, x);
        party.add(f);
        return f;
    }

    Result run(long durationMs) {
        for (now = 0; now < durationMs; now += TICK_MS) {
            boolean ai = now % AI_MS == 0;
            revive();
            for (Fighter f : party) {
                if (f.alive && f.hp * 10 < f.maxHp * 3) {
                    underMs += TICK_MS;
                }
            }
            tickMobs();
            for (Fighter f : party) {
                if (!f.alive) {
                    continue;
                }
                potions(f);
                if (f == cleric) {
                    if (ai) {
                        clericTick();
                    }
                } else if (ai) {
                    fighterTick(f);
                }
            }
            if (arm == Arm.A1_COMMAND) {
                ownerAsks();
            }
        }
        return new Result(arm, seed, durationMs, deaths, underMs / 1000.0, exp, hpPots, mpPots, heals);
    }

    // ---- cleric ----

    private void clericTick() {
        if (now < cleric.lockedUntilMs) {
            return;
        }
        BotHealPolicy.Decision d = decide();
        if (d.kind() == BotHealPolicy.Kind.CAST && now >= nextHealMs && cleric.mp >= heal.getMpCon()) {
            castHeal();
            return;
        }
        int goal = followX();
        if (d.kind() == BotHealPolicy.Kind.MOVE) {
            goal = d.moveX();
        }
        walk(cleric, goal);
        boolean saveMp = d.saveMp();
        Mob t = nearestMob(cleric.x, Assumptions.ENERGY_BOLT_RANGE_PX);
        if (t != null && !saveMp && cleric.mp >= boltMp && now >= cleric.nextAttackMs) {
            cleric.mp -= boltMp;
            hit(cleric, t);
            cleric.lockedUntilMs = now + cleric.attackGapMs;
            cleric.nextAttackMs = now + cleric.attackGapMs;
        }
    }

    private BotHealPolicy.Decision decide() {
        if (arm == Arm.A1_COMMAND) {
            if (!askPending || now < askDueMs || !owner.alive) {
                return BotHealPolicy.Decision.HOLD;
            }
            if (heal.calculateBoundingBox(new Point(cleric.x, 0), false).contains(owner.x, 0)) {
                askPending = false; // cast_skill(heal, owner): one cast, then the instruction is finished
                return BotHealPolicy.Decision.cast(false, "asked");
            }
            return new BotHealPolicy.Decision(BotHealPolicy.Kind.MOVE, owner.x, false, "asked, walking");
        }
        return policy.decide(view());
    }

    private BotHealPolicy.View view() {
        List<BotHealPolicy.Member> others = new ArrayList<>();
        for (Fighter f : party) {
            if (f != cleric) {
                others.add(member(f));
            }
        }
        Rectangle box = heal.calculateBoundingBox(new Point(cleric.x, 0), false);
        return new BotHealPolicy.View(now, member(cleric), others, cleric.mp, cleric.maxMp, heal.getMpCon(),
                affected -> heal.healPerTarget(cleric.maxHp, affected), box);
    }

    private BotHealPolicy.Member member(Fighter f) {
        return new BotHealPolicy.Member(f.id, f.x, 0, f.alive ? f.hp : 0, f.maxHp, f.alive, f == owner);
    }

    private void castHeal() {
        Rectangle box = heal.calculateBoundingBox(new Point(cleric.x, 0), false);
        List<Fighter> in = new ArrayList<>();
        for (Fighter f : party) {
            if (f.alive && (f == cleric || box.contains(f.x, 0))) {
                in.add(f);
            }
        }
        int per = heal.healPerTarget(cleric.maxHp, in.size());
        for (Fighter f : in) {
            f.hp = Math.min(f.maxHp, f.hp + per);
        }
        cleric.mp -= heal.getMpCon();
        heals++;
        cleric.lockedUntilMs = now + healLockMs;
        nextHealMs = now + healLockMs + BotCombatManager.cfg.HEAL_MOVE_WINDOW_MS;
    }

    private int followX() {
        Fighter anchor = owner.alive ? owner : firstAlive();
        if (anchor == null || anchor == cleric) {
            return cleric.x;
        }
        int facing = anchor.target != null && anchor.target.x < anchor.x ? -1 : 1;
        return anchor.x - facing * Assumptions.CLERIC_FOLLOW_PX;
    }

    // ---- the other members ----

    private void fighterTick(Fighter f) {
        if (now < f.lockedUntilMs) {
            return;
        }
        if (f.target == null || !f.target.alive) {
            f.target = nearestMob(f.role == Role.RANGED_BOT && owner.alive ? owner.x : f.x, Integer.MAX_VALUE);
        }
        Mob t = f.target;
        if (t == null) {
            return;
        }
        int dx = t.x - f.x;
        if (f.role == Role.RANGED_BOT) {
            if (Math.abs(dx) < Assumptions.ARCHER_MIN_PX) {
                walk(f, f.x - Integer.signum(dx == 0 ? 1 : dx) * 200);
                return;
            }
            if (Math.abs(dx) > Assumptions.ARCHER_RANGE_PX) {
                walk(f, t.x - Integer.signum(dx) * (Assumptions.ARCHER_RANGE_PX - 50));
                return;
            }
        } else if (Math.abs(dx) > Assumptions.TOUCH_PX) {
            walk(f, t.x);
            return;
        }
        if (now >= f.nextAttackMs) {
            hit(f, t);
            f.nextAttackMs = now + f.attackGapMs;
            f.lockedUntilMs = now + Math.min(f.attackGapMs, 600L);
        }
    }

    private void hit(Fighter f, Mob t) {
        t.hp -= (int) (f.dmgPerHit * (0.8 + 0.4 * rng.nextDouble()));
        if (Math.abs(t.x - f.x) <= Assumptions.MOB_CHASE_PX) {
            t.chasing = f;
        }
        if (t.hp <= 0) {
            t.alive = false;
            t.respawnAtMs = now + Assumptions.MOB_RESPAWN_MS;
            t.chasing = null;
            exp += mobExp;
        }
    }

    // ---- mobs and damage ----

    private void tickMobs() {
        double step = Assumptions.MOB_PX_PER_S * TICK_MS / 1000.0;
        for (Mob m : mobs) {
            if (!m.alive) {
                if (now >= m.respawnAtMs) {
                    m.alive = true;
                    m.hp = mobHp;
                    m.x = m.spawnX;
                }
                continue;
            }
            if (m.chasing != null && m.chasing.alive) {
                m.x += (int) Math.round(Math.signum(m.chasing.x - m.x) * Math.min(step, Math.abs(m.chasing.x - m.x)));
            } else {
                m.chasing = null;
                if (rng.nextDouble() < 0.02) {
                    m.wanderDir = -m.wanderDir;
                }
                if (Math.abs(m.x + m.wanderDir * step - m.spawnX) > 150) {
                    m.wanderDir = -m.wanderDir;
                }
                m.x += (int) Math.round(m.wanderDir * step);
            }
            for (Fighter f : party) {
                if (f.alive && now >= f.iframeUntilMs && Math.abs(m.x - f.x) <= Assumptions.TOUCH_PX) {
                    touch(f);
                }
            }
        }
    }

    private void touch(Fighter f) {
        f.iframeUntilMs = now + BotCombatManager.cfg.MOB_HIT_COOLDOWN_MS;
        var formulas = CombatFormulaProvider.getInstance();
        double pHit = formulas.calculateBotAvoidChance(mobAcc, mobLevel, f.stats.getLevel(),
                formulas.getTotalAvoidability(f.stats));
        if (rng.nextDouble() > pHit) {
            return;
        }
        f.hp -= BotDefenseDataProvider.getInstance().physicalTouchDamageAt(f.stats, mobPad, mobLevel, rng.nextDouble());
        if (f.hp <= 0) {
            f.hp = 0;
            f.alive = false;
            f.backAtMs = now + Assumptions.DEATH_DOWNTIME_MS;
            f.target = null;
            deaths++;
            if (f == owner) {
                askPending = false;
                owner.ownerPotDueMs = -1;
            }
        }
    }

    private void revive() {
        for (Fighter f : party) {
            if (!f.alive && now >= f.backAtMs) {
                f.alive = true;
                f.hp = f.maxHp;
                f.x = 0;
                if (f == cleric) {
                    cleric.mp = cleric.maxMp;
                }
            }
        }
    }

    // ---- potions and the owner's own reactions ----

    private void potions(Fighter f) {
        if (f == owner) {
            ownerPotions();
        } else if (now >= f.nextPotMs && f.hp < f.maxHp * BotManager.cfg.AUTOPOT_HP_THRESH) {
            f.hp = Math.min(f.maxHp, f.hp + Assumptions.HP_POT);
            hpPots++;
            f.nextPotMs = now + Assumptions.BOT_POT_GAP_MS;
        }
        if (f == cleric && now >= f.nextPotMs && f.mp < f.maxMp * BotManager.cfg.AUTOPOT_MP_THRESH) {
            f.mp = Math.min(f.maxMp, f.mp + Assumptions.MP_POT);
            mpPots++;
            f.nextPotMs = now + Assumptions.BOT_POT_GAP_MS;
        }
    }

    private void ownerPotions() {
        boolean low = owner.hp < owner.maxHp * Assumptions.OWNER_POT_RATIO;
        if (owner.ownerPotDueMs < 0) {
            if (low) {
                long delay = between(Assumptions.OWNER_REACT_MIN_MS, Assumptions.OWNER_REACT_MAX_MS);
                if (rng.nextDouble() < Assumptions.OWNER_LAPSE_CHANCE) {
                    delay += between(Assumptions.OWNER_LAPSE_MIN_MS, Assumptions.OWNER_LAPSE_MAX_MS);
                }
                owner.ownerPotDueMs = now + delay;
            }
            return;
        }
        if (now < owner.ownerPotDueMs) {
            return;
        }
        if (owner.hp < owner.maxHp * (Assumptions.OWNER_POT_RATIO + 0.1)) {
            owner.hp = Math.min(owner.maxHp, owner.hp + Assumptions.HP_POT);
            hpPots++;
            owner.ownerPotDueMs = now + Assumptions.OWNER_POT_GAP_MS; // keeps pressing until back above
        } else {
            owner.ownerPotDueMs = -1;
        }
    }

    private void ownerAsks() {
        if (!askPending && owner.alive && owner.hp < owner.maxHp * Assumptions.OWNER_ASK_HEAL_RATIO) {
            askPending = true;
            askDueMs = now + between(Assumptions.OWNER_ASK_MIN_MS, Assumptions.OWNER_ASK_MAX_MS);
        }
    }

    // ---- helpers ----

    private void walk(Fighter f, int goal) {
        goal = Math.clamp(goal, 0, Assumptions.PLATFORM_PX);
        int step = (int) (Assumptions.WALK_PX_PER_S * AI_MS / 1000);
        f.x += (int) Math.signum(goal - f.x) * Math.min(step, Math.abs(goal - f.x));
    }

    private Mob nearestMob(int x, int within) {
        Mob best = null;
        for (Mob m : mobs) {
            if (m.alive && Math.abs(m.x - x) <= within && (best == null || Math.abs(m.x - x) < Math.abs(best.x - x))) {
                best = m;
            }
        }
        return best;
    }

    private Fighter firstAlive() {
        for (Fighter f : party) {
            if (f.alive && f != cleric) {
                return f;
            }
        }
        return null;
    }

    private long between(long lo, long hi) {
        return lo + (long) (rng.nextDouble() * (hi - lo));
    }
}
