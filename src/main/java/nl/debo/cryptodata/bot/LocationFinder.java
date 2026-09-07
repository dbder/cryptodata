package nl.debo.cryptodata.bot;

import nl.debo.cryptodata.tools.Kline;

import java.util.List;
import java.util.Optional;

/**
 * Step 2 of the process: where is business worth doing? Implementations look
 * at execution-timeframe candles (oldest first, up to "now") plus the current
 * volume profile and either produce a zone to buy in, or nothing.
 */
@FunctionalInterface
public interface LocationFinder {

    /**
     * The discount zone of a long idea. Prices follow the video's fib layout:
     * {@code top} = 0.705 retracement, {@code bottom} = 0.886 — below the
     * bottom the idea is invalidated, not "an even better price". The swing
     * high that anchored the fib doubles as the trade's target ("the market
     * goes and takes swing points").
     */
    record DiscountZone(double top, double bottom, double swingLow, double swingHigh) {

        public boolean contains(double price) {
            return price >= bottom && price <= top;
        }
    }

    Optional<DiscountZone> find(List<Kline> executionTimeframe, VolumeProfile profile);
}
