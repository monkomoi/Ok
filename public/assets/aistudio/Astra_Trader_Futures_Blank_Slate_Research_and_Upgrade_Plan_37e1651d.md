# ASTRA TRADER — FUTURES BINANCE / SMALL-CAPITAL INTRADAY
## Research, APK audit, blank-slate rebuild and implementation specification

**Document version:** 1.0  
**Research date:** 31 October 2026  
**Target runtime:** Samsung Galaxy S7 Edge, Exynos 8890, 4 GB RAM, LineageOS Android 11 (API 30)  
**Exchange/product:** Binance USDⓈ-M Futures, USDT-margined perpetual contracts  
**Operating intent:** frequent intraday opportunities, small capital, strict downside control, daily compounding when profits actually occur  
**Status:** design and audit specification; not a claim that the APK is production-ready or profitable

---

## 0. Executive decision

Astra should be rebuilt as a **small, event-driven, risk-first intraday futures system**. It should continuously observe a small, dynamically selected universe of liquid USDT perpetuals, detect a limited number of high-quality opportunities, calculate every trade deterministically, and execute only when the expected net edge remains positive after fees, slippage, funding and market impact.

It must not be designed to force a trade every day. A day with no valid opportunity is a successful risk-control day, not a system failure.

The user's aspirational target is **+1% net account-equity growth per day, compounded**. This must be represented as a performance aspiration and reporting benchmark, not a guaranteed return, mandatory quota, or trigger to increase leverage. A bot that is forced to find 1% every day will eventually lower its standards, overtrade, or increase risk exactly when it should stand aside.

### 0.1 The most important design decisions

1. **Start with an empty trusted-data store.** Bundled samples, demo trades, AI-generated examples and synthetic fixtures must never be silently treated as real market history or live performance.
2. **Use Binance as the source of truth for market and account facts.** External AI may explain, classify and challenge evidence; it must not invent candles, prices, fills, balances or exchange rules.
3. **Do not train a large model on the S7 Edge.** The phone is a lightweight collector, feature calculator, inference host, risk controller and execution monitor. Large historical training and model validation belong on a desktop/VPS/cloud training environment.
4. **Use one primary strategy at first.** Start with a testable Breakout + Momentum + Volume Expansion + Retest strategy. Do not launch a zoo of half-validated strategies.
5. **Use AI as an advisor and research assistant, not the order authority.** AI proposes or challenges a candidate. Deterministic code independently decides whether any order is allowed.
6. **Calculate size from maximum acceptable loss, not from desired leverage or profit target.** Leverage only changes required margin and liquidation geometry; it does not create an edge.
7. **Exchange-hosted protective orders are mandatory.** A phone crash must not remove the stop-loss from an already-open position.
8. **Every unknown execution state is treated as unknown.** A timeout is not proof that an order failed. Query by client ID and reconcile before considering another submission.
9. **Do not go LIVE until the system passes data, model, risk, execution and long-duration Android tests.** The current APK has not been proven in runtime operation.

### 0.2 A realistic success definition

The first success milestone is not “make 1% today”. It is:

- ingest authentic data continuously without gaps or timestamp corruption;
- correctly identify liquid and tradable contracts;
- produce reproducible signals from closed candles;
- estimate net expectancy after all costs;
- reject weak or stale candidates;
- size a position within pre-approved loss limits;
- place and verify an exchange-side stop immediately after fills;
- reconcile account state after disconnections and restarts;
- demonstrate positive, stable out-of-sample and paper-trading results before risking capital.

Only after that may the system attempt to improve return while keeping drawdown bounded.

---

## 1. What +1% per day actually means

Compounding makes a small daily percentage grow rapidly:

| Assumed net return | Starting equity | Equity after 30 days | Equity after 252 days | Equity after 365 days |
|---|---:|---:|---:|---:|
| +1% every day | 100 USDT | ~134.78 | ~1,227.40 | ~3,778.34 |

These are mathematical illustrations, not forecasts. They assume the account earns exactly +1% net every day, can reinvest all profits, has no losing days, no withdrawals, no capacity limits and no operational interruptions. Real trading does not behave this way. Crypto markets also have quiet periods, adverse regimes, spread expansion, API failures, fees, funding and losing streaks.

### 1.1 How Astra should encode the target

- `daily_goal_pct = 1.0` is a display/benchmark value only.
- `daily_goal_reached` may activate a profit-protection policy, such as reducing risk or pausing new entries for the rest of the accounting day.
- `daily_goal_not_reached` must **not** make the scanner loosen filters, increase leverage, increase trade frequency or lower the required EV.
- No valid candidate means `NO_TRADE`.
- A negative-EV or uncertain candidate must be rejected even if the day is currently red.
- The system must not “make back” a loss by doubling size, using Martingale, averaging down without a predefined risk plan, or opening correlated positions.

### 1.2 Daily return accounting

Calculate daily performance from account equity, adjusted for external cash flows:

`daily_net_return = (equity_end - equity_start - net_deposits) / equity_start`

Use one fixed accounting timezone. Binance timestamps and funding events should remain UTC internally; the UI may display Asia/Ho_Chi_Minh time. Avoid resetting the daily counter at different times in different modules.

Report at least:

- realized trading PnL;
- unrealized PnL;
- maker/taker commissions;
- funding paid/received;
- slippage and estimated market impact;
- liquidation/insurance/other exchange charges if any;
- deposits and withdrawals;
- net equity change.

Do not count a profitable open position as fully realized daily income. Do not double-count the same PnL when a position crosses the daily boundary.

---

## 2. Static APK audit: what is present and what remains unproven

The supplied APK was inspected as a compiled Android package. The following are static observations from its DEX strings, assets and embedded forecast bundle. They are not proof that every method is correct, reachable, called in production, or safe under live market conditions.

### 2.1 Components and architecture clues found

| Area | Components / strings observed in APK | Assessment |
|---|---|---|
| Binance execution | `BinanceAdapter`, `submitAlgoConditionalOrder`, order query/cancel/reconciliation-related symbols | Substantial implementation clues; requires runtime tests against demo trading |
| AI integration | `AiIntelligenceGateway`, strict AI parser-related symbols | Provider abstraction exists; provider routing quality and failure handling still need tests |
| Telegram | `TelegramBotController`, `Panic`-related symbols | Bot control scaffolding exists; must test auth, replay handling, delivery persistence and panic behavior |
| Quant / backtest | `runChronologicalBacktest`, `calculatePositionSizing`, `calibrateWinProbabilityFromHistory` | Useful quantitative components appear present; correctness and data provenance are not established |
| Risk / execution gate | `ExecutionGate`, `OrderIntent` | Good architectural concepts; validate all transitions and failure cases |
| News / sentiment | `NewsEventGateEngine`, `CrowdAttentionProxySnapshot` | Data model/scaffolding exists; real source coverage and freshness are not established |
| Forecast model | `GRU_TCN_HYBRID_INT8_COMPACT_KOTLIN`, logistic fallback | A compact model bundle is present; it is not evidence of live self-training or predictive edge |

### 2.2 Forecast bundle findings

The APK contains `assets/forecast_bundle/` with:

- `manifest.json`
- `feature_schema.json`
- `calibration.json`
- `scaler.json`
- `golden_feature_vectors.json`
- `golden_outputs.json`
- `weights_compact.json`
- `SHA256SUMS`

The manifest reports:

| Field | Value embedded in APK |
|---|---|
| Model | `astra_gru_tcn_hybrid_v1_int8` |
| Runtime | `COMPACT_KOTLIN_CPU_INT8_2THREADS` |
| Architecture | GRU + TCN hybrid, compact INT8 |
| Lookback | 64 steps |
| Feature count | 26 |
| Base timeframe | 15 minutes |
| Horizons | 15m, 1h, 4h |
| Declared supported symbols | BTCUSDT, ETHUSDT, SOLUSDT, DOGEUSDT |
| Embedded dataset bars | 560 × 15m bars |
| Declared source | `BINANCE_PUBLIC_API_CACHED` |
| Declared dataset span | 25 October 2026 11:30 UTC to 31 October 2026 07:29:59 UTC |
| Declared mode | `SHADOW_ADVISORY` |
| Target device | Samsung Galaxy S7 Edge, arm64, 4 GB RAM, API 30 |

Important audit findings:

1. **560 fifteen-minute candles represent only about 5.8 days.** That is not enough to establish a robust trading edge across bullish, bearish, sideways, high-volatility and low-volatility regimes.
2. `golden_feature_vectors.json` contains only three samples. `golden_outputs.json` contains three samples, all for `DOGEUSDT`.
3. `calibration.json` reports 23 samples per horizon. This is far too small to trust as a production calibration set, even where reported Brier/ECE values look excellent.
4. The 4h golden outputs contain cases where the direction classifier reports approximately 100% `pDown`, while the regression/quantile outputs report positive expected return and positive median return. These may be separate model heads, but the output contract needs explicit semantics and automated consistency tests. Do not assume it is definitely a code bug; do not ignore it either.
5. The manifest declares `model_tflite_sha256`, but the runtime label says compact Kotlin CPU INT8 and the asset list contains `weights_compact.json`, not a distinct `.tflite` file. Clarify the naming and artifact contract.
6. The SHA-256 entries in the embedded `SHA256SUMS` file match the included bundle files. This verifies internal file integrity, **not** the authenticity of the training data, the correctness of labels, the absence of leakage, or predictive performance.
7. The source label `BINANCE_PUBLIC_API_CACHED` is a claim in a manifest, not independent provenance. The data must be independently checked against Binance public market data before use.

### 2.3 Required APK changes

- Mark all bundled `golden_*` data and any demo trades as `TEST_FIXTURE`.
- Prevent fixture data from entering the production training set, live PnL, trade-history database or model calibration store.
- Add a visible `DATA_TRUST_STATE`: `EMPTY`, `BOOTSTRAPPING`, `VALIDATING`, `SHADOW_READY`, `PAPER_READY`, `LIVE_ELIGIBLE`, `QUARANTINED`.
- On first launch, show that no verified user trading history exists. Do not display sample PnL as account performance.
- Add model metadata: training date, source symbols, source intervals, time span, label definition, fee assumptions, training code version, validation split, metrics and artifact hash.
- Validate output semantics for direction probability, expected return, quantiles, uncertainty and OOD score. If heads disagree beyond a tested tolerance, mark the forecast as `INCONSISTENT` and prevent it from authorizing a trade.
- Add per-symbol model eligibility. Never apply a DOGE-specific calibration to XRP or an unsupported coin.
- Add a runtime diagnostic screen that identifies whether the forecast ran, which model version ran, how long it took, memory usage, OOD status and whether the output passed validation.

---

## 3. Blank-slate startup and trustworthy data bootstrap

The app has not been run in a proven live environment and there is no verified user-generated trading history. Treat it as a clean installation even though the APK contains test assets.

### 3.1 First-run sequence

1. Create an empty production database.
2. Move all bundled test assets into a read-only test namespace or tag them `TEST_FIXTURE`.
3. Verify device clock, Binance server time, API connectivity and DNS/TLS reachability.
4. Fetch USDⓈ-M `exchangeInfo` and build a current contract metadata cache.
5. Fetch the current 24h ticker universe and rank eligible USDT perpetuals.
6. Backfill authentic historical klines for the selected universe from Binance API or Binance Public Data archives.
7. Compare a sample of downloaded history with Binance's live API to confirm schema, timestamps, symbol and market type.
8. Start live WebSocket ingestion and reconcile the live stream against the last REST candle.
9. Run feature calculations and data-quality tests without creating trades.
10. Build the first shadow signals, store their exact input snapshots and wait for future outcomes to mature.
11. Only after sufficient valid history and testing may the app move to paper trading.

### 3.2 Source-of-truth policy

| Data | Primary source | Use of AI |
|---|---|---|
| Candles, trades, book, mark price | Binance USDⓈ-M public API / WebSocket / official public archives | No AI-generated values allowed |
| Funding rate and history | Binance Futures funding endpoints and streams | AI may explain unusual values, not replace raw data |
| Open interest | Binance Futures market-data endpoints | AI may summarize; numeric source remains Binance |
| Account equity, positions, orders, fills | Signed Binance account endpoints + User Data Stream | Never accept AI as account truth |
| Contract filters and status | Binance `exchangeInfo` | Never hardcode as permanent values |
| Fear & Greed | Explicit third-party source such as Alternative.me, with attribution | AI can explain; label its scope and timestamp |
| News | Verifiable publisher/project/exchange sources | AI can classify/summarize with source links and timestamp |
| Social sentiment | Approved API/provider with clear licensing and author/source quality | Never treat bot-heavy or unverified mentions as factual market evidence |

### 3.3 Historical data requirements

Recommended initial research corpus:

- 6–12 months minimum for an early strategy study; 12–24 months is preferable when reliable history is available.
- Include more than one market regime. A single recent rally or one week of candles is not representative.
- Start with 5m and 15m candles for selected liquid symbols; use 1h and 4h for regime context.
- Use 1m bars or trades only for entry simulation and execution-cost studies where necessary.
- Add funding history, open-interest snapshots and mark-price data where available.
- Add order-book or aggTrade data only if the strategy actually uses it. Do not collect massive raw depth data merely because it sounds sophisticated.

For a constrained S7 Edge, do not attempt to download and keep every tick for hundreds of symbols. Use Binance Public Data archives or a separate training computer to prepare research datasets. Store a compact, validated runtime cache on the phone.

### 3.4 Data provenance fields

Every stored data item should carry:

- `source` and `source_endpoint`;
- `market_type` (`USD_M_FUTURES`);
- `symbol` and `interval`;
- `event_time`, `receive_time` and `close_time`;
- `source_timestamp_unit` (milliseconds or microseconds);
- `is_closed` for candles;
- `sequence_or_trade_id` where provided;
- `checksum` or content hash for archived files;
- `is_synthetic` and `is_fixture` flags;
- `validation_status` and `validation_errors`;
- `collector_version`.

Never merge data from spot and USDⓈ-M Futures as though they were the same instrument. Never mix seconds, milliseconds and microseconds without an explicit conversion layer.

### 3.5 Synthetic data policy

Synthetic data may be used for:

- unit tests;
- fault injection;
- UI previews;
- load tests;
- controlled model experiments where synthetic provenance is explicit.

Synthetic data must never be used to:

- claim real strategy profitability;
- calibrate production probability outputs;
- train the champion production model without an explicitly isolated experiment;
- report user PnL;
- authorize live trading.

If an AI provider creates sample candles or a hypothetical news event, tag it `SYNTHETIC_AI_GENERATED` and quarantine it. AI output is not an exchange feed.

---

## 4. Dynamic coin selection: what happens when the scanner finds a new contract?

Astra must not depend on a permanently hardcoded list of four symbols. The scanner should discover eligible contracts dynamically, but dynamic discovery does not mean every newly discovered coin is safe to trade.

### 4.1 Contract eligibility

Fetch `exchangeInfo` and require, at minimum:

- `status == TRADING`;
- quote asset is USDT;
- contract type is the intended perpetual type;
- the contract is supported by the account and current API;
- price/quantity/notional filters are present and valid;
- current market data is fresh;
- minimum order quantity and notional are feasible for the account's risk budget.

Exclude or quarantine:

- suspended, delivery, expired or non-perpetual contracts;
- contracts with insufficient history;
- recently listed contracts until the configured history threshold is met;
- extreme spread or shallow order book;
- abnormal data gaps or inconsistent mark/last prices;
- symbols with API errors or unrecognized filters;
- symbols with high estimated market impact for the intended order size.

### 4.2 Rank the universe

Use a code-based ranking model. A possible scoring mix is:

- quote-volume/liquidity score;
- spread and depth score;
- volatility suitability score;
- clean data coverage score;
- relative-volume score;
- trend/momentum score;
- funding and event-risk penalty;
- execution-cost estimate.

The scanner should begin with a modest universe, such as the top 8–15 eligible contracts by liquidity, then narrow to 3–5 candidates for deeper analysis. These are initial engineering limits to benchmark on the phone, not permanent exchange rules.

### 4.3 Per-coin model support

For each symbol, maintain an explicit state:

- `FULL_SUPPORTED`: the model has been trained and independently validated for this symbol or a demonstrably transferable pooled model, with calibration evidence.
- `POOLED_MODEL_ONLY`: the model is trained across multiple symbols but has not met the symbol-specific confidence threshold.
- `BASELINE_ONLY`: deterministic strategy and baseline statistics are available, but no validated neural forecast exists.
- `UNSUPPORTED`: insufficient history, OOD state, broken data or unvalidated contract.

A new coin must never inherit DOGE calibration automatically. If a model is unsupported or out-of-distribution, the forecast is advisory or disabled. The code-based strategy may still evaluate it only if its own symbol eligibility and historical tests pass. Otherwise, `NO_TRADE`.

---

## 5. Trading strategy: start with one measurable setup

The primary goal is short-term USDT-margined intraday trading. The S7 Edge and mobile network are not suitable targets for ultra-low-latency HFT. Start with a 5m/15m setup and a 1m or 5m entry trigger, not millisecond scalping.

### 5.1 Proposed first strategy: Breakout + Momentum + Volume Expansion + Retest

This is a research baseline, not a proven profitable strategy.

**Regime layer — 1h / 4h**

- Identify trend, range and high-volatility regimes.
- Use EMA slope/structure, ATR percentile and recent range expansion.
- Avoid taking a breakout directly into a major higher-timeframe resistance/support zone.
- Detect abrupt volatility regime changes and stand aside until the model has enough evidence.

**Setup layer — 15m / 5m**

- Identify a well-defined recent range or consolidation.
- Require a close beyond the range boundary, not merely an intrabar wick.
- Confirm relative volume expansion against a rolling baseline.
- Check candle body/wick structure to reduce wick-only fakeouts.
- Check spread, depth and estimated slippage before treating the breakout as tradable.

**Entry layer — 1m / 5m**

- Prefer a retest/hold of the broken level or a validated continuation trigger.
- Do not chase after price has moved too far from the planned entry.
- Reject if current price has drifted outside the candidate's allowed entry band.
- Ensure actual stop distance leaves positive net expectancy after costs.

**Optional derivatives confirmation**

- open-interest change;
- taker buy/sell imbalance;
- funding rate and predicted funding where available;
- mark price vs contract/last price;
- liquidation/ADL risk context where available.

These are confirmations, not independent reasons to trade. Funding extremes can persist; a high funding rate is not an automatic short signal.

### 5.2 Signal output contract

Every candidate must produce a deterministic record:

```json
{
  "decision_id": "unique-id",
  "symbol": "BTCUSDT",
  "strategy_id": "BREAKOUT_MOMENTUM_RETEST_V1",
  "market_regime": "TREND_UP",
  "direction": "LONG",
  "signal_time_utc": "ISO-8601",
  "entry_type": "LIMIT_RETEST",
  "entry_min": 0,
  "entry_max": 0,
  "stop_loss": 0,
  "take_profit_levels": [],
  "invalidation_conditions": [],
  "expected_holding_minutes": 0,
  "estimated_fee_pct_round_trip": 0,
  "estimated_slippage_pct": 0,
  "estimated_funding_cost_pct": 0,
  "net_ev_r": 0,
  "data_quality": "PASS",
  "model_eligibility": "BASELINE_ONLY",
  "expires_at_utc": "ISO-8601"
}
```

Values above are schema placeholders, not market instructions. The candidate must include evidence IDs linking every metric to the raw data snapshot.

### 5.3 No-lookahead rule

- Use only fully closed candles for candle-based signals unless intrabar behavior is explicitly modeled.
- Store the exact `as_of` time of every feature.
- Never use a future close, future funding payment, future high/low or later revised data in a historical decision.
- Backtest order arrival delay, trigger delay and slippage; do not fill at a candle close that was only known after the candle closed.
- Avoid using the same price tick for both signal confirmation and idealized fill unless the execution model justifies it.

---

## 6. Quantitative engine: mathematics must be deterministic and cost-aware

The app should use conventional code for indicators, feature calculations, probability bookkeeping, sizing and order rules. AI should not be asked to calculate position quantity, liquidation price, margin or exact exchange filters.

### 6.1 Net expected value

At minimum:

`NetEV = P(win) × AvgWin − P(loss) × AvgLoss − Fees − Slippage − Funding − OtherExpectedCosts`

Use probabilities and average outcomes estimated from genuinely out-of-sample data, not model confidence prose. Reject trades where net EV is not positive after a safety buffer. The buffer must be tuned from measured model uncertainty and execution error.

Track expectancy in R units as well as USDT:

`Expectancy_R = P(win) × AvgWin_R − P(loss) × AvgLoss_R − Costs_R`

A high win rate is not enough if the average loss is much larger than the average win.

### 6.2 Fees and funding

Calculate fees from the actual account commission rate and the expected maker/taker behavior. Do not hardcode one universal fee schedule. A round trip may include two executed sides and partial fills.

Funding is separate from commission. Funding is calculated from position notional and the applicable funding rate, and is paid/received only if the position is open at the relevant funding settlement. Funding intervals can be changed by exchange rules and contract conditions; query the current rate and schedule rather than assuming a fixed interval forever.

### 6.3 Position sizing

Define:

- `E`: verified account equity or approved risk capital;
- `r`: configured risk fraction per trade;
- `R`: maximum planned loss budget for this trade;
- `P_entry`: planned entry price;
- `P_stop`: stop price;
- `C_unit`: conservative per-unit cost allowance, including fees, expected slippage and funding estimate.

`R = min(E × r, remaining_daily_risk_budget, portfolio_risk_budget)`

`quantity_raw = R / (abs(P_entry − P_stop) + C_unit)`

Then apply:

- exchange quantity step and minimum quantity;
- market-lot size rules;
- minimum notional;
- maximum notional and account exposure caps;
- available margin;
- maintenance-margin/liquidation-distance checks;
- expected slippage at the rounded quantity.

If rounding the quantity up to the exchange minimum makes risk exceed the approved budget, reject the trade. Never widen the loss budget merely to satisfy minimum notional.

`notional = quantity × P_entry`

`approx_initial_margin = notional / leverage`

This last relation is an approximation for planning, not a substitute for the exchange's actual margin and maintenance-margin tier calculations.

### 6.4 Leverage policy

- Size the position from stop distance and maximum loss first.
- Select the lowest leverage that provides adequate margin efficiency while keeping liquidation safely beyond the stop and expected volatility envelope.
- Prefer Isolated Margin for the initial implementation.
- Do not switch margin mode or position mode while positions/orders are open unless a tested recovery procedure explicitly supports it.
- Query leverage brackets and maintenance margin where applicable.
- Reject trades where the stop is too close to liquidation under stressed price movement.
- Never increase leverage because the daily goal is not reached.
- Never allow AI to set leverage or quantity directly.

### 6.5 Portfolio risk

Add `PortfolioRiskManager` before live trading:

- total open risk across all positions;
- total notional exposure;
- total initial/maintenance margin use;
- correlation and common-factor exposure;
- exposure by quote asset, sector/theme and market beta;
- risk concentration during BTC-led moves;
- funding concentration;
- maximum number of concurrent positions.

For the first live-capable version, start with one open position at a time. Expand only after portfolio-level risk tests show the system understands correlated positions. Four correlated LONGs are not four independent bets.

### 6.6 Suggested initial risk limits for paper testing

These are conservative **starting test parameters**, not universal financial advice. Adjust only with documented evidence:

| Parameter | Initial paper-test value | Rule |
|---|---:|---|
| Risk per trade | 0.25% of equity | Never changed by AI |
| Maximum daily net loss stop | 1.0% of start-of-day equity | Stop new entries; manage open positions safely |
| Maximum weekly drawdown stop | 3.0% | Pause and diagnose |
| Maximum peak-to-trough drawdown review threshold | 5.0% | Disable new entries and require review |
| Concurrent positions | 1 | Increase only after portfolio tests |
| Maximum new entries per day | 3 initially | Hard cap to prevent overtrading |
| Loss-streak stop | 3 consecutive losses | Pause and review regime/model |
| Leverage | Lowest feasible, Isolated | Never increase to chase target |
| Profit goal | +1% daily aspiration | If reached, consider risk reduction/pause; never force it |

For very small capital, 0.25% may be a tiny absolute dollar amount. Exchange minimum notional and fees may make some contracts economically untradeable. In that situation the correct outcome is `NO_TRADE`, not a larger risk percentage.

---

## 7. External AI: roles, routing, prompt design and token budget

Astra may use multiple external AI providers, but the number of configured providers is not the same as the number of useful independent agents. The phone's network latency, provider latency, quotas and token costs make indiscriminate parallel calls a bad design.

### 7.1 Recommended roles

| Role | What it receives | What it returns | Can block a trade? | Can place a trade? |
|---|---|---|---|---|
| Quant Scanner | Local numeric features | Candidate score | Yes, through deterministic rules | No |
| Primary Analyst | Compact candidate snapshot + evidence | Structured rationale and risk flags | May recommend rejection | No |
| Skeptic | Same facts plus analyst proposal | Contradictions, missing evidence, risk objections | May recommend rejection | No |
| News/Event Analyst | Verified source snippets and timestamps | Event classification and affected symbols | May raise event gate | No |
| Risk Engine | Account/exchange facts + stop/entry | Quantity, margin, risk approval | Yes | No |
| Execution Engine | Approved immutable intent | Exchange order operations | N/A | Yes, only deterministic code |
| Performance Analyst | Closed trade journal and metrics | Post-trade diagnosis | May recommend pausing a strategy | No |

Do not add a dedicated AI for every tiny task. Begin with one primary model and one independent skeptic. Invoke a news model only when the event detector finds something material. Model selection should use measured latency, schema compliance, cost, reliability and historical usefulness on a fixed test set.

### 7.2 AI provider routing

Maintain a provider registry with:

- provider/model name and endpoint;
- supported API protocol;
- authentication method;
- response schema adapter;
- timeout and retry policy;
- latency p50/p95;
- success/error rate;
- JSON/schema compliance;
- measured cost per decision;
- task-specific evaluation score;
- last health-check time;
- rate-limit/quota state.

Routing policy:

1. Low-cost/low-latency model may produce a first analysis for an already filtered candidate.
2. A different provider/model may perform skepticism for a high-value candidate.
3. A news model runs only when a verified event or abnormal social-attention signal exists.
4. If a model times out, the candidate expires or falls back according to an explicit policy. Do not wait indefinitely.
5. If the two models materially disagree, return `NO_TRADE` or `REVIEW_REQUIRED`, not an average of contradictory answers.
6. If all AI APIs are down, deterministic risk controls remain operational. Whether new entries are allowed without AI must be a configuration chosen during testing; default for the first LIVE-capable release should be `AI_ADVISORY_ONLY` and require the local strategy's own evidence to be sufficient.
7. AI can never bypass data freshness, risk, execution, account-state or protective-order gates.

### 7.3 Compact context packet

Do not send the entire candle history, raw order book or chat transcript to AI. The phone calculates features and sends a compact snapshot with a bounded evidence window:

```json
{
  "schema_version": "astra.decision-context.v1",
  "decision_id": "unique-id",
  "as_of_utc_ms": 0,
  "symbol": "BTCUSDT",
  "strategy": "BREAKOUT_MOMENTUM_RETEST_V1",
  "timeframes": ["1h", "15m", "5m"],
  "data_quality": {
    "market_data_fresh": true,
    "closed_candles_only": true,
    "missing_bar_count": 0,
    "source": "BINANCE_USDM"
  },
  "market": {
    "last_price": 0,
    "mark_price": 0,
    "spread_bps": 0,
    "quote_volume_24h": 0,
    "relative_volume": 0,
    "atr_pct": 0,
    "funding_rate": 0,
    "open_interest_change_pct": 0
  },
  "technical": {
    "ema20_slope": 0,
    "ema50_slope": 0,
    "rsi14": 0,
    "macd_hist_pct": 0,
    "range_breakout": "UP",
    "breakout_level": 0,
    "retest_status": "WAITING"
  },
  "model": {
    "model_id": "versioned-model-id",
    "symbol_eligibility": "BASELINE_ONLY",
    "p_up": null,
    "p_flat": null,
    "p_down": null,
    "ood_score": null,
    "calibration_status": "NOT_QUALIFIED"
  },
  "cost_estimate": {
    "maker_fee_rate": 0,
    "taker_fee_rate": 0,
    "slippage_round_trip_pct": 0,
    "funding_cost_pct": 0,
    "net_ev_r": 0
  },
  "risk_context": {
    "equity_usdt": 0,
    "daily_loss_remaining_usdt": 0,
    "portfolio_open_risk_usdt": 0,
    "max_leverage_allowed": 0
  },
  "evidence_ids": [],
  "expires_at_utc_ms": 0
}
```

AI output should be small and schema-bound:

```json
{
  "decision_id": "same-id",
  "symbol": "BTCUSDT",
  "recommendation": "PASS | CAUTION | REJECT",
  "risk_flags": [],
  "supporting_evidence_ids": [],
  "contradicting_evidence_ids": [],
  "missing_data": [],
  "reason_codes": [],
  "valid_until_utc_ms": 0
}
```

Parser requirements:

- strict JSON only; no natural-language fallback for execution;
- schema version validation;
- exact matching `decision_id` and `symbol`;
- evidence IDs must exist in the submitted snapshot;
- reject unknown enum values and out-of-range values;
- reject expired results;
- ignore tool instructions or arbitrary code contained in retrieved news;
- store raw response, sanitized response, provider/model ID, token usage and latency for audit;
- never let output populate a raw exchange value or account balance.

### 7.4 Token and latency optimization

- Scanner and feature engine filter first. Only the best 3–5 candidates are eligible for AI analysis.
- Use stable system prompts and provider-side prompt caching where supported.
- Send compact numeric features, not full JSON candles.
- Deduplicate near-identical signal fingerprints.
- Reuse a prior response only while the same decision snapshot remains valid; invalidate it if the price, spread, regime or evidence materially changes.
- Use event-driven calls rather than calling AI on every polling cycle.
- Limit output to short structured JSON, reason codes and evidence IDs.
- Do not ask multiple models the same broad question repeatedly.
- Keep an explicit per-day token/cost budget.
- AI latency must never keep an order intent alive past its price/decision TTL.

### 7.5 AI research and “ask AI to fetch and insert data”

AI can search documentation, summarize news, classify events and suggest what data to collect. It cannot be trusted to generate exchange history. Any suggested data source must be resolved to a real endpoint or downloadable archive and fetched by a deterministic collector that stores provenance.

A news summary must contain publisher, canonical URL, publication time, event time if different, affected asset, source confidence and duplicate-cluster ID. A model's statement without a verifiable source remains `UNVERIFIED`.

---

## 8. Sentiment and news: useful, but not a magic price oracle

The APK contains `NewsEventGateEngine` and `CrowdAttentionProxySnapshot`-related symbols. This suggests data structures or logic exist, but the APK alone does not prove that a robust live news/social data pipeline is connected.

### 8.1 Recommended signal layers

1. **Exchange/project events:** listing, delisting, maintenance, contract changes, token unlocks, incidents and official announcements.
2. **Macro events:** CPI, FOMC, central-bank statements and major economic releases, with actual scheduled timestamps.
3. **Trusted news:** reputable outlets and source-verifiable reporting.
4. **Crowd attention:** approved social/news APIs; do not rely on unsupported scraping or bot-heavy counts.
5. **Broad sentiment index:** Fear & Greed can provide slow-moving context, not a per-coin 1m entry signal.

Alternative.me publishes a Fear & Greed API and requires attribution near displayed data. Its index is broad market sentiment; it does not replace contract-level Binance data.

### 8.2 Sentiment processing

For every item:

- normalize publisher/source and URL;
- deduplicate copied articles and syndicated posts;
- distinguish publication time from event time;
- detect whether it is confirmed, alleged, rumor or opinion;
- classify affected symbols and confidence;
- score positive/negative/neutral, magnitude and likely time horizon;
- preserve quoted text only as untrusted data;
- flag stale, contradictory or unverifiable items;
- calculate a decay function so old news loses influence.

### 8.3 How sentiment should affect trading

- Verified high-impact adverse event: block new entries or reduce allowed risk according to a deterministic event policy.
- Unverified rumor: do not create a directional trade signal; optionally increase uncertainty and monitor.
- High attention with widening spread: block or reduce size due to execution risk.
- Positive sentiment alone: never auto-LONG.
- Negative sentiment alone: never auto-SHORT.
- Sentiment conflicting with price/volume: ask Skeptic to inspect the conflict; do not blindly average it into a score.

For a fast intraday strategy, sentiment should primarily act as **an event-risk filter and explanation layer**, not the main trigger.

---

## 9. Self-learning: what Astra should learn, and where

“Self-learning” must be separated into three different jobs:

1. **Online observation:** continuously collect data and record what happened.
2. **Performance learning:** calculate which setups, regimes, coins and execution conditions work or fail.
3. **Model retraining:** train new model weights from versioned, validated historical datasets.

The first two can run in lightweight form on the S7. The third should run off-device.

### 9.1 What to record for every candidate

- complete input snapshot and data provenance;
- strategy/model version;
- regime and feature vector;
- AI provider/model outputs and token/latency records;
- why the candidate passed or failed each gate;
- entry, stop, target and expiry plan;
- market outcome for fixed horizons, whether or not a trade was taken;
- if traded: order IDs, fill IDs, fill prices/quantities, fees, funding, slippage, realized PnL;
- if rejected: subsequent price path for counterfactual evaluation;
- post-trade classification of error or success reason.

Recording rejected candidates matters. Otherwise the system only learns from trades it chose to take and cannot measure missed opportunities or false positives.

### 9.2 Labeling

Use explicit outcome labels, such as:

- hit stop before target;
- hit target before stop;
- neither barrier reached within horizon;
- net positive after fees and slippage;
- net negative after fees and slippage;
- invalid because data/execution conditions failed.

A triple-barrier or similarly well-defined labeling method can be researched, but it must be fixed before evaluating the model. Never let the label depend on future data unavailable at decision time.

Separate **forecast correctness** from **trade profitability**. A forecast may be directionally correct but untradeable after fees; a trade may profit despite a weak forecast because of lucky execution.

### 9.3 Model training workflow

1. Download authentic historical data.
2. validate and deduplicate it;
3. build causal features;
4. create labels with explicit execution-cost assumptions;
5. split chronologically, not randomly;
6. train candidate model externally;
7. calibrate on separate validation data;
8. run walk-forward and out-of-sample tests;
9. compare with a simple baseline and the current champion;
10. package a signed, versioned artifact;
11. run on-device shadow inference;
12. promote only after predefined acceptance metrics pass;
13. retain the prior artifact for rollback.

### 9.4 No automatic production self-modification

Astra must not rewrite its own strategy thresholds, risk limits, leverage or production weights just because recent trades lost money. It may propose a change, run it in an isolated candidate version and report results. A promotion gate must approve the candidate before deployment.

### 9.5 Minimum validation dashboard

Track by symbol, timeframe, strategy and market regime:

- Brier score and log loss;
- expected calibration error (ECE);
- precision/recall for the actual trade class;
- ROC/PR metrics where appropriate;
- mean absolute error only for a clearly defined regression target;
- net expectancy after realistic costs;
- profit factor;
- maximum drawdown;
- average win/loss and win rate;
- exposure/time in market;
- turnover and fees as a fraction of gross PnL;
- trade count and confidence intervals;
- performance decay/drift;
- comparison against simple baselines.

A tiny sample must show `INSUFFICIENT_SAMPLE`, not a green success badge. A score of zero on a tiny or leaked validation sample is not evidence of perfect prediction.

---

## 10. Execution: the order must be real, protected and reconciled

This is a critical safety subsystem. The current APK contains execution-related components, but static presence is not enough.

### 10.1 Required order state machine

Use explicit, durable states:

`CANDIDATE → RISK_APPROVED → INTENT_PERSISTED → SUBMITTING → UNKNOWN/ACKNOWLEDGED → PARTIALLY_FILLED/FILLED → PROTECTION_PENDING → PROTECTED → CLOSING → CLOSED`

Error/recovery states:

`REJECTED`, `EXPIRED`, `CANCEL_PENDING`, `RECONCILIATION_REQUIRED`, `UNPROTECTED_POSITION`, `MANUAL_INTERVENTION_REQUIRED`.

Every transition must be persisted before or immediately after the external action so a process death can recover safely.

### 10.2 Pre-order checks

- account and API permissions valid;
- correct Binance environment (demo/test vs production);
- exchange time synchronized;
- account equity and available margin fresh;
- account position mode known (One-way or Hedge);
- margin mode and leverage state verified;
- symbol status `TRADING`;
- `PRICE_FILTER`, `LOT_SIZE`, `MARKET_LOT_SIZE`, `MIN_NOTIONAL` and applicable filters current;
- price/quantity rounded using exchange steps, not floating-point approximation;
- spread and estimated market impact acceptable;
- entry and stop remain valid after rounding;
- quantity does not exceed risk/portfolio caps;
- no unresolved order intent or reconciliation discrepancy;
- candidate is not expired;
- protective-order path available.

### 10.3 Idempotency and ambiguous outcomes

- Generate a unique client order ID for every intent.
- Persist intent before sending.
- If the request times out or returns an execution-unknown response, **do not blindly resend**.
- Query by client order ID and/or exchange order ID; inspect User Data Stream updates; then reconcile open orders, fills and position state.
- Only submit a replacement if the old intent is proven not to have created an order and the strategy candidate is still valid.
- Use a separate client ID namespace for entry, stop, take profit and emergency close intents.

### 10.4 Fill handling and protection

- On partial fill, compute actual filled quantity and weighted average fill price.
- Protect the filled quantity immediately; do not wait for the remaining quantity to fill.
- Place exchange-hosted stop protection before optional profit-taking complexity.
- Confirm stop order/algo order exists, has correct symbol, side, position side, trigger source and reduce-only/close-position behavior.
- If stop placement fails or cannot be confirmed, attempt a deterministic reduce-only close of the actual exposed quantity and block all new entries.
- If protection status is unknown, set `UNPROTECTED_POSITION` and trigger urgent notification/reconciliation.
- Handle partial stop/TP fills and prevent closing more than the open position.
- Never treat a local stop watcher as a substitute for exchange-hosted protection.

Binance's USDⓈ-M documentation now describes conditional/algo order interfaces, including an algo order placement route. The implementation must follow the current official USDⓈ-M API contract at build time; do not copy old endpoint assumptions from stale tutorials.

### 10.5 WebSocket and REST reconciliation

- Use User Data Stream for timely order/account updates.
- Use REST for initial snapshot, gap recovery and reconciliation.
- Reconnect proactively before the documented connection lifetime limit and refresh listen keys on schedule.
- After reconnect, fetch account, positions, open standard orders, open algo orders and recent fills.
- Compare exchange state with local database. Exchange state wins for actual position/order facts.
- Any mismatch blocks new entries until reconciled.

### 10.6 Kill switches

At least four levels:

1. **Pause entries:** stop new orders, continue managing open positions.
2. **Drain:** cancel eligible unfilled entry orders, keep protective orders and monitor existing positions.
3. **Emergency reduce/close:** close exposure using a verified reduce-only/close-position path, then reconcile.
4. **Credential/API lockdown:** if key compromise is suspected, stop signing new requests and instruct the operator to revoke the key through Binance.

Panic actions must not depend on an AI response. Telegram panic should require authentication and a confirmation challenge for destructive actions, but once confirmed it must invoke deterministic code.

---

## 11. Binance API, reliability and account security

### 11.1 API key policy

- Create a dedicated Futures API key for Astra.
- Enable only required read and Futures trading permissions.
- **Disable withdrawals and transfers** unless a separate, clearly justified function requires them; this bot should not need withdrawal permission.
- Use IP restrictions where a stable trusted egress IP is available. Do not use an unreliable rotating proxy to pretend to have a stable identity.
- Never put API key/secret in AI prompts, Telegram messages, logs, crash reports or exported diagnostics.
- Store secrets using Android Keystore-backed encryption where compatible; understand that a rooted/compromised device changes the threat model.
- Provide a one-touch emergency credential revocation checklist.

### 11.2 Rate limits and backoff

- Read current rate-limit metadata and endpoint weights from official docs/response headers.
- Maintain a central request budget for the app process.
- Batch/stream market data where supported instead of REST polling every symbol.
- On 429, back off and reduce request volume.
- On 418, stop retry loops and respect the ban window.
- Use exponential backoff with jitter for recoverable reads, but do not apply blind retries to order-placement calls with ambiguous execution state.
- Give risk-reducing cancel/close actions priority over scanner requests.

### 11.3 Server time and signed requests

- Query Binance server time and estimate clock offset.
- Track offset and round-trip latency, not just local wall-clock time.
- Reject signed trading actions if clock drift exceeds the configured safety threshold.
- Keep a strict recvWindow policy and avoid generating signed requests far ahead of send time.

### 11.4 API version drift

Add an `ExchangeCapabilityRegistry`:

- verified API base URLs for production and demo;
- supported standard/algo order routes;
- supported order types and flags;
- required filters and current schemas;
- stream payload schema version;
- test timestamp and capability test result.

At startup, fail closed if the app depends on an endpoint or field that has changed and has not been verified.

---

## 12. Samsung Galaxy S7 Edge / LineageOS Android 11 constraints

The S7 Edge is a 2016-era Exynos 8890 device with 4 GB RAM. It can run a lightweight event-driven trading monitor and compact deterministic computation, but it is not a sensible host for continuous large-model training or a multi-agent local LLM stack.

### 12.1 What should run on the phone

- one lightweight market-data collector;
- one combined market WebSocket connection for selected streams/symbols where possible;
- one user-data stream connection;
- compact feature calculations;
- simple regime detector and baseline strategy;
- small CPU model inference only if benchmarking proves it is useful;
- risk engine and order state machine;
- SQLite/Room data store;
- Telegram command and notification worker;
- watchdog/health monitor.

### 12.2 What should not run on the phone

- model training on large historical data;
- heavy backtests across hundreds of symbols;
- a local general-purpose LLM;
- multiple neural models running continuously;
- massive full-depth order-book storage for every symbol;
- unrestricted social-media scraping;
- GPU-heavy inference pipelines that compete with networking and order execution.

### 12.3 Runtime resource budget

Use measured budgets, not assumed capacity:

| Resource | Proposed initial guardrail |
|---|---|
| Forecast inference | At most 1–2 CPU threads; run only on qualified candidates or scheduled snapshots |
| Active market universe | Start with 8–15 symbols; benchmark and reduce if thermal/RAM pressure appears |
| AI calls | Event-driven, only for shortlisted candidates |
| Local history | 5m/15m candles and compact features; bounded retention for high-frequency data |
| Database | SQLite/Room with WAL, indexes, periodic checkpoint and corruption recovery test |
| Background logs | Rotate and cap size; never let logs fill storage |
| CPU/thermal | Monitor temperature, throttling, inference latency and missed stream events |
| Memory | Set a hard memory ceiling and degrade gracefully; do not let scanner compete with execution |

These are starting engineering constraints. Measure actual process RSS, CPU, temperature and latency on the exact ROM/device before choosing final limits.

### 12.4 Android 11 reliability

Android Doze and App Standby can defer background network and execution activity when the device is idle. A foreground service makes the work more visible and improves continuity, but does not make a phone an always-on server with guaranteed uptime. ROM-specific battery management and process killing also matter.

Required measures:

- foreground service with an ongoing notification while trading service is active;
- user-visible battery-optimization guidance where legally and technically appropriate;
- boot recovery that first reconciles exchange state before enabling scanner/entries;
- WebSocket heartbeat, timeout, reconnect and stream-gap detection;
- database checkpointing and crash-safe intent persistence;
- thermal and low-memory fallback mode;
- if the process restarts, remain `PAUSED` until account/positions/orders are reconciled;
- if the phone sleeps or network pauses, keep exchange-hosted protective orders in place;
- long-duration tests with screen off, charging, Wi-Fi loss, mobile-network changes and Doze.

### 12.5 Practical operating recommendation

For initial use, keep the S7 plugged into stable power, adequately ventilated and on reliable Wi-Fi, but monitor battery health and temperature. An old battery under continuous heat is a reliability and safety risk. Do not hide failures by keeping the screen awake forever.

If the app must manage funds unattended 24/7, a small stable VPS is a safer execution host than a 2016 phone. The S7 can remain a remote dashboard/Telegram console or lightweight fallback monitor. If the phone is non-negotiable, limit scope and accept that downtime must trigger `PAUSE`, not autonomous catch-up trading.

---

## 13. Telegram: notifications, commands and security

### 13.1 Outbound notifications

Use a durable `NotificationOutbox` so a Telegram outage does not erase critical alerts. Each message gets an event ID, severity, created time, delivery attempts and final delivery state.

Required event types:

- service started/recovered;
- data feed stale or disconnected;
- candidate found/rejected and reason;
- order submitted/unknown/rejected;
- partial fill/complete fill;
- stop/TP placement and verification;
- position closed and net PnL;
- daily/weekly risk stop;
- circuit breaker activation;
- reconciliation mismatch;
- device temperature/low storage/low memory;
- AI provider timeout or quota failure;
- model drift or forecast disabled.

### 13.2 Inbound commands

Suggested commands:

| Command | Action |
|---|---|
| `/status` | Service, connection, mode, data freshness and health |
| `/positions` | Exchange-verified open positions and protective orders |
| `/pnl` | Net PnL, fees, funding and equity change |
| `/why` | Why last candidate passed/failed each gate |
| `/pause` | Stop new entries, continue managing open positions |
| `/drain` | Cancel unfilled entry orders and keep protective orders |
| `/reconcile` | Force account/order/position reconciliation |
| `/report` | Daily/weekly report |
| `/panic` | Authenticated, confirmed emergency procedure |
| `/help` | Command list |

`/resume` should not immediately enable live entries by itself. It should require local checks, clean reconciliation and explicit operator confirmation.

### 13.3 Access controls

- strict Telegram user ID and chat ID allowlist;
- reject group chats unless explicitly configured;
- persist processed update IDs to prevent replay;
- rate-limit commands;
- never expose API secrets or full account credentials in messages;
- require confirmation for panic/close actions;
- log every command and result;
- if Telegram is down, internal risk/execution logic continues independently.

---

## 14. Storage and internal module boundaries

Recommended logical modules:

| Module | Responsibility |
|---|---|
| `ExchangeMarketData` | Binance public market streams/REST backfill |
| `ExchangeAccountSync` | Account, fills, positions, orders and reconciliation |
| `ContractRegistry` | Exchange metadata, filters, capabilities and symbol eligibility |
| `DataQualityEngine` | Freshness, gaps, duplicates, timestamp/schema validation |
| `FeatureEngine` | Indicators and compact causal features |
| `MarketRegimeEngine` | Trend/range/volatility regime |
| `UniverseScanner` | Dynamic symbol ranking and candidate selection |
| `StrategyEngine` | Breakout/momentum/retest setup |
| `ForecastEngine` | Small model inference and calibration checks |
| `SentimentEngine` | Verified news/event/sentiment snapshots |
| `AiProviderRouter` | Provider selection, prompt packing, schema validation and budgets |
| `SkepticEngine` | Independent contradiction/risk review |
| `NetEVEngine` | Cost-aware expected value |
| `PortfolioRiskManager` | Aggregate risk/correlation/exposure/margin limits |
| `RiskSizingEngine` | Position quantity, stop-risk and leverage eligibility |
| `ExecutionEngine` | Durable order intent, submission, protection and recovery |
| `CircuitBreaker` | Pause/drain/panic policies |
| `LearningJournal` | Candidate, outcome and decision history |
| `ModelRegistry` | Model artifacts, training metadata and rollback |
| `TelegramController` | Authenticated commands and durable notifications |
| `DeviceHealthMonitor` | Android process, memory, thermal, battery and network health |

Recommended core tables:

- `market_candles`
- `market_snapshots`
- `contract_metadata`
- `data_quality_events`
- `features`
- `candidate_signals`
- `ai_requests`
- `ai_responses`
- `risk_decisions`
- `order_intents`
- `exchange_orders`
- `fills`
- `positions`
- `trade_outcomes`
- `news_events`
- `sentiment_snapshots`
- `model_registry`
- `daily_metrics`
- `notification_outbox`
- `audit_log`

Every table needs schema versioning, timestamps and retention policy. Do not store the entire raw market feed forever on the phone.

---

## 15. Test plan and promotion gates

Astra must progress through modes. The mode should be explicit and visible everywhere.

`OFFLINE_TEST → DATA_BOOTSTRAP → SHADOW → PAPER → DEMO_EXECUTION → RESTRICTED_LIVE → LIVE`

A future mode transition must never occur automatically merely because the app has collected a few profitable sample trades.

### 15.1 Test stages

| Stage | Tests | Required result |
|---|---|---|
| 1. Static/build | Manifest, target API, permissions, service declarations, package integrity | No unexplained permission or configuration mismatch |
| 2. Data bootstrap | Historical backfill, gaps, timestamps, duplicate bars, source comparison | Data quality state passes and provenance is complete |
| 3. Feature tests | Golden vectors from trusted fixtures, numeric edge cases, missing values | Deterministic output within documented tolerance |
| 4. Forecast validation | Chronological splits, calibration, OOD, symbol-specific metrics | No leakage; enough sample; metrics beat a simple baseline |
| 5. Strategy backtest | Fees, funding, slippage, order delay, partial fills, no-lookahead | Positive out-of-sample net expectancy with confidence intervals |
| 6. AI integration | Valid/invalid JSON, timeouts, provider outage, prompt injection | No malformed or untrusted AI output reaches execution |
| 7. Risk simulation | Small account, min notional, high leverage request, correlated positions | Risk budget never exceeded |
| 8. Execution fault injection | Timeout after accepted order, partial fill, rejected stop, WS disconnect | No duplicate entries; exposure is protected or reduced |
| 9. Binance demo | Actual API filters, conditional/algo orders, stream recovery | Exchange and local state reconcile correctly |
| 10. Android endurance | Screen off, Doze, reboot, memory pressure, heat, storage full, Wi-Fi change | Safe pause/recovery; no silent orphan position |
| 11. Paper trading | Live market, no real orders | Stable execution simulation, meaningful sample and net metrics |
| 12. Restricted live | Lowest practical risk and hard loss limits | Only after all prior gates pass and operator approves |

### 15.2 Minimum evidence before restricted live

Suggested initial gates, to be calibrated with more data:

- at least 8–12 weeks of shadow/paper operation to expose operational failures;
- several hundred strategy candidates and a meaningful number of executed paper trades, not only a few wins;
- results evaluated on chronological holdout and multiple market regimes;
- positive net expectancy after conservative fees/slippage/funding assumptions;
- drawdown within predeclared cap;
- no unresolved order/position reconciliation bugs;
- 100% of opened paper positions have a valid protective-order state in the simulation;
- no critical Android restart/Doze scenario causes an unprotected live-equivalent position;
- all model/provider/API failure modes fail closed.

The exact sample size depends on trade frequency and variance. A small sample with an attractive win rate is not sufficient proof.

### 15.3 Must-run fault scenarios

1. Binance accepts order but response times out.
2. App process dies after exchange accepted entry but before local ACK.
3. Entry partially fills, then remaining quantity expires.
4. Stop order placement returns error.
5. Stop request times out but may have been accepted.
6. WebSocket drops at funding time.
7. REST returns stale account state during volatility.
8. Server time offset drifts.
9. API returns 429 or 418.
10. A symbol changes filters or becomes non-tradable.
11. Telegram is unreachable.
12. AI provider returns malformed JSON or prompt-injected news.
13. Device enters Doze or the process is killed.
14. Device storage becomes full or database is corrupt.
15. Mark price and contract price diverge sharply.
16. Quantity rounding pushes risk above the cap.
17. A position crosses the daily accounting boundary.
18. A coin suddenly becomes illiquid or spread expands.

---

## 16. Build roadmap: implement in this order

### Phase A — Remove false confidence

- quarantine demo/synthetic fixtures;
- mark the production database as empty on first launch;
- expose data trust state;
- audit all model manifests and forecast outputs;
- correct the TFLite/Kotlin artifact naming mismatch;
- disable LIVE by default.

### Phase B — Reliable data foundation

- implement Binance USDⓈ-M market-data bootstrap;
- fetch exchange metadata and filters dynamically;
- backfill selected liquid symbols;
- build data provenance and quality checks;
- add WebSocket reconnect and gap repair;
- implement bounded SQLite retention.

### Phase C — One baseline strategy

- implement 1h regime + 15m/5m setup + 1m/5m entry trigger;
- add liquidity/spread/volume filters;
- add no-lookahead feature calculation;
- compute fee/slippage/funding-aware EV;
- log all candidates, including rejected ones.

### Phase D — Risk and execution correctness

- implement PortfolioRiskManager;
- make sizing and leverage decisions deterministic;
- add durable order intent and client ID handling;
- add current Binance conditional/algo stop integration;
- protect partial fills immediately;
- implement reconciliation and kill-switch states.

### Phase E — External AI and sentiment

- implement one primary analyst and one independent skeptic;
- create strict compact context/response schemas;
- add provider health/cost/latency routing;
- add source-verifiable event/news pipeline;
- make AI advisory-only and never allow it to bypass risk gates.

### Phase F — Learning and validation

- implement LearningJournal;
- implement chronological backtest/walk-forward evaluation;
- train candidate models off-device;
- calibrate per symbol/regime only when sample supports it;
- add champion/challenger and rollback;
- run shadow and paper trading.

### Phase G — S7 optimization and endurance

- benchmark CPU/RAM/thermal/inference latency;
- restrict active symbols and data rates;
- test Android 11 Doze, reboot and process death;
- test database recovery;
- verify exchange-side stops survive device loss;
- consider VPS execution if unattended uptime is a requirement.

---

## 17. Prioritized change list for the current APK

### P0 — Must fix before any real money

1. Treat all bundled forecast samples as untrusted test fixtures until independently validated.
2. Replace the 5.8-day / 560-bar evidence base with a genuine historical research dataset.
3. Rebuild calibration from a large chronological holdout; remove any green/qualified label based on 23 samples.
4. Resolve the 4h direction-vs-return output inconsistency through documented head semantics and tests.
5. Verify the supported-symbol list, feature availability and per-symbol calibration.
6. Validate Binance current algo/conditional order contract and protective stop behavior.
7. Verify ambiguous order-timeout handling and client-ID idempotency.
8. Verify stop protection after partial fills and after app restart.
9. Add durable exchange/local reconciliation before entries can resume.
10. Add portfolio-level risk and correlated-position controls.
11. Add no-trade rules when minimum notional conflicts with risk budget.
12. Ensure no user/API secret ever reaches AI or Telegram content.

### P1 — High-value additions

- `DataTrustManager`
- `DynamicContractRegistry`
- `DataQualityEngine`
- `PortfolioRiskManager`
- `ExecutionFailureSimulator`
- `NotificationOutbox`
- `ModelChampionChallenger`
- `ModelDriftMonitor`
- `ProviderPerformanceRouter`
- `SentimentEventVerifier`
- `DeviceHealthMonitor`
- `ColdStartBootstrapper`

### P2 — Defer until the core system is proven

- reinforcement learning;
- full Kelly position sizing;
- VPIN and large order-flow models;
- local general-purpose LLM;
- many simultaneous neural architectures;
- autonomous self-editing strategies;
- social scraping at scale;
- dozens of overlapping AI agents;
- multi-strategy portfolio optimization.

These may be revisited only when the system has high-quality data, validated execution and enough samples to justify the added complexity.

---

## 18. Acceptance criteria for the developer/AI coding agent

The implementation is not complete until all of the following are demonstrated with tests or reproducible logs:

- [ ] Fresh installation starts with no trusted user trade history.
- [ ] Bundled demo/synthetic data is visibly tagged and cannot contaminate production training/PnL.
- [ ] Historical candles are downloaded from a verifiable Binance source and validated.
- [ ] The app dynamically discovers eligible USDT perpetuals using current exchange metadata.
- [ ] Unsupported symbols are rejected or explicitly handled by an approved baseline.
- [ ] All technical features are deterministic and time-causal.
- [ ] No strategy is evaluated with lookahead bias.
- [ ] Fees, funding and slippage are included in net EV and backtests.
- [ ] Size comes from loss budget and stop distance, not from target return.
- [ ] The app refuses trades when exchange minimums violate the risk budget.
- [ ] AI responses are strict-schema validated and tied to the original decision ID.
- [ ] AI cannot set quantity/leverage or submit an exchange order.
- [ ] Model/provider outages cannot bypass risk gates.
- [ ] Every order intent is durable and idempotent.
- [ ] Unknown order results are reconciled before retry.
- [ ] Partial fills receive exchange-hosted protection immediately.
- [ ] Failed/unverified protection blocks new entries and triggers recovery.
- [ ] WebSocket gaps cause REST reconciliation.
- [ ] App restart begins paused until exchange state is reconciled.
- [ ] Daily loss, weekly drawdown and portfolio limits are deterministic.
- [ ] Telegram commands are authenticated and replay-protected.
- [ ] Notification failures do not suppress internal risk actions.
- [ ] S7 Edge passes endurance, Doze, restart, thermal and low-memory tests.
- [ ] Paper results are net-positive out of sample with adequate sample size before any restricted-live promotion.
- [ ] LIVE mode requires an explicit operator action and cannot be activated by an AI response or Telegram text alone.

---

## 19. Master implementation instruction for the coding agent

> **Task:** Audit and upgrade the existing Astra Trader Android project into a safe, lightweight Binance USDⓈ-M Futures intraday trading system for small capital, targeting Samsung Galaxy S7 Edge / Exynos 8890 / 4 GB RAM / LineageOS Android 11.
>
> **Non-negotiable constraints:**
> 1. Do not assume the current APK has ever run correctly or that it contains trustworthy user history.
> 2. Treat all bundled sample candles, golden vectors, sample PnL and AI-generated examples as test fixtures unless their origin is independently verified.
> 3. Do not promise or force +1% daily. It is an aspirational performance benchmark only. Never loosen filters or increase leverage to chase it.
> 4. Use Binance USDⓈ-M public market data and signed account/execution data as sources of truth. Never fabricate prices, fills, balances, funding or exchange filters.
> 5. Do not train large models or run a local general-purpose LLM on the S7 Edge. Keep the phone's work lightweight. Train candidate models off-device and ship versioned artifacts after validation.
> 6. AI may research, summarize, classify and challenge candidates. It may not calculate authoritative order quantity, override risk limits, change leverage, or place orders.
> 7. Every trade must have positive net expected value after conservative fees, slippage, funding and other costs, plus all risk gates passing.
> 8. Any unknown order state, unprotected position, stale data, reconciliation mismatch, clock drift, unsupported symbol or model OOD condition must block new entries.
>
> **Required work:**
> - Inspect the whole source project, not just the UI. Map the real call graph from scanner to signal, AI gateway, skeptic, risk sizing, execution, Binance response, local persistence, reconciliation and Telegram.
> - For every major module, classify it as `IMPLEMENTED_AND_TESTED`, `IMPLEMENTED_NOT_TESTED`, `PARTIAL`, `PLACEHOLDER`, `UNUSED` or `MISSING`. Cite file paths, classes, functions and tests. Do not infer functionality from class names alone.
> - Create a clean first-run bootstrap and data-trust state machine. Quarantine demo/synthetic data.
> - Build a dynamic USDⓈ-M contract registry and a bounded liquid-symbol scanner.
> - Use one initial strategy: 1h regime filter + 15m/5m breakout/momentum/volume setup + 1m/5m execution trigger. Keep all thresholds configurable and validated by chronological tests.
> - Add data provenance, gap detection, timestamp normalization, closed-candle checks and REST/WebSocket reconciliation.
> - Audit the current forecast bundle, class-label ordering, direction/regression heads, output scaling, calibration, OOD behavior and symbol support. Fix inconsistencies and add golden tests. Do not claim the model is accurate without independent out-of-sample evidence.
> - Add cost-aware NetEV, deterministic sizing, leverage/margin/liquidation checks and portfolio-level correlation/exposure limits.
> - Add robust order lifecycle, unique client IDs, unknown-outcome handling, partial-fill protection, exchange-hosted stop orders, reconciliation and kill switches.
> - Verify the latest official Binance USDⓈ-M endpoints and schemas at implementation time. Do not blindly reuse obsolete endpoint paths or parameter names.
> - Implement AI provider routing with a small number of roles, compact schemas, strict parsing, token/cost budgets, timeouts and independent skepticism. Never send secrets or raw account credentials.
> - Build sentiment/news only from timestamped, source-verifiable information. Use it as event-risk context, not an automatic directional signal.
> - Implement a learning journal, chronological walk-forward evaluation, champion/challenger model registry and rollback. Train off-device.
> - Optimize for the S7: bounded symbol count, limited WebSockets, compact SQLite storage, 1–2 model CPU threads at most, no local LLM, thermal/memory guards and safe Android 11 recovery.
> - Audit Telegram authentication, replay protection, durable notifications and panic confirmations.
>
> **Required deliverables:**
> 1. Architecture and call-graph audit of the existing source.
> 2. Defect/omission register with severity, evidence, impact and exact proposed fix.
> 3. Updated implementation plan with file-level tasks and dependencies.
> 4. Data schema and API contract definitions.
> 5. Unit, integration, property-based and fault-injection tests.
> 6. Backtest and paper-trading report with net costs and sample-size caveats.
> 7. S7 Edge benchmark report: CPU, RAM, temperature, storage, network reconnection and background survival.
> 8. Explicit mode-transition rules and acceptance checklist.
>
> **Self-review requirement:** Before declaring completion, inspect your own changes for race conditions, stale data, duplicated orders, schema drift, timestamp errors, rounding errors, memory leaks, process-death recovery gaps, unprotected partial fills, AI prompt injection, false-positive model confidence and incorrect PnL accounting. Create tests that reproduce each identified risk. If a feature cannot be verified, mark it `NOT_VERIFIED` and keep it disabled in LIVE mode.
>
> **Definition of done:** A clean install can collect authentic data, explain why it does or does not trade, run in shadow and demo modes, survive network/process failure without losing exchange-side protection, reconcile exchange state, and produce an honest net-performance report. Profitability must be demonstrated by robust out-of-sample and paper evidence, never asserted from code structure or a few sample trades.

---

## 20. Research references

Official and primary references checked on 31 October 2026. API schemas and exchange rules can change; verify the exact USDⓈ-M product page and response schema again when coding.

1. **Binance USDⓈ-M Futures General Info** — base endpoint, demo environment, signed requests, unknown execution outcomes and retry behavior.  
   https://developers.binance.com/en/docs/products/derivatives-trading-usds-futures/general-info
2. **Binance USDⓈ-M Futures Market Data** — server time, market data and funding-rate history.  
   https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data
3. **Binance USDⓈ-M Futures User Data Streams** — account/order events, keepalive and connection lifetime/recovery guidance.  
   https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/ws-api/user-data-streams
4. **Binance USDⓈ-M Futures Trade WebSocket API** — current algo/conditional order placement interface.  
   https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/ws-api/trade
5. **Binance USDⓈ-M Futures Common Definitions** — contract filters, order constraints and rate-limit definitions.  
   https://developers.binance.com/en/docs/products/derivatives-trading-options/common-definition
6. **Binance Futures Funding Rates** — funding payment calculation and settlement concepts.  
   https://www.binance.com/en/support/faq/detail/360033525031
7. **Binance Futures Fees** — commission, funding and liquidation-related fee categories; actual commission depends on account/market conditions.  
   https://www.binance.com/en-AE/support/faq/detail/98488a516eb84e3eb34605683dffd554
8. **Binance Public Data repository** — historical USD-M Futures klines, aggTrades and downloadable public archives.  
   https://github.com/binance/binance-public-data
9. **Alternative.me Fear & Greed API** — endpoint and attribution requirements.  
   https://alternative.me/crypto/fear-and-greed-index/
10. **Android Developers: Optimize for Doze and App Standby** — background network/execution deferral on idle devices.  
    https://developer.android.com/training/monitoring-device-state/doze-standby
11. **Android Developers: Background optimization** — battery restriction states and manufacturer-dependent behavior.  
    https://developer.android.com/topic/performance/background-optimization
12. **Android Developers: Power management resource limits** — execution/network limits by app and device state.  
    https://developer.android.com/topic/performance/power/power-details.html
13. **LineageOS Samsung Galaxy S7 Edge device configuration** — Exynos 8890, Mali-T880, 4 GB RAM and hardware baseline.  
    https://github.com/LineageOS/android_device_samsung_hero2lte

---

## Final recommendation

Build Astra around **verified data → deterministic signal → independent challenge → deterministic risk sizing → exchange execution → exchange-side protection → reconciliation → learning journal**.

Do not build around **AI prediction → leverage → hope**.

For the S7 Edge, keep runtime computation lean and move model training off-device. For the small account, accept that some days and some coins will be untradeable after fees and exchange minimums. For the +1% daily aspiration, measure it honestly and let compounding happen only when genuine net profits are earned.

The first job of Astra is not to make money every day. It is to remain alive, know what it is doing, and never risk more than its rules permit while waiting for a real statistical edge.
