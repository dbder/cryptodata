package nl.debo.cryptodata.bot;

import nl.debo.cryptodata.tools.Kline;

import java.util.List;

/**
 * Classifies the higher-timeframe environment from candles ordered oldest
 * first. Swappable so a different structure model (or a real GEX feed for
 * BTC/ETH via an options exchange) can replace the default without touching
 * the rest of the bot.
 */
@FunctionalInterface
public interface EnvironmentClassifier {

    Environment classify(List<Kline> higherTimeframe);
}
