package nl.debo.cryptodata.bot;

import nl.debo.cryptodata.tools.BitvavoClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Per-candle order-flow delta aggregated from a {@link TradeTapeCollector}
 * CSV: taker buys minus taker sells per candle, the number a footprint chart
 * shows at the bottom of each bar. This is the input for a future delta-based
 * {@link EntryTrigger} — real absorption detection instead of the OHLCV
 * proxy — once enough tape exists to make a backtest meaningful.
 */
public record CandleDelta(long openTime, double buyVolume, double sellVolume) {

    /** Taker buys minus taker sells; negative = aggressive sellers dominated. */
    public double delta() {
        return buyVolume - sellVolume;
    }

    /**
     * Aggregates a tape CSV into candles of {@code interval} (e.g. "1h"),
     * oldest first. Rows are deduplicated by trade id — the collector can in
     * rare cases save a trade twice around a restart.
     */
    public static List<CandleDelta> aggregate(Path tapeCsv, String interval) throws IOException {
        long intervalMillis = BitvavoClient.closeTime(0, interval) + 1;
        var byCandle = new TreeMap<Long, double[]>();   // openTime -> [buy, sell]
        var seenIds = new HashSet<String>();

        try (var lines = Files.lines(tapeCsv)) {
            lines.skip(1).filter(line -> !line.isBlank()).forEach(line -> {
                String[] parts = line.split(",");
                if (!seenIds.add(parts[1])) {
                    return;
                }
                long timestamp = Long.parseLong(parts[0]);
                double amount = Double.parseDouble(parts[3]);
                long openTime = timestamp - timestamp % intervalMillis;
                double[] volumes = byCandle.computeIfAbsent(openTime, k -> new double[2]);
                volumes[parts[4].equals("buy") ? 0 : 1] += amount;
            });
        }

        var result = new ArrayList<CandleDelta>(byCandle.size());
        byCandle.forEach((openTime, volumes) -> result.add(new CandleDelta(openTime, volumes[0], volumes[1])));
        return result;
    }
}
