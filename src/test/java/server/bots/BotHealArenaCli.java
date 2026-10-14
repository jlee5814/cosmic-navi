package server.bots;

import client.SkillFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Runs {@link BotHealArena} for every arm on the same seeds and prints per hour rates with paired differences
 * (B minus each baseline, mean and 95% t interval over seeds). Under 30% is per member hour on the field, so an
 * arm that keeps members alive longer is not charged for the extra exposure.
 * usage: BotHealArenaCli &lt;mobId&gt; &lt;mobsPer1000Px,...&gt; &lt;seeds&gt; &lt;minutes&gt; [&lt;potSupply,...&gt;] (-1 unlimited)
 */
public final class BotHealArenaCli {
    public static void main(String[] args) {
        int mobId = Integer.parseInt(args[0]);
        String[] densities = args[1].split(",");
        int seeds = Integer.parseInt(args[2]);
        long ms = Long.parseLong(args[3]) * 60_000L;
        String[] supplies = args.length > 4 ? args[4].split(",") : new String[]{"-1"};
        double h = ms / 3_600_000.0;
        SkillFactory.loadAllSkills();
        System.out.println("mob\tdensity/pots\tarm\tseeds\tdeaths/h\tunder30 s/fieldh\texp/h\thpPots/h\tmpPots/h"
                + "\tresupplies/h\theals/h");
        for (String sup : supplies) {
            for (String d : densities) {
                int density = Integer.parseInt(d);
                int pots = Integer.parseInt(sup);
                Map<BotHealArena.Arm, List<BotHealArena.Result>> byArm = new EnumMap<>(BotHealArena.Arm.class);
                for (BotHealArena.Arm arm : BotHealArena.Arm.values()) {
                    List<BotHealArena.Result> rs = new ArrayList<>();
                    for (int s = 1; s <= seeds; s++) {
                        rs.add(new BotHealArena(arm, s, mobId, density, pots).run(ms));
                    }
                    byArm.put(arm, rs);
                    System.out.printf("%d\t%d/%s\t%s\t%d\t%.2f\t%.1f\t%.0f\t%.0f\t%.0f\t%.2f\t%.0f%n", mobId, density,
                            sup, arm, seeds, mean(rs, r -> r.deaths() / h), mean(rs, BotHealArenaCli::under30),
                            mean(rs, r -> r.exp() / h), mean(rs, r -> r.hpPots() / h), mean(rs, r -> r.mpPots() / h),
                            mean(rs, r -> r.resupplies() / h), mean(rs, r -> r.heals() / h));
                }
                for (BotHealArena.Arm[] pair : new BotHealArena.Arm[][]{
                        {BotHealArena.Arm.B_RESPONSIBILITY, BotHealArena.Arm.A2_REACTIVE},
                        {BotHealArena.Arm.B2_EAGER, BotHealArena.Arm.A2_REACTIVE},
                        {BotHealArena.Arm.A2_REACTIVE, BotHealArena.Arm.A1_COMMAND}}) {
                    List<BotHealArena.Result> b = byArm.get(pair[0]), a = byArm.get(pair[1]);
                    String base = pair[0] + "-" + pair[1];
                    System.out.printf("diff\t%d/%s\t%s\t%d\tdeaths/h %s\tunder30 s/fieldh %s\texp/h %s"
                                    + "\thpPots/h %s\tmpPots/h %s\tresupplies/h %s%n", density, sup, base, seeds,
                            paired(b, a, r -> r.deaths() / h), paired(b, a, BotHealArenaCli::under30),
                            paired(b, a, r -> r.exp() / h), paired(b, a, r -> r.hpPots() / h),
                            paired(b, a, r -> r.mpPots() / h), paired(b, a, r -> r.resupplies() / h));
                }
            }
        }
    }

    /** Seconds under 30% HP per member hour on the field. */
    private static double under30(BotHealArena.Result r) {
        return r.fieldSeconds() <= 0 ? 0 : r.secondsUnder30() / (r.fieldSeconds() / 3600.0);
    }

    private static double mean(List<BotHealArena.Result> rs, ToDoubleFunction<BotHealArena.Result> f) {
        return rs.stream().mapToDouble(f).average().orElse(0);
    }

    /** Mean paired difference and its 95% t interval over seeds. */
    private static String paired(List<BotHealArena.Result> b, List<BotHealArena.Result> a,
                                 ToDoubleFunction<BotHealArena.Result> f) {
        int n = b.size();
        double[] d = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            d[i] = f.applyAsDouble(b.get(i)) - f.applyAsDouble(a.get(i));
            sum += d[i];
        }
        double mean = sum / n, ss = 0;
        for (double x : d) {
            ss += (x - mean) * (x - mean);
        }
        double half = n > 1 ? t975(n - 1) * Math.sqrt(ss / (n - 1) / n) : 0;
        return String.format("%+.2f [%+.2f, %+.2f]", mean, mean - half, mean + half);
    }

    /** Student t 0.975 quantile (Cornish Fisher expansion; 2.093 at 19 degrees of freedom). */
    static double t975(int df) {
        double z = 1.959964, z3 = z * z * z, z5 = z3 * z * z;
        return z + (z3 + z) / (4.0 * df) + (5 * z5 + 16 * z3 + 3 * z) / (96.0 * df * df);
    }
}
