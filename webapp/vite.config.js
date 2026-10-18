import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Candle store written by KlineHistoryImportBitvavo / CryptoAnalysisBitvavo.
const KLINES_DIR = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../output/klines-bitvavo'
);

// Ledger of the user's own Bitvavo trades, written by PrivateInfoImportBitvavo (TradeCsvStore).
const TRADES_CSV = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../output/balance-bitvavo/trades.csv'
);

const INTERVAL_ORDER = ['1h', '2h', '4h', '6h', '8h', '12h', '1d', '2d', '1W', '1M'];

// Intervals the store does not hold itself, merged from a base interval on request (mirrors the
// Java DerivedInterval): `2d` is every two consecutive 1d candles taken together.
const DERIVED = { '2d': { base: '1d', factor: 2 } };

/** Parses a store CSV into candles, oldest first. */
function readCandles(file) {
  const candles = [];
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!line || line.startsWith('openTime')) continue;
    const [openTime, open, high, low, close, volume, closeTime] = line.split(',');
    candles.push({
      openTime: Number(openTime),
      open: Number(open),
      high: Number(high),
      low: Number(low),
      close: Number(close),
      volume: Number(volume),
      closeTime: Number(closeTime),
    });
  }
  return candles.sort((a, b) => a.openTime - b.openTime);
}

/**
 * Merges `factor` consecutive base candles into one: open of the first, close of the last, the
 * group's high/low, volumes summed. Groups are counted back from the newest closed base candle so
 * the last derived candle is always complete; a still-open candle and the incomplete group at the
 * old end are dropped. Same rules as the Java DerivedInterval, so the chart matches the report.
 */
function derive(base, factor, now = Date.now()) {
  const closed = base.filter((c) => c.closeTime <= now);
  const out = [];
  for (let start = closed.length % factor; start + factor <= closed.length; start += factor) {
    const group = closed.slice(start, start + factor);
    out.push({
      openTime: group[0].openTime,
      open: group[0].open,
      high: Math.max(...group.map((c) => c.high)),
      low: Math.min(...group.map((c) => c.low)),
      close: group.at(-1).close,
      volume: group.reduce((sum, c) => sum + c.volume, 0),
      closeTime: group.at(-1).closeTime,
    });
  }
  return out;
}

function json(res, status, body) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json');
  res.end(JSON.stringify(body));
}

/** Serves the local CSV candle store as JSON on /api/* (dev server and `vite preview`). */
function klineApi() {
  return {
    name: 'kline-api',
    configureServer: (server) => mount(server.middlewares),
    configurePreviewServer: (server) => mount(server.middlewares),
  };
}

function mount(middlewares) {
  middlewares.use('/api/markets', (req, res) => {
        if (!fs.existsSync(KLINES_DIR)) {
          return json(res, 404, { error: `candle store not found: ${KLINES_DIR}` });
        }
        const markets = {};
        for (const file of fs.readdirSync(KLINES_DIR)) {
          const m = /^(.+)_([^_]+)\.csv$/.exec(file);
          if (!m) continue;
          (markets[m[1]] ??= []).push(m[2]);
        }
        // A market with the base interval in store also offers the intervals derived from it.
        for (const intervals of Object.values(markets)) {
          for (const [name, { base }] of Object.entries(DERIVED)) {
            if (intervals.includes(base) && !intervals.includes(name)) intervals.push(name);
          }
        }
        const list = Object.keys(markets)
          .sort()
          .map((market) => ({
            market,
            intervals: markets[market].sort(
              (a, b) => INTERVAL_ORDER.indexOf(a) - INTERVAL_ORDER.indexOf(b)
            ),
          }));
        json(res, 200, list);
      });

  middlewares.use('/api/klines', (req, res) => {
        const url = new URL(req.url, 'http://localhost');
        const market = url.searchParams.get('market') ?? '';
        const interval = url.searchParams.get('interval') ?? '';
        if (!/^[A-Z0-9]+-[A-Z0-9]+$/.test(market) || !/^[0-9]+[hdWM]$/.test(interval)) {
          return json(res, 400, { error: 'bad market or interval' });
        }
        const derived = DERIVED[interval];
        const file = path.join(KLINES_DIR, `${market}_${derived?.base ?? interval}.csv`);
        if (!fs.existsSync(file)) {
          return json(res, 404, { error: `no candles for ${market} ${interval}` });
        }
        const candles = readCandles(file);
        json(res, 200, derived ? derive(candles, derived.factor) : candles);
      });

  // The user's own trades (all markets), oldest first; [] when the ledger does not exist yet.
  middlewares.use('/api/trades', (req, res) => {
        if (!fs.existsSync(TRADES_CSV)) return json(res, 200, []);
        const trades = [];
        for (const line of fs.readFileSync(TRADES_CSV, 'utf8').split(/\r?\n/)) {
          if (!line || line.startsWith('timestamp')) continue;
          const [timestamp, market, id, side, amount, price, fee, feeCurrency] = line.split(',');
          trades.push({
            time: Number(timestamp), market, id, side,
            amount: Number(amount), price: Number(price), fee: Number(fee), feeCurrency,
          });
        }
        trades.sort((a, b) => a.time - b.time);
        json(res, 200, trades);
      });
}

export default defineConfig({
  plugins: [react(), klineApi()],
  server: { port: 5173, open: true },
});
