package nl.debo.cryptodata.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * An interval the exchange does not offer itself, built by merging a fixed
 * number of consecutive candles of a base interval: {@code 2d} is every two
 * {@code 1d} candles taken together.
 *
 * <p>A merged candle opens with the first candle of its group and closes
 * with the last; its high and low span the group and its volume is the sum.
 * Groups are counted back from the newest <em>closed</em> base candle, so
 * the newest derived candle is always complete and as fresh as the base
 * series (a still-open base candle is dropped first, an incomplete group at
 * the old end of the series is dropped too). This does mean the grouping
 * shifts by one base candle from one day to the next; a derived series is a
 * snapshot "as of now", not a fixed calendar grid.</p>
 *
 * @param name         the derived interval's own name, e.g. {@code "2d"}
 * @param baseInterval the exchange interval to build from, e.g. {@code "1d"}
 * @param factor       how many base candles make one derived candle
 */
public record DerivedInterval(String name, String baseInterval, int factor) {

    public DerivedInterval {
        if (factor < 2) {
            throw new IllegalArgumentException("factor must be at least 2, got " + factor);
        }
    }

    /** {@code 2d}: two daily candles per candle. */
    public static DerivedInterval twoDays() {
        return new DerivedInterval("2d", "1d", 2);
    }

    /**
     * Merges {@code base} (oldest first) into derived candles, oldest first.
     * Base candles that have not closed yet are left out before grouping.
     */
    public List<Kline> derive(List<Kline> base) {
        return derive(base, System.currentTimeMillis());
    }

    List<Kline> derive(List<Kline> base, long now) {
        List<Kline> closed = base.stream()
                .filter(k -> k.closeTime() <= now)
                .toList();

        var derived = new ArrayList<Kline>(closed.size() / factor);
        // Skip the incomplete group at the old end so groups line up on the
        // newest candle.
        for (int start = closed.size() % factor; start + factor <= closed.size(); start += factor) {
            derived.add(merge(closed.subList(start, start + factor)));
        }
        return derived;
    }

    private static Kline merge(List<Kline> group) {
        Kline first = group.getFirst();
        Kline last = group.getLast();
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        double volume = 0;
        for (Kline k : group) {
            high = Math.max(high, k.high());
            low = Math.min(low, k.low());
            volume += k.volume();
        }
        return new Kline(first.openTime(), first.open(), high, low, last.close(), volume, last.closeTime());
    }
}
