package nl.debo.cryptodata.bot;

import nl.debo.cryptodata.tools.Kline;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The full process from docs/orderflow-strategy/, walked candle by candle:
 * environment (structure UP required) → location (fib discount zone below
 * value area) → confirmation (entry trigger) → management (stop under failed
 * sellers, target at the swing high, break-even at +1R). Long-only, one
 * position at a time, like the video's worked example.
 *
 * <p>Deterministic over a candle window, so the backtest and the paper bot
 * share this loop: the paper bot just re-runs it on a fresh window and diffs
 * the trades. Exits are judged intrabar with the stop taking priority when a
 * candle hits both — the conservative reading.
 *
 * <p>Discipline is part of the strategy, not an afterthought (the video's
 * central claim): a participation filter, a trade cap per day, and a
 * stop-after-losses tripwire are all enforced here.
 */
public final class OrderflowStrategy {

    /** Tunables, grouped so backtest and paper runs can't drift apart. */
    public record Config(
            int structureCandles,        // higher-TF candles handed to the environment classifier
            int profileWindow,           // execution candles in the volume profile
            int swingLookback,           // execution candles the location finder sees
            double minRewardRisk,        // entry skipped when target is closer than this many R
            int maxTradesPerDay,
            int stopAfterLossesInRow,    // per UTC day, the "stop after two losses" tripwire
            double minVolumePercentile,  // participation filter, the 20k-contracts stand-in
            double stakeEur,
            double fee                   // per side, like the webapp simulation
    ) {
        public static Config defaults() {
            return new Config(90, 120, 100, 1.0, 2, 2, 0.30, 100, 0.0025);
        }
    }

    /**
     * One completed or open trade; {@code exitTime == 0} while still open.
     * {@code stop} is the live stop (it moves to break-even at +1R);
     * {@code initialStop} stays at entry so R multiples keep their meaning.
     */
    public record Trade(
            long entryTime, double entry, double stop, double initialStop, double target,
            long exitTime, double exit, String reason,
            double rMultiple, double eur, Environment environment
    ) {
        public boolean open() {
            return exitTime == 0;
        }
    }

    public record Result(List<Trade> trades, Optional<Trade> openTrade) {
    }

    private final Config config;
    private final EnvironmentClassifier environmentClassifier;
    private final LocationFinder locationFinder;
    private final EntryTrigger entryTrigger;

    public OrderflowStrategy(Config config) {
        this(config, new SwingStructureClassifier(), new FibDiscountFinder(), new AbsorptionProxyTrigger());
    }

    public OrderflowStrategy(Config config, EnvironmentClassifier environmentClassifier,
                             LocationFinder locationFinder, EntryTrigger entryTrigger) {
        this.config = config;
        this.environmentClassifier = environmentClassifier;
        this.locationFinder = locationFinder;
        this.entryTrigger = entryTrigger;
    }

    /**
     * Runs the process over the windows (both oldest first). Higher-timeframe
     * candles are only consulted up to the execution candle being processed,
     * so a backtest sees exactly what a live run would have seen.
     */
    public Result run(List<Kline> execution, List<Kline> higherTimeframe) {
        var trades = new ArrayList<Trade>();
        Trade open = null;
        int higherUpTo = 0;                    // higher-TF candles closed before "now"
        LocalDate day = null;
        int tradesToday = 0;
        int lossesInRowToday = 0;
        int warmup = Math.max(config.profileWindow(), config.swingLookback());

        for (int i = warmup; i < execution.size(); i++) {
            Kline candle = execution.get(i);

            LocalDate candleDay = Instant.ofEpochMilli(candle.openTime()).atZone(ZoneOffset.UTC).toLocalDate();
            if (!candleDay.equals(day)) {
                day = candleDay;
                tradesToday = 0;
                lossesInRowToday = 0;
            }

            if (open != null) {
                Trade closed = manage(open, candle);
                if (closed != null) {
                    trades.add(closed);
                    lossesInRowToday = closed.eur() < 0 ? lossesInRowToday + 1 : 0;
                    open = null;
                } else if (candle.high() >= open.entry() + (open.entry() - open.stop())) {
                    // +1R reached: stop to break-even, the trail-behind-aggression proxy.
                    open = withStop(open, Math.max(open.stop(), open.entry()));
                }
                continue;   // one position at a time; no stacking entries
            }

            if (tradesToday >= config.maxTradesPerDay()) continue;
            if (lossesInRowToday >= config.stopAfterLossesInRow()) continue;
            if (!enoughParticipation(execution, i)) continue;

            while (higherUpTo < higherTimeframe.size()
                    && higherTimeframe.get(higherUpTo).closeTime() < candle.openTime()) {
                higherUpTo++;
            }
            List<Kline> higher = higherTimeframe.subList(
                    Math.max(0, higherUpTo - config.structureCandles()), higherUpTo);
            Environment environment = environmentClassifier.classify(higher);
            if (environment.structure() != Environment.Structure.UP) continue;

            VolumeProfile profile = VolumeProfile.of(execution.subList(i - config.profileWindow(), i));
            Optional<LocationFinder.DiscountZone> zone = locationFinder.find(
                    execution.subList(Math.max(0, i - config.swingLookback()), i + 1), profile);
            if (zone.isEmpty()) continue;
            if (candle.close() < zone.get().bottom()) continue;   // below the 0.886: idea invalidated

            Optional<EntryTrigger.Entry> entry = entryTrigger.check(execution, i, zone.get());
            if (entry.isEmpty()) continue;

            double risk = entry.get().entryPrice() - entry.get().stopPrice();
            double reward = zone.get().swingHigh() - entry.get().entryPrice();
            if (risk <= 0 || reward / risk < config.minRewardRisk()) continue;

            open = new Trade(candle.openTime(), entry.get().entryPrice(),
                    entry.get().stopPrice(), entry.get().stopPrice(),
                    zone.get().swingHigh(), 0, 0, null, 0, 0, environment);
            tradesToday++;
        }

        return new Result(trades, Optional.ofNullable(open));
    }

    /** The closed trade when this candle takes the stop or target, else null. */
    private Trade manage(Trade open, Kline candle) {
        boolean stopped = candle.low() <= open.stop();
        boolean targeted = candle.high() >= open.target();
        if (!stopped && !targeted) {
            return null;
        }
        double exit = stopped ? open.stop() : open.target();   // stop wins when a candle hits both
        String reason = stopped ? (open.stop() >= open.entry() ? "break-even" : "stop") : "target";
        double r = (exit - open.entry()) / (open.entry() - open.initialStop());
        double eur = resultEur(open.entry(), exit);
        return new Trade(open.entryTime(), open.entry(), open.stop(), open.initialStop(), open.target(),
                candle.closeTime(), exit, reason, r, eur, open.environment());
    }

    private double resultEur(double entry, double exit) {
        double bought = config.stakeEur() * (1 - config.fee());
        return bought * (exit / entry) * (1 - config.fee()) - config.stakeEur();
    }

    private static Trade withStop(Trade t, double stop) {
        return new Trade(t.entryTime(), t.entry(), stop, t.initialStop(), t.target(), 0, 0, null, 0, 0, t.environment());
    }

    /** Volume percentile of this candle against the profile window before it. */
    private boolean enoughParticipation(List<Kline> execution, int i) {
        int from = i - config.profileWindow();
        int below = 0;
        for (int j = from; j < i; j++) {
            if (execution.get(j).volume() < execution.get(i).volume()) below++;
        }
        return (double) below / config.profileWindow() >= config.minVolumePercentile();
    }
}
