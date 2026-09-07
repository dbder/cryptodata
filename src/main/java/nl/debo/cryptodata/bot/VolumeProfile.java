package nl.debo.cryptodata.bot;

import nl.debo.cryptodata.tools.Kline;

import java.util.List;

/**
 * Volume-by-price profile approximated from OHLCV candles: each candle's
 * volume is spread evenly over the price buckets its high–low range covers.
 * The value area is grown outward from the POC bucket until it holds 70% of
 * the window's volume, the standard market-profile convention the strategy's
 * premium/discount split is built on (docs/orderflow-strategy/02-location.md).
 */
public record VolumeProfile(double poc, double valueAreaLow, double valueAreaHigh) {

    private static final int BUCKETS = 50;
    private static final double VALUE_AREA_FRACTION = 0.70;

    public static VolumeProfile of(List<Kline> window) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Kline c : window) {
            min = Math.min(min, c.low());
            max = Math.max(max, c.high());
        }
        double bucketSize = (max - min) / BUCKETS;
        if (bucketSize <= 0) {   // flat window (e.g. a stablecoin): everything is the same price
            return new VolumeProfile(min, min, max);
        }

        var volume = new double[BUCKETS];
        for (Kline c : window) {
            int from = bucketOf(c.low(), min, bucketSize);
            int to = bucketOf(c.high(), min, bucketSize);
            double perBucket = c.volume() / (to - from + 1);
            for (int b = from; b <= to; b++) {
                volume[b] += perBucket;
            }
        }

        int poc = 0;
        double total = 0;
        for (int b = 0; b < BUCKETS; b++) {
            total += volume[b];
            if (volume[b] > volume[poc]) poc = b;
        }

        // Grow the value area around the POC, always absorbing the heavier neighbor.
        int lo = poc;
        int hi = poc;
        double inArea = volume[poc];
        while (inArea < total * VALUE_AREA_FRACTION && (lo > 0 || hi < BUCKETS - 1)) {
            double below = lo > 0 ? volume[lo - 1] : -1;
            double above = hi < BUCKETS - 1 ? volume[hi + 1] : -1;
            if (below >= above) {
                inArea += volume[--lo];
            } else {
                inArea += volume[++hi];
            }
        }

        return new VolumeProfile(
                min + (poc + 0.5) * bucketSize,
                min + lo * bucketSize,
                min + (hi + 1) * bucketSize);
    }

    private static int bucketOf(double price, double min, double bucketSize) {
        return Math.clamp((int) ((price - min) / bucketSize), 0, BUCKETS - 1);
    }
}
