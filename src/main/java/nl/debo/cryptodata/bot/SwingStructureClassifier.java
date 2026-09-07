package nl.debo.cryptodata.bot;

import nl.debo.cryptodata.tools.Kline;

import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link EnvironmentClassifier}: basic market structure from swing
 * points ("very basic market structure, higher highs, higher lows" — Chris),
 * and a volatility regime from the ATR-to-price percentile.
 *
 * <p>A swing high is a candle whose high exceeds the {@code SWING_WINDOW}
 * candles on both sides (a fractal); swing lows mirror that. The last two
 * swing highs and lows decide the structure: both rising → UP, both falling
 * → DOWN, anything mixed → SIDEWAYS.
 */
public final class SwingStructureClassifier implements EnvironmentClassifier {

    static final int SWING_WINDOW = 2;
    private static final int ATR_PERIOD = 14;
    private static final int ATR_LOOKBACK = 100;      // percentile window, in candles
    private static final double EXPLOSIVE_PERCENTILE = 0.70;

    @Override
    public Environment classify(List<Kline> candles) {
        return new Environment(structure(candles), regime(candles));
    }

    private static Environment.Structure structure(List<Kline> candles) {
        List<Integer> highs = swingIndices(candles, true);
        List<Integer> lows = swingIndices(candles, false);
        if (highs.size() < 2 || lows.size() < 2) {
            return Environment.Structure.SIDEWAYS;
        }
        Kline h1 = candles.get(highs.get(highs.size() - 2));
        Kline h2 = candles.get(highs.get(highs.size() - 1));
        Kline l1 = candles.get(lows.get(lows.size() - 2));
        Kline l2 = candles.get(lows.get(lows.size() - 1));
        boolean higher = h2.high() > h1.high() && l2.low() > l1.low();
        boolean lower = h2.high() < h1.high() && l2.low() < l1.low();
        return higher ? Environment.Structure.UP
                : lower ? Environment.Structure.DOWN
                : Environment.Structure.SIDEWAYS;
    }

    /** Indices of swing highs ({@code findHighs}) or swing lows, oldest first. */
    static List<Integer> swingIndices(List<Kline> candles, boolean findHighs) {
        var swings = new ArrayList<Integer>();
        for (int i = SWING_WINDOW; i < candles.size() - SWING_WINDOW; i++) {
            boolean isSwing = true;
            for (int j = i - SWING_WINDOW; j <= i + SWING_WINDOW && isSwing; j++) {
                if (j == i) continue;
                isSwing = findHighs
                        ? candles.get(i).high() > candles.get(j).high()
                        : candles.get(i).low() < candles.get(j).low();
            }
            if (isSwing) {
                swings.add(i);
            }
        }
        return swings;
    }

    /**
     * ATR(14) as a fraction of price, ranked against its own trailing history:
     * above the 70th percentile the market is moving explosively (the negative
     * gamma stand-in), otherwise calm.
     */
    private static Environment.Regime regime(List<Kline> candles) {
        double[] atrPct = atrFractions(candles);
        int last = atrPct.length - 1;
        if (last < ATR_PERIOD + 5 || Double.isNaN(atrPct[last])) {
            return Environment.Regime.CALM;
        }
        int from = Math.max(ATR_PERIOD, last - ATR_LOOKBACK);
        int below = 0;
        int total = 0;
        for (int i = from; i < last; i++) {
            if (Double.isNaN(atrPct[i])) continue;
            total++;
            if (atrPct[i] < atrPct[last]) below++;
        }
        boolean explosive = total > 0 && (double) below / total >= EXPLOSIVE_PERCENTILE;
        return explosive ? Environment.Regime.EXPLOSIVE : Environment.Regime.CALM;
    }

    /** Wilder ATR divided by close; NaN during the warmup. */
    private static double[] atrFractions(List<Kline> candles) {
        int n = candles.size();
        var result = new double[n];
        java.util.Arrays.fill(result, Double.NaN);
        if (n <= ATR_PERIOD) {
            return result;
        }
        double atr = 0;
        for (int i = 1; i < n; i++) {
            Kline c = candles.get(i);
            double prevClose = candles.get(i - 1).close();
            double tr = Math.max(c.high() - c.low(),
                    Math.max(Math.abs(c.high() - prevClose), Math.abs(c.low() - prevClose)));
            if (i <= ATR_PERIOD) {
                atr += tr / ATR_PERIOD;
                if (i == ATR_PERIOD) result[i] = atr / c.close();
            } else {
                atr = (atr * (ATR_PERIOD - 1) + tr) / ATR_PERIOD;
                result[i] = atr / c.close();
            }
        }
        return result;
    }
}
