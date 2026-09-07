package nl.debo.cryptodata.bot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/**
 * Records the public trade tape to CSV so a real delta layer can exist later:
 * Bitvavo only serves recent trades, so per-candle buy/sell volume for a
 * backtest is only available from the moment this collector starts running.
 * Leave it running (it is idempotent across restarts — it resumes after the
 * last saved timestamp) and aggregate with {@link CandleDelta} once weeks of
 * tape have accumulated.
 *
 * <p>Output: {@code output/trade-tape/{market}.csv} with
 * {@code timestamp,id,price,amount,side}, oldest first.
 */
public final class TradeTapeCollector {

    private static final List<String> MARKETS = List.of("BTC-EUR", "ETH-EUR", "SOL-EUR", "XRP-EUR");
    private static final Path TAPE_DIR = Path.of("output", "trade-tape");
    private static final String HEADER = "timestamp,id,price,amount,side";
    private static final long POLL_MILLIS = 60_000;
    private static final int MAX_PAGES = 20;

    public static void main(String[] args) throws Exception {
        var tape = new BitvavoTradeTape();
        Files.createDirectories(TAPE_DIR);
        System.out.println("collecting trade tape for " + MARKETS + " -> " + TAPE_DIR + " (Ctrl+C to stop)");

        while (true) {
            for (String market : MARKETS) {
                try {
                    collect(tape, market);
                } catch (Exception e) {
                    System.out.println(Instant.now() + " " + market + " collect failed: " + e.getMessage());
                }
            }
            Thread.sleep(POLL_MILLIS);
        }
    }

    private static void collect(BitvavoTradeTape tape, String market) throws Exception {
        Path csv = TAPE_DIR.resolve(market + ".csv");
        if (!Files.exists(csv)) {
            Files.writeString(csv, HEADER + System.lineSeparator());
        }
        long since = lastSavedTimestamp(csv);
        // Cold start: don't crawl the whole tape, begin one poll interval back.
        if (since == 0) {
            since = System.currentTimeMillis() - POLL_MILLIS;
        }

        List<BitvavoTradeTape.TapeTrade> trades = tape.tradesSince(market, since, MAX_PAGES);
        if (trades.isEmpty()) {
            return;
        }
        var sb = new StringBuilder();
        for (var t : trades) {
            sb.append(t.timestamp()).append(',').append(t.id()).append(',')
                    .append(t.price()).append(',').append(t.amount()).append(',')
                    .append(t.side()).append(System.lineSeparator());
        }
        Files.writeString(csv, sb.toString(), StandardOpenOption.APPEND);
        System.out.println(Instant.now() + " " + market + " +" + trades.size() + " trades");
    }

    /** Timestamp of the last saved row, or 0 for a fresh file. */
    private static long lastSavedTimestamp(Path csv) throws IOException {
        try (var lines = Files.lines(csv)) {
            return lines.reduce((a, b) -> b)
                    .filter(last -> !last.equals(HEADER) && !last.isBlank())
                    .map(last -> Long.parseLong(last.substring(0, last.indexOf(','))))
                    .orElse(0L);
        }
    }
}
