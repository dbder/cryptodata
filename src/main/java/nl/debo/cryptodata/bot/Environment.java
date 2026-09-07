package nl.debo.cryptodata.bot;

/**
 * Step 1 of the orderflow process (docs/orderflow-strategy/01-environment.md):
 * what kind of market are we walking into? {@code structure} is read from the
 * higher timeframe; {@code regime} replaces the video's gamma exposure — crypto
 * on Bitvavo has no options market, so a realized-volatility percentile stands
 * in for the dealer-positioning volatility regime.
 */
public record Environment(Structure structure, Regime regime) {

    /** Direction in which the higher timeframe is building value. */
    public enum Structure {UP, DOWN, SIDEWAYS}

    /**
     * Volatility regime, the GEX proxy: {@code EXPLOSIVE} ≈ negative gamma
     * (moves get amplified), {@code CALM} ≈ positive gamma (chop, failed
     * breakouts). Annotation only — like the video it informs, it doesn't gate.
     */
    public enum Regime {CALM, EXPLOSIVE}
}
