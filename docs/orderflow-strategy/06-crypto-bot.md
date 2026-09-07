# The crypto adaptation — `nl.debo.cryptodata.bot`

The strategy from this documentation, translated to Bitvavo spot markets as a
**backtest + paper-trading bot**. No real orders are ever placed. Long-only,
1h execution candles with 1d candles for the environment.

## What maps to what

```mermaid
flowchart LR
    subgraph video["Video concept"]
        V1["Environment:<br/>structure + GEX"]
        V2["Location:<br/>value area + fib zone"]
        V3["Confirmation:<br/>footprint absorption"]
        V4["Management &<br/>discipline"]
    end
    subgraph bot["Bot class"]
        B1["SwingStructureClassifier<br/>(ATR percentile replaces GEX)"]
        B2["VolumeProfile +<br/>FibDiscountFinder"]
        B3["AbsorptionProxyTrigger<br/>(OHLCV proxy for now)"]
        B4["OrderflowStrategy<br/>(stops, targets, trade caps,<br/>volume filter)"]
    end
    V1 --> B1
    V2 --> B2
    V3 --> B3
    V4 --> B4
```

Every step is a small interface (`EnvironmentClassifier`, `LocationFinder`,
`EntryTrigger`), so a piece can be swapped without touching the loop — that is
how the real delta layer gets in later.

## Honest deviations from the video

- **No GEX.** Crypto spot has no options chain; an ATR-percentile volatility
  regime (CALM/EXPLOSIVE) stands in, and only annotates trades.
- **No footprint data (yet).** The absorption + "second failure higher"
  sequence is approximated from candle shapes. This is the weakest link:
  the video's edge lives exactly in this layer.
- **No sessions.** Crypto trades 24/7; the "first 90 minutes of New York"
  becomes a per-candle volume-percentile participation filter.

## Main classes (run from the IDE)

| Class | What it does |
|---|---|
| `OrderflowBacktest` | Runs the strategy over the local candle store; per-market stats + totals. Args: market names, or none for every market. |
| `PaperTradingBot` | Polls fresh Bitvavo candles every hour and logs the entries/exits the strategy would take to `output/bot-paper/paper-trades.csv`. |
| `TradeTapeCollector` | Records the public trade tape (with taker side) to `output/trade-tape/` — the raw material for real per-candle delta (`CandleDelta`). Bitvavo only serves recent trades, so this must run continuously before a delta-based trigger can ever be backtested. |

## First backtest result (2026-09-07, defaults)

Full candle store, 1h execution: **698 trades, 31% win rate, profit factor
0.66, −€257** at €100 stake / 0.25% fee per side. Majors only (BTC, ETH, SOL,
XRP, ADA): 84 trades, PF 1.10, ≈ break-even.

That is the expected shape of a v1: the candle proxy captures the *location*
logic but not the *confirmation* edge, and the video's own numbers (60–65%
win rate) come from reading real order flow. The path forward, in order:

1. let `TradeTapeCollector` run for a few weeks,
2. build a delta-based `EntryTrigger` on `CandleDelta`,
3. compare it against `AbsorptionProxyTrigger` on the same window — only the
   tape-covered period, honestly — before any thought of live orders.
