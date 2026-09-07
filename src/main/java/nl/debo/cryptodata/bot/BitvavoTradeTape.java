package nl.debo.cryptodata.bot;

import com.fasterxml.jackson.databind.JsonNode;
import nl.debo.cryptodata.tools.BitvavoRateLimiter;
import nl.debo.cryptodata.utils.JsonHttp;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Client for Bitvavo's public trade tape ({@code GET /v2/{market}/trades}):
 * individual fills with their aggressor side, the raw material for real
 * per-candle delta. This is the data the video's footprint charts are built
 * from — the exchange only serves recent history, which is why
 * {@link TradeTapeCollector} has to record it continuously before a
 * delta-based entry trigger can be backtested.
 */
public final class BitvavoTradeTape {

    /** One public fill; {@code side} is the taker side, "buy" or "sell". */
    public record TapeTrade(String id, long timestamp, double price, double amount, String side) {
    }

    private static final String BASE_URL = "https://api.bitvavo.com/v2";
    private static final int PAGE_LIMIT = 1000;

    private final JsonHttp http = new JsonHttp("Bitvavo API", new BitvavoRateLimiter());

    /**
     * All trades with {@code timestamp > sinceMillis}, oldest first, paging
     * backwards through the tape until the window is covered (capped at
     * {@code maxPages} to bound a cold start on a busy market).
     */
    public List<TapeTrade> tradesSince(String market, long sinceMillis, int maxPages) throws Exception {
        var all = new ArrayList<TapeTrade>();
        long end = System.currentTimeMillis();   // Bitvavo requires start and end together
        for (int page = 0; page < maxPages; page++) {
            List<TapeTrade> batch = fetch(market, sinceMillis + 1, end).get();
            all.addAll(batch);
            if (batch.size() < PAGE_LIMIT) {
                break;   // reached sinceMillis: the window is covered
            }
            end = batch.stream().mapToLong(TapeTrade::timestamp).min().orElseThrow();
        }
        all.sort(Comparator.comparingLong(TapeTrade::timestamp).thenComparing(TapeTrade::id));
        return all;
    }

    private CompletableFuture<List<TapeTrade>> fetch(String market, long start, long end) {
        var uri = URI.create(BASE_URL + "/" + market + "/trades?limit=" + PAGE_LIMIT
                + "&start=" + start + "&end=" + end);
        return http.getJson(uri).thenApply(BitvavoTradeTape::parse);
    }

    private static List<TapeTrade> parse(JsonNode root) {
        var trades = new ArrayList<TapeTrade>(root.size());
        for (JsonNode n : root) {
            trades.add(new TapeTrade(
                    n.get("id").asText(),
                    n.get("timestamp").asLong(),
                    n.get("price").asDouble(),
                    n.get("amount").asDouble(),
                    n.get("side").asText()));
        }
        return trades;
    }
}
