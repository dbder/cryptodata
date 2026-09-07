package nl.debo.cryptodata.bot;

import nl.debo.cryptodata.tools.Kline;

import java.util.List;
import java.util.Optional;

/**
 * Candle-only stand-in for the video's footprint confirmation
 * (docs/orderflow-strategy/03-confirmation.md). Without bid/ask data the
 * absorption + "second attempt fails higher" sequence is approximated as:
 *
 * <ol>
 *   <li><b>Absorption candidate</b> — a candle probes the zone and rejects it:
 *       its low is in the zone and it closes in the upper part of its range
 *       (a long lower wick — sellers were aggressive at the extreme and got
 *       no result).</li>
 *   <li><b>Second failure, higher</b> — within the next few candles, another
 *       push down bottoms <em>above</em> the candidate's low and closes
 *       bullish. That close is the trigger.</li>
 * </ol>
 *
 * The stop goes under the candidate's low — "the other side of the failed
 * sellers" — because sellers pushing past the level they failed at twice is
 * exactly what invalidates the idea.
 */
public final class AbsorptionProxyTrigger implements EntryTrigger {

    /** Candidate must close above this fraction of its own range (0.5 = upper half). */
    private static final double MIN_CLOSE_POSITION = 0.5;
    /** How many candles after the candidate the second test may come. */
    private static final int SECOND_TEST_WINDOW = 5;

    @Override
    public Optional<Entry> check(List<Kline> candles, int i, LocationFinder.DiscountZone zone) {
        Kline trigger = candles.get(i);
        if (trigger.close() <= trigger.open()) {
            return Optional.empty();
        }
        // Walk back looking for the absorption candidate this candle confirms.
        int from = Math.max(0, i - SECOND_TEST_WINDOW);
        for (int a = i - 1; a >= from; a--) {
            Kline cand = candles.get(a);
            if (!isAbsorption(cand, zone)) continue;
            if (trigger.low() <= cand.low()) continue;   // second attempt must fail HIGHER
            if (belowBetween(candles, a, i, cand.low())) continue;
            return Optional.of(new Entry(trigger.close(), cand.low()));
        }
        return Optional.empty();
    }

    private static boolean isAbsorption(Kline c, LocationFinder.DiscountZone zone) {
        double range = c.high() - c.low();
        if (range <= 0) return false;
        double closePosition = (c.close() - c.low()) / range;
        return zone.contains(c.low()) && closePosition >= MIN_CLOSE_POSITION;
    }

    /** True when any candle strictly between the two dipped under the candidate's low. */
    private static boolean belowBetween(List<Kline> candles, int a, int i, double low) {
        for (int j = a + 1; j < i; j++) {
            if (candles.get(j).low() <= low) return true;
        }
        return false;
    }
}
