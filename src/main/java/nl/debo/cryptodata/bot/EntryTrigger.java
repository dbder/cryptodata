package nl.debo.cryptodata.bot;

import nl.debo.cryptodata.tools.Kline;

import java.util.Optional;

/**
 * Step 3 of the process: confirmation inside the zone. Implementations look
 * at the candles up to index {@code i} (the candle that just closed) and
 * decide whether a long fires there. The proxy version reads OHLCV shapes;
 * once enough trade-tape has been collected ({@link TradeTapeCollector},
 * {@link CandleDelta}) a delta-based implementation can replace it without
 * touching the strategy loop.
 */
@FunctionalInterface
public interface EntryTrigger {

    /** A fired long: entry at the trigger candle's close, stop under the failed sellers. */
    record Entry(double entryPrice, double stopPrice) {
    }

    Optional<Entry> check(java.util.List<Kline> candles, int i, LocationFinder.DiscountZone zone);
}
