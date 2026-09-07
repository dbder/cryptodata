package nl.debo.cryptodata.bot;

import nl.debo.cryptodata.tools.Kline;
import nl.debo.cryptodata.utils.KlineCsvStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * One-factor experiments on {@link OrderflowStrategy}, judged with a time
 * split so a variant has to hold up on data it was not tuned on: trades
 * entered before {@code CUTOFF} are in-sample (IS), after it out-of-sample
 * (OOS). A variant only counts as an improvement when it beats the baseline
 * on BOTH sides — an IS-only win is curve fitting, not edge.
 *
 * <p>Also prints where the baseline's results actually come from (per exit
 * reason, per volatility regime, per liquidity bucket) — the diagnosis that
 * says which knob is worth turning before any knob is turned.
 */
public final class OrderflowExperiment {

    private static final Path KLINES_DIR = Path.of("output", "klines-bitvavo");
    private static final Instant CUTOFF = Instant.parse("2025-09-01T00:00:00Z");
    /** Rough liquidity bar: average daily traded value over the last year, in EUR. */
    private static final double LIQUID_EUR_PER_DAY = 500_000;

    private record MarketData(String market, List<Kline> execution, List<Kline> structure, boolean liquid) {
    }

    public static void main(String[] args) throws Exception {
        List<MarketData> data = loadAll();
        long liquid = data.stream().filter(MarketData::liquid).count();
        System.out.printf("%d markets (%d liquid ≥ €%,.0f/day) · IS < %s ≤ OOS%n%n",
                data.size(), liquid, LIQUID_EUR_PER_DAY, CUTOFF);

        var defaults = OrderflowStrategy.Config.defaults();
        record Variant(String name, OrderflowStrategy strategy, boolean liquidOnly) {
        }
        List<Variant> variants = List.of(
                new Variant("baseline", new OrderflowStrategy(defaults), false),
                new Variant("liquid markets only", new OrderflowStrategy(defaults), true),
                new Variant("regime CALM only", new OrderflowStrategy(defaults.withRequiredRegime(Environment.Regime.CALM)), false),
                new Variant("regime EXPLOSIVE only", new OrderflowStrategy(defaults.withRequiredRegime(Environment.Regime.EXPLOSIVE)), false),
                new Variant("minRR 1.5", new OrderflowStrategy(defaults.withMinRewardRisk(1.5)), false),
                new Variant("minRR 2.0", new OrderflowStrategy(defaults.withMinRewardRisk(2.0)), false),
                new Variant("stricter trigger (close ≥ 0.65)", strategyWithTrigger(defaults, 0.65), false),
                new Variant("volume filter ≥ p50", new OrderflowStrategy(defaults.withMinVolumePercentile(0.50)), false),
                new Variant("no break-even move", new OrderflowStrategy(defaults.withMoveToBreakEven(false)), false),
                new Variant("profile window 240", new OrderflowStrategy(defaults.withProfileWindow(240)), false),
                new Variant("fee 0.15% (maker)", new OrderflowStrategy(defaults.withFee(0.0015)), false),
                new Variant("fee 0 (gross edge check)", new OrderflowStrategy(defaults.withFee(0)), false)
        );

        System.out.printf("%-32s %28s %28s%n", "", "── in-sample ──", "── out-of-sample ──");
        System.out.printf("%-32s %7s %6s %6s %7s %7s %6s %6s %7s%n",
                "variant", "trades", "win%", "PF", "eur", "trades", "win%", "PF", "eur");
        Map<String, List<OrderflowStrategy.Trade>> baselineTrades = new ConcurrentHashMap<>();
        for (Variant v : variants) {
            var trades = new ConcurrentHashMap<String, List<OrderflowStrategy.Trade>>();
            data.parallelStream()
                    .filter(m -> !v.liquidOnly() || m.liquid())
                    .forEach(m -> trades.put(m.market(), v.strategy().run(m.execution(), m.structure()).trades()));
            if (v.name().equals("baseline")) {
                baselineTrades.putAll(trades);
            }
            List<OrderflowStrategy.Trade> all = trades.values().stream().flatMap(List::stream).toList();
            System.out.printf("%-32s %s %s%n", v.name(),
                    stats(all, t -> t.entryTime() < CUTOFF.toEpochMilli()),
                    stats(all, t -> t.entryTime() >= CUTOFF.toEpochMilli()));
        }

        diagnose(data, baselineTrades);
        higherTimeframes(defaults);
    }

    /**
     * The structural experiment the fee diagnosis points at: bigger moves per
     * trade by executing on 4h (structure 1d) and 1d (structure 1W) candles,
     * with the two knobs the 1h round favored (no break-even move, EXPLOSIVE
     * regime), alone and combined.
     */
    private static void higherTimeframes(OrderflowStrategy.Config defaults) throws Exception {
        record Combo(String name, OrderflowStrategy.Config config) {
        }
        List<Combo> combos = List.of(
                new Combo("baseline", defaults),
                new Combo("no break-even move", defaults.withMoveToBreakEven(false)),
                new Combo("regime EXPLOSIVE only", defaults.withRequiredRegime(Environment.Regime.EXPLOSIVE)),
                new Combo("no-BE + EXPLOSIVE", defaults.withMoveToBreakEven(false).withRequiredRegime(Environment.Regime.EXPLOSIVE))
        );
        for (String[] tf : List.of(new String[]{"4h", "1d"}, new String[]{"1d", "1W"})) {
            List<MarketData> data = loadAll(tf[0], tf[1]);
            System.out.printf("%n%s execution / %s structure (%d markets):%n", tf[0], tf[1], data.size());
            for (Combo combo : combos) {
                var strategy = new OrderflowStrategy(combo.config());
                List<OrderflowStrategy.Trade> all = data.parallelStream()
                        .flatMap(m -> strategy.run(m.execution(), m.structure()).trades().stream())
                        .toList();
                System.out.printf("%-32s %s %s%n", combo.name(),
                        stats(all, t -> t.entryTime() < CUTOFF.toEpochMilli()),
                        stats(all, t -> t.entryTime() >= CUTOFF.toEpochMilli()));
            }
        }
    }

    private static OrderflowStrategy strategyWithTrigger(OrderflowStrategy.Config config, double minClosePosition) {
        return new OrderflowStrategy(config, new SwingStructureClassifier(), new FibDiscountFinder(),
                new AbsorptionProxyTrigger(minClosePosition, 5));
    }

    private static String stats(List<OrderflowStrategy.Trade> all, java.util.function.Predicate<OrderflowStrategy.Trade> in) {
        List<OrderflowStrategy.Trade> trades = all.stream().filter(in).toList();
        if (trades.isEmpty()) {
            return String.format("%7d %6s %6s %7s", 0, "-", "-", "-");
        }
        long wins = trades.stream().filter(t -> t.eur() > 0).count();
        double win = trades.stream().filter(t -> t.eur() > 0).mapToDouble(OrderflowStrategy.Trade::eur).sum();
        double loss = -trades.stream().filter(t -> t.eur() <= 0).mapToDouble(OrderflowStrategy.Trade::eur).sum();
        double eur = trades.stream().mapToDouble(OrderflowStrategy.Trade::eur).sum();
        return String.format("%7d %5.0f%% %6.2f %7.0f",
                trades.size(), 100.0 * wins / trades.size(), loss > 0 ? win / loss : 99.99, eur);
    }

    /** Where the baseline's money actually goes: exit reasons, regimes, liquidity. */
    private static void diagnose(List<MarketData> data, Map<String, List<OrderflowStrategy.Trade>> byMarket) {
        var liquidMarkets = data.stream().filter(MarketData::liquid).map(MarketData::market).collect(java.util.stream.Collectors.toSet());
        List<OrderflowStrategy.Trade> all = byMarket.values().stream().flatMap(List::stream).toList();

        System.out.println("\nbaseline segmented:");
        segment(all, "exit=target", t -> t.reason().equals("target"));
        segment(all, "exit=stop", t -> t.reason().equals("stop"));
        segment(all, "exit=break-even", t -> t.reason().equals("break-even"));
        segment(all, "regime CALM at entry", t -> t.environment().regime() == Environment.Regime.CALM);
        segment(all, "regime EXPLOSIVE at entry", t -> t.environment().regime() == Environment.Regime.EXPLOSIVE);
        List<OrderflowStrategy.Trade> liquidTrades = byMarket.entrySet().stream()
                .filter(e -> liquidMarkets.contains(e.getKey()))
                .flatMap(e -> e.getValue().stream()).toList();
        segment(liquidTrades, "liquid markets", t -> true);
    }

    private static void segment(List<OrderflowStrategy.Trade> all, String label, java.util.function.Predicate<OrderflowStrategy.Trade> p) {
        List<OrderflowStrategy.Trade> trades = all.stream().filter(p).toList();
        double eur = trades.stream().mapToDouble(OrderflowStrategy.Trade::eur).sum();
        System.out.printf("  %-26s %5d trades %8.0f eur %s%n", label, trades.size(), eur,
                trades.isEmpty() ? "" : String.format("(avg %+.2f eur)", eur / trades.size()));
    }

    private static List<MarketData> loadAll() throws Exception {
        return loadAll("1h", "1d");
    }

    private static List<MarketData> loadAll(String executionInterval, String structureInterval) throws Exception {
        List<String> markets;
        try (Stream<Path> files = Files.list(KLINES_DIR)) {
            markets = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("_" + executionInterval + ".csv"))
                    .map(n -> n.substring(0, n.length() - ("_" + executionInterval + ".csv").length()))
                    .filter(m -> Files.exists(KLINES_DIR.resolve(m + "_" + structureInterval + ".csv")))
                    .sorted().toList();
        }
        return markets.parallelStream().map(market -> {
            try {
                List<Kline> execution = KlineCsvStore.readKlines(KLINES_DIR.resolve(market + "_" + executionInterval + ".csv"));
                List<Kline> structure = KlineCsvStore.readKlines(KLINES_DIR.resolve(market + "_" + structureInterval + ".csv"));
                List<Kline> daily = structureInterval.equals("1d") ? structure
                        : KlineCsvStore.readKlines(KLINES_DIR.resolve(market + "_1d.csv"));
                return new MarketData(market, execution, structure, isLiquid(daily));
            } catch (Exception e) {
                throw new RuntimeException(market, e);
            }
        }).toList();
    }

    /** Average close×volume per day over the last 365 daily candles. */
    private static boolean isLiquid(List<Kline> daily) {
        List<Kline> lastYear = daily.subList(Math.max(0, daily.size() - 365), daily.size());
        if (lastYear.isEmpty()) return false;
        double avg = lastYear.stream().mapToDouble(k -> k.close() * k.volume()).average().orElse(0);
        return avg >= LIQUID_EUR_PER_DAY;
    }
}
