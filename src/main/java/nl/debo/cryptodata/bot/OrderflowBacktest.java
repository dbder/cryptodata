package nl.debo.cryptodata.bot;

import nl.debo.cryptodata.tools.Kline;
import nl.debo.cryptodata.utils.KlineCsvStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Backtests {@link OrderflowStrategy} over the local candle store: 1h
 * execution candles with 1d candles for the environment. With no arguments
 * every market that has both intervals is tested; pass market names
 * (e.g. {@code BTC-EUR ETH-EUR}) to test just those.
 *
 * <p>Prints one line per market plus totals: trades, win rate, average R,
 * profit factor and the euro result at the configured stake and fee — the
 * same money semantics as the webapp simulation, so the numbers compare.
 */
public final class OrderflowBacktest {

    private static final Path KLINES_DIR = Path.of("output", "klines-bitvavo");
    private static final String EXECUTION_INTERVAL = "1h";
    private static final String STRUCTURE_INTERVAL = "1d";

    public static void main(String[] args) throws Exception {
        List<String> markets = args.length > 0 ? List.of(args) : marketsWithBothIntervals();
        var strategy = new OrderflowStrategy(OrderflowStrategy.Config.defaults());

        System.out.printf("%-12s %7s %7s %7s %7s %9s%n", "market", "trades", "win%", "avgR", "PF", "eur");
        int totalTrades = 0;
        int totalWins = 0;
        double totalEur = 0;
        double grossWin = 0;
        double grossLoss = 0;

        for (String market : markets) {
            List<Kline> execution = KlineCsvStore.readKlines(csv(market, EXECUTION_INTERVAL));
            List<Kline> structure = KlineCsvStore.readKlines(csv(market, STRUCTURE_INTERVAL));
            var result = strategy.run(execution, structure);
            List<OrderflowStrategy.Trade> trades = result.trades();
            if (trades.isEmpty()) {
                System.out.printf("%-12s %7d%n", market, 0);
                continue;
            }
            int wins = (int) trades.stream().filter(t -> t.eur() > 0).count();
            double eur = trades.stream().mapToDouble(OrderflowStrategy.Trade::eur).sum();
            double avgR = trades.stream().mapToDouble(OrderflowStrategy.Trade::rMultiple).average().orElse(0);
            double win = trades.stream().filter(t -> t.eur() > 0).mapToDouble(OrderflowStrategy.Trade::eur).sum();
            double loss = -trades.stream().filter(t -> t.eur() <= 0).mapToDouble(OrderflowStrategy.Trade::eur).sum();
            System.out.printf("%-12s %7d %6.0f%% %7.2f %7.2f %9.0f%s%n",
                    market, trades.size(), 100.0 * wins / trades.size(), avgR,
                    loss > 0 ? win / loss : Double.POSITIVE_INFINITY, eur,
                    result.openTrade().isPresent() ? "  (1 open)" : "");
            totalTrades += trades.size();
            totalWins += wins;
            totalEur += eur;
            grossWin += win;
            grossLoss += loss;
        }

        if (totalTrades > 0) {
            System.out.printf("%n%-12s %7d %6.0f%% %15.2f %9.0f%n", "TOTAL",
                    totalTrades, 100.0 * totalWins / totalTrades,
                    grossLoss > 0 ? grossWin / grossLoss : Double.POSITIVE_INFINITY, totalEur);
        }
    }

    private static Path csv(String market, String interval) {
        return KLINES_DIR.resolve(market + "_" + interval + ".csv");
    }

    private static List<String> marketsWithBothIntervals() throws Exception {
        try (Stream<Path> files = Files.list(KLINES_DIR)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith("_" + EXECUTION_INTERVAL + ".csv"))
                    .map(name -> name.substring(0, name.length() - ("_" + EXECUTION_INTERVAL + ".csv").length()))
                    .filter(market -> Files.exists(csv(market, STRUCTURE_INTERVAL)))
                    .sorted()
                    .toList();
        }
    }
}
