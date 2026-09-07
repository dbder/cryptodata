package nl.debo.cryptodata.bot;

import nl.debo.cryptodata.tools.Kline;

import java.util.List;
import java.util.Optional;

/**
 * Default {@link LocationFinder}: the video's fib pullback zone. Takes the
 * most recent swing-low → swing-high leg (so there is internal structure by
 * construction: both ends are real swing points), draws the 0.705–0.886
 * retracement band, and only accepts it when the band sits entirely below the
 * value area — "if the fib is sitting inside value area, I don't want it."
 */
public final class FibDiscountFinder implements LocationFinder {

    private static final double FIB_TOP = 0.705;
    private static final double FIB_BOTTOM = 0.886;
    /** A leg has to be worth trading: swing high at least this far above the swing low. */
    private static final double MIN_LEG_FRACTION = 0.02;

    @Override
    public Optional<DiscountZone> find(List<Kline> candles, VolumeProfile profile) {
        List<Integer> lows = SwingStructureClassifier.swingIndices(candles, false);
        List<Integer> highs = SwingStructureClassifier.swingIndices(candles, true);
        if (lows.isEmpty() || highs.isEmpty()) {
            return Optional.empty();
        }

        // The leg: latest swing high, paired with the last swing low before it.
        int highIdx = highs.get(highs.size() - 1);
        int lowIdx = -1;
        for (int i : lows) {
            if (i < highIdx) lowIdx = i;
        }
        if (lowIdx < 0) {
            return Optional.empty();
        }

        double swingLow = candles.get(lowIdx).low();
        double swingHigh = candles.get(highIdx).high();
        double leg = swingHigh - swingLow;
        if (leg < swingLow * MIN_LEG_FRACTION) {
            return Optional.empty();
        }

        var zone = new DiscountZone(
                swingHigh - FIB_TOP * leg,
                swingHigh - FIB_BOTTOM * leg,
                swingLow,
                swingHigh);
        // Outside (below) the value area, or it means nothing.
        if (zone.top() >= profile.valueAreaLow()) {
            return Optional.empty();
        }
        return Optional.of(zone);
    }
}
