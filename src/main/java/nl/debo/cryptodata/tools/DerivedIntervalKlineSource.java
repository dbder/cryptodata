package nl.debo.cryptodata.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link KlineSource} decorator that answers requests for a
 * {@link DerivedInterval} by fetching its base interval from the wrapped
 * source and merging the candles. Any other interval passes straight
 * through, so the rest of the pipeline treats {@code 2d} like any exchange
 * interval.
 *
 * <p>A derived candle needs {@code factor} base candles, so the base request
 * asks for {@code limit * factor} candles to end up with about {@code limit}
 * derived ones.</p>
 */
public final class DerivedIntervalKlineSource implements KlineSource {

    private final KlineSource delegate;
    private final Map<String, DerivedInterval> derivedByName;

    public DerivedIntervalKlineSource(KlineSource delegate, List<DerivedInterval> derived) {
        this.delegate = delegate;
        this.derivedByName = derived.stream()
                .collect(Collectors.toUnmodifiableMap(DerivedInterval::name, Function.identity()));
    }

    /** {@code delegate} itself when there is nothing to derive, otherwise the decorated source. */
    public static KlineSource wrap(KlineSource delegate, List<DerivedInterval> derived) {
        return derived.isEmpty() ? delegate : new DerivedIntervalKlineSource(delegate, derived);
    }

    @Override
    public CompletableFuture<List<Kline>> getKlinesAsync(String symbol, String interval, int limit) {
        DerivedInterval derived = derivedByName.get(interval);
        if (derived == null) {
            return delegate.getKlinesAsync(symbol, interval, limit);
        }
        return delegate.getKlinesAsync(symbol, derived.baseInterval(), limit * derived.factor())
                .thenApply(derived::derive);
    }
}
