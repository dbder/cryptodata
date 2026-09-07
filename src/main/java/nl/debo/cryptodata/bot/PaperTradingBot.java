package nl.debo.cryptodata.bot;

import nl.debo.cryptodata.tools.BitvavoClient;
import nl.debo.cryptodata.tools.Kline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Live paper trading: no orders, no money — the strategy runs on fresh
 * Bitvavo candles and every entry and exit it would have taken is printed and
 * appended to {@code output/bot-paper/paper-trades.csv}.
 *
 * <p>Each poll simply re-runs {@link OrderflowStrategy} over a trailing
 * window and diffs the resulting trade list against what was already
 * reported, so there is no separate live state machine that could disagree
 * with the backtest. Trades that already existed at startup are marked as
 * seen silently; only what happens while the bot runs is reported.
 *
 * <p>Runs until stopped. Polls once per closed execution candle (1h), a few
 * seconds after the boundary so the candle is available.
 */
public final class PaperTradingBot {

    private static final List<String> MARKETS = List.of("BTC-EUR", "ETH-EUR", "SOL-EUR", "XRP-EUR");
    private static final String EXECUTION_INTERVAL = "1h";
    private static final String STRUCTURE_INTERVAL = "1d";
    private static final int EXECUTION_CANDLES = 400;
    private static final int STRUCTURE_CANDLES = 120;
    private static final Path OUT_CSV = Path.of("output", "bot-paper", "paper-trades.csv");
    private static final String HEADER = "market,event,time,price,stop,target,reason,rMultiple,eur";

    public static void main(String[] args) throws Exception {
        var client = new BitvavoClient();
        var strategy = new OrderflowStrategy(OrderflowStrategy.Config.defaults());
        Files.createDirectories(OUT_CSV.getParent());
        if (!Files.exists(OUT_CSV)) {
            Files.writeString(OUT_CSV, HEADER + System.lineSeparator());
        }

        Set<String> seen = new HashSet<>();
        boolean firstPoll = true;
        System.out.println("paper trading " + MARKETS + " on " + EXECUTION_INTERVAL
                + " candles -> " + OUT_CSV + " (Ctrl+C to stop)");

        while (true) {
            for (String market : MARKETS) {
                try {
                    poll(client, strategy, market, seen, firstPoll);
                } catch (Exception e) {
                    System.out.println(Instant.now() + " " + market + " poll failed: " + e.getMessage());
                }
            }
            firstPoll = false;
            sleepUntilNextCandle();
        }
    }

    private static void poll(BitvavoClient client, OrderflowStrategy strategy, String market,
                             Set<String> seen, boolean quiet) throws Exception {
        List<Kline> execution = closedOnly(client.getKlinesAsync(market, EXECUTION_INTERVAL, EXECUTION_CANDLES).get());
        List<Kline> structure = closedOnly(client.getKlinesAsync(market, STRUCTURE_INTERVAL, STRUCTURE_CANDLES).get());
        var result = strategy.run(execution, structure);

        for (OrderflowStrategy.Trade t : result.trades()) {
            report(market, t, "ENTRY", t.entryTime(), t.entry(), seen, quiet);
            report(market, t, "EXIT", t.exitTime(), t.exit(), seen, quiet);
        }
        result.openTrade().ifPresent(t -> report(market, t, "ENTRY", t.entryTime(), t.entry(), seen, quiet));
    }

    private static void report(String market, OrderflowStrategy.Trade t, String event,
                               long time, double price, Set<String> seen, boolean quiet) {
        if (!seen.add(market + "|" + event + "|" + t.entryTime())) {
            return;
        }
        if (quiet) {
            return;   // pre-existing history, recorded as seen only
        }
        boolean exit = event.equals("EXIT");
        String line = String.join(",",
                market, event, Instant.ofEpochMilli(time).toString(),
                fmt(price), fmt(t.stop()), fmt(t.target()),
                exit ? t.reason() : "",
                exit ? fmt(t.rMultiple()) : "",
                exit ? fmt(t.eur()) : "");
        System.out.println(line);
        try {
            Files.writeString(OUT_CSV, line + System.lineSeparator(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("could not append to " + OUT_CSV + ": " + e.getMessage());
        }
    }

    /** The API ends with the currently open candle; the strategy only sees closed ones. */
    private static List<Kline> closedOnly(List<Kline> klines) {
        long now = System.currentTimeMillis();
        return klines.stream().filter(k -> k.closeTime() <= now).toList();
    }

    private static void sleepUntilNextCandle() throws InterruptedException {
        long intervalMillis = BitvavoClient.closeTime(0, EXECUTION_INTERVAL) + 1;
        long now = System.currentTimeMillis();
        long next = (now / intervalMillis + 1) * intervalMillis + 10_000;   // 10s past the boundary
        Thread.sleep(next - now);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.6f", v);
    }
}
