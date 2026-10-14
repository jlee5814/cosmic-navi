package server.bots;

import client.SkillFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs {@link BotHealArena} for every arm on the same seeds and prints per hour rates with paired differences.
 * usage: BotHealArenaCli &lt;mobId&gt; &lt;mobsPer1000Px,...&gt; &lt;seeds&gt; &lt;minutes&gt; &lt;potSupply,...&gt; (-1 unlimited)
 */
public final class BotHealArenaCli {
    public static void main(String[] args) {
        int mobId = Integer.parseInt(args[0]);
        String[] densities = args[1].split(",");
        int seeds = Integer.parseInt(args[2]);
        long ms = Long.parseLong(args[3]) * 60_000L;
        String[] supplies = args.length > 4 ? args[4].split(",") : new String[]{"-1"};
        SkillFactory.loadAllSkills();
        System.out.println("mob\tdensity/pots\tarm\tseeds\tdeaths/h\tunder30 s/h\texp/h\thpPots/h\tmpPots/h\theals/h");
        for (String sup : supplies)
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
                double h = ms / 3_600_000.0;
                System.out.printf("%d\t%d/%s\t%s\t%d\t%.2f\t%.0f\t%.0f\t%.0f\t%.0f\t%.0f%n", mobId, density, sup, arm, seeds,
                        mean(rs, r -> r.deaths()) / h, mean(rs, BotHealArena.Result::secondsUnder30) / h,
                        mean(rs, r -> r.exp()) / h, mean(rs, r -> r.hpPots()) / h, mean(rs, r -> r.mpPots()) / h,
                        mean(rs, r -> r.heals()) / h);
            }
            for (BotHealArena.Arm base : List.of(BotHealArena.Arm.A1_COMMAND, BotHealArena.Arm.A2_REACTIVE)) {
                List<BotHealArena.Result> b = byArm.get(BotHealArena.Arm.B_RESPONSIBILITY), a = byArm.get(base);
                double h = ms / 3_600_000.0;
                System.out.printf("diff\t%d/%s\tB-%s\t%d\tdeaths/h %s\tunder30 s/h %s\texp/h %s\thpPots/h %s\tmpPots/h %s%n",
                        density, sup, base, seeds,
                        paired(b, a, r -> r.deaths() / h), paired(b, a, r -> r.secondsUnder30() / h),
                        paired(b, a, r -> r.exp() / h), paired(b, a, r -> r.hpPots() / h),
                        paired(b, a, r -> r.mpPots() / h));
            }
        }
    }

    private static double mean(List<BotHealArena.Result> rs, java.util.function.ToDoubleFunction<BotHealArena.Result> f) {
        return rs.stream().mapToDouble(f).average().orElse(0);
    }

    /** Mean paired difference and its 95% interval (normal approximation over seeds). */
    private static String paired(List<BotHealArena.Result> b, List<BotHealArena.Result> a,
                                 java.util.function.ToDoubleFunction<BotHealArena.Result> f) {
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
        double half = n > 1 ? 1.96 * Math.sqrt(ss / (n - 1) / n) : 0;
        return String.format("%+.1f [%+.1f, %+.1f]", mean, mean - half, mean + half);
    }
}
