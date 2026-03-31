# Day Trader Stock Scanner — Project Specification

> **Version**: 3.0
> **Last updated**: 2026-03-31
> **Status**: Ready for development
> **Architecture style**: Event-driven, real-time streaming engine

-----

## Table of contents

| # | Section | What it covers |
|---|---------|----------------|
| [1](#1-project-overview) | **Project overview** | What this system is (and isn't) |
| [2](#2-system-architecture) | **System architecture** | High-level data flow, design principles |
| [3](#3-domain-model) | **Domain model** | Ticker, Candle, Signal, Trade, MarketSession, enums |
| [4](#4-event-system) | **Event system** | Event types, event bus, full alert flow walkthrough |
| [5](#5-stateful-runtime) | **Stateful runtime** | Hot/cold storage, rolling calcs, cleanup, memory mgmt, crash recovery |
| [6](#6-scheduling-and-orchestration) | **Scheduling & orchestration** | Task types, main loop, API key rotation, budget, retry/failover |
| [7](#7-dynamic-watchlist-engine) | **Dynamic watchlist engine** | Pre-market gapper scan, top movers, watchlist merge |
| [8](#8-scoring-engine) | **Scoring engine** | Metrics, normalization, dynamic weights, composite score, alert rules |
| [9](#9-strategy-engine) | **Strategy engine** | ORB, VWAP Pullback, VWAP Breakout, Gap & Go, timing matrix, sizing |
| [10](#10-market-regime-detection) | **Market regime detection** | Trending/choppy/volatile/low_vol, detection logic, adjustments |
| [11](#11-catalyst-and-news-integration) | **Catalyst & news** | News sentiment, earnings, short squeeze, score boosts |
| [12](#12-liquidity-filter) | **Liquidity filter** | Float, spread, dollar volume, grading, slippage |
| [13](#13-float-and-short-interest-analysis) | **Float & short interest** | Float scoring, float rotation, short squeeze detection, dilution risk |
| [14](#14-technical-confirmation-layer) | **Technical confirmation** | RSI, MACD, support/resistance, moving averages, multi-timeframe |
| [15](#15-market-cap-tier-system) | **Market cap tiers** | Small-cap vs mid/large-cap parameter sets, behavior differences |
| [16](#16-risk-management) | **Risk management** | Daily loss limit, concurrent signals, position sizing, prioritization |
| [17](#17-notification-system) | **Notification system** | Gmail, Telegram, Desktop, alert format, signal updates |
| [18](#18-market-configuration) | **Market configuration** | US (full), HK/SG/JP/TW/UK/EU (templates) |
| [19](#19-performance-and-latency-constraints) | **Performance & latency** | SLAs, data freshness, throughput |
| [20](#20-backtesting) | **Backtesting** | v1.0 log replay, v2.0 full replay upgrade path |
| [21](#21-configuration-schema) | **Configuration schema** | Full field-by-field config.json reference |
| [22](#22-external-service-registration) | **Service registration** | Signup guide for all APIs |
| [23](#23-file-structure) | **File structure** | Python files + results directory |
| [24](#24-development-sequence) | **Development sequence** | Build order |
| [25](#25-future-enhancements-v20) | **Future enhancements** | v2.0+ roadmap |

-----

## 1. Project overview

A real-time, event-driven stock scanning engine that identifies high-potential day trading candidates across multiple markets. The system continuously processes market data ticks, **dynamically discovers stocks in play each morning**, scores them against professional day-trading metrics, detects trading setups, calculates actionable entry/exit levels, and pushes alerts through multiple notification channels.

This is **not** a request/response web app. It is a **streaming data pipeline** with in-memory state, rolling calculations, and time-sensitive decision logic.

**Key philosophy**: Day trading is about finding *what's moving today*, not trading a static list. This system combines a base watchlist of liquid favorites with a dynamic discovery engine that identifies pre-market gappers, unusual volume movers, and short squeeze candidates fresh each session.

Primary market: **US** (fully built). Architecture supports: **HK, SG, JP, TW, UK, EU**.

-----

## 2. System architecture

### 2.1 High-level data flow

```
┌──────────────┐     ┌──────────────┐
│ Alpha Vantage │     │   Finnhub    │
│  (REST, daily │     │ (REST/poll,  │
│   + history)  │     │  real-time)  │
└──────┬───────┘     └──────┬───────┘
       │                     │
       └────────┬────────────┘
                │
        ┌───────▼────────┐
        │  DATA INGESTION │
        │  (normalized    │
        │   tick events)  │
        └───────┬────────┘
                │
         emit: PriceTick, DailyBar, NewsEvent, EarningsEvent,
               GapperEvent, ShortInterestEvent
                │
        ┌───────▼────────┐
        │   EVENT BUS     │
        │  (in-process    │
        │   pub/sub)      │
        └───┬───┬───┬──┬─┘
            │   │   │  │
    ┌───────▼┐ ┌▼──────┐ ┌▼──────────┐ ┌▼──────────────┐
    │DYNAMIC │ │SCORING│ │  REGIME   │ │  TECHNICAL    │
    │WATCHLST│ │ENGINE │ │ DETECTOR  │ │  CONFIRMATION │
    │ ENGINE │ └──┬────┘ └────┬──────┘ └──────┬────────┘
    └───┬────┘    │            │               │
        │         │            │               │
    ┌───▼────┐    │            │               │
    │LIQUIDITY│   │            │               │
    │+ FLOAT  │   │            │               │
    │ FILTER  │   │            │               │
    └───┬────┘    │            │               │
        │    emit: ScoreUpdate │               │
        │         │            │               │
        │    ┌────▼─────┐     │               │
        │    │ STRATEGY  │◄────┘───────────────┘
        │    │  ENGINE   │
        │    └────┬──────┘
        │         │
        │    emit: Signal
        │         │
        │    ┌────▼──────────┐
        │    │ STATE MANAGER  │
        │    │ + RISK MANAGER │
        │    └────┬──────────┘
        │         │
        │    emit: Alert (if passes all gates)
        │         │
        │    ┌────▼──────┐
        │    │ NOTIFIER   │
        │    │ Gmail/TG/  │
        │    │ Desktop    │
        │    └────┬──────┘
        │         │
        │    ┌────▼──────┐
        │    │  LOGGER    │
        │    │ CSV + JSON │
        │    └───────────┘
```

### 2.2 Core design principles

- **Event-driven**: Every piece of data flows as a typed event through the event bus. Components subscribe to events they care about and emit new events downstream.
- **Dynamic discovery**: The system rebuilds its watchlist every morning from pre-market gappers, unusual volume movers, and short squeeze candidates — not just a static ticker list.
- **Stateful runtime**: Rolling calculations (VWAP, RVOL, ATR, RSI, MACD) live in memory and update incrementally on each tick — not recomputed from scratch.
- **Market-cap aware**: Small-cap and large-cap stocks are treated with different parameter sets because they behave fundamentally differently.
- **Time-aware**: The system understands session phases, time windows, data freshness, and score decay. Stale data and stale moves are detected and handled.
- **Domain-modeled**: Core trading concepts (Ticker, Candle, Signal, Trade) are first-class entities with clear lifecycles.
- **Fail-safe**: All components degrade gracefully. If Finnhub goes down, the system falls back to Alpha Vantage. If all APIs fail, it preserves state and resumes.

-----

## 3. Domain model

### 3.1 Core entities

#### Ticker

Represents a tradeable instrument with its static profile and real-time state.

```
Ticker:
  symbol: str                     # "NVDA", "0700.HK"
  market: str                     # "US", "HK", "SG"
  name: str                       # "NVIDIA Corporation"
  sector: str                     # "Technology"
  market_cap: float
  market_cap_tier: str            # "SMALL", "MID", "LARGE" (see Section 15)
  shares_outstanding: int         # Total shares
  float_shares: int               # Public float (shares available for trading)
  short_interest_pct: float       # Short % of float
  short_ratio: float              # Days to cover (short interest / avg daily vol)
  has_dilution_risk: bool         # Active shelf/ATM offerings (see Section 13)
  liquidity_grade: str            # "A" through "F"
  is_tradeable: bool              # Passes liquidity filter
  catalysts: list[Catalyst]       # Active catalysts for today
  source: str                     # "BASE_WATCHLIST", "GAPPER", "TOP_MOVER", "SQUEEZE_CANDIDATE"
  gap_pct: float                  # Pre-market gap % from prev close
  pre_market_volume: int          # Volume traded before market open
  last_updated: datetime
```

#### Candle (OHLCV)

A single price bar at any resolution.

```
Candle:
  symbol: str
  timestamp: datetime
  resolution: str                 # "1m", "5m", "15m", "1d"
  open: float
  high: float
  low: float
  close: float
  volume: int
```

#### Signal

A trading opportunity identified by the strategy engine. Has a lifecycle.

```
Signal:
  id: str                         # UUID
  symbol: str
  strategy: str                   # "ORB", "VWAP_PULLBACK", "VWAP_BREAKOUT", "GAP_AND_GO"
  direction: str                  # "LONG", "SHORT"
  
  entry_price: float
  stop_loss: float
  target_1: float
  target_2: float
  risk_per_share: float
  reward_risk_ratio: float
  suggested_shares: int
  slippage_adj: float
  
  composite_score: float
  catalyst_boost: float
  float_score: float              # Float-based scoring component
  short_squeeze_score: float      # Short interest scoring component
  technical_confirmation: dict    # RSI, MACD, S/R levels
  regime: str
  session_phase: str
  liquidity_grade: str
  market_cap_tier: str            # "SMALL", "MID", "LARGE"
  
  status: SignalStatus            # See lifecycle below
  triggered_at: datetime
  closed_at: datetime | None
  close_reason: str | None        # "HIT_T1", "HIT_T2", "STOPPED", "EXPIRED", "MANUAL"
  result_pct: float | None
  
  conditions: dict                # VWAP aligned, volume confirmed, RSI/MACD, etc.
  key_levels: dict                # Nearby S/R, 50-day MA, 200-day MA, 52-wk high/low
  notes: str
```

Signal lifecycle:

```
DETECTED → PENDING → ACTIVE → HIT_T1 → HIT_T2 → CLOSED
                 │                │          │
                 │                └──→ STOPPED (hit stop loss)
                 │                └──→ EXPIRED (time/session stop)
                 └──→ REJECTED (failed risk check or dedup)
```

#### Trade (future — for broker integration)

```
Trade:
  id: str
  signal_id: str                  # Links to the Signal that triggered it
  symbol: str
  direction: str
  entry_price: float
  entry_time: datetime
  exit_price: float | None
  exit_time: datetime | None
  shares: int
  pnl: float | None
  status: str                     # "OPEN", "CLOSED", "CANCELLED"
```

#### MarketSession

Represents the current state of a market's trading session.

```
MarketSession:
  market: str                     # "US", "HK"
  phase: SessionPhase             # PRE_MARKET, OPENING_RANGE, MORNING, etc.
  regime: MarketRegime            # TRENDING, CHOPPY, VOLATILE, LOW_VOL
  is_open: bool
  time_since_open_minutes: int
  time_to_close_minutes: int
  
  orb_high: float | None          # Set after opening range completes
  orb_low: float | None
  pre_market_high: float | None
  pre_market_low: float | None
  prev_day_high: float
  prev_day_low: float
  prev_day_close: float
  
  # Sector snapshot for correlation checks
  sector_momentum: dict           # {"Technology": +1.2%, "Energy": -0.5%, ...}
```

### 3.2 Enumerations

#### SessionPhase

```
PRE_MARKET      — Before open: collect levels, discover gappers, build watchlist
OPENING_RANGE   — First 15 min: building ORB, no trades
MORNING         — 09:45–11:30: ORB + VWAP + Gap strategies active
MIDDAY          — 11:30–14:00: VWAP only, lower vol
POWER_HOUR      — 14:00–15:45: All strategies, second ORB window
CLOSING         — Last 15 min: flatten all, no new signals
AFTER_HOURS     — Market closed: log results, reset
MIDDAY_BREAK    — HK/SG/JP only: break between sessions
POST_BREAK      — First 15 min after midday break: second ORB
```

#### MarketRegime

```
TRENDING        — Strong directional day, favor ORB + breakouts
CHOPPY          — Range-bound, favor VWAP pullback only
VOLATILE        — High ATR no direction, widen stops
LOW_VOL         — Dead market, raise thresholds
```

#### SignalStatus

```
DETECTED        — Strategy engine identified a setup
PENDING         — Passed risk/state gates, alert sent, awaiting price action
ACTIVE          — Entry price approached or breached
HIT_T1          — Target 1 reached, trailing stop engaged
HIT_T2          — Target 2 reached, signal closed as winner
STOPPED         — Stop loss hit, signal closed as loser
EXPIRED         — Time stop or session close, signal closed flat
REJECTED        — Failed risk check, dedup, or cooldown gate
```

#### MarketCapTier

```
SMALL           — Market cap < $800M (wider stops, lower vol thresholds, float-sensitive)
MID             — Market cap $800M–$10B
LARGE           — Market cap > $10B (tighter spreads, higher vol requirements)
```

-----

## 4. Event system

### 4.1 Event types

All data flows through typed events. Each event carries a timestamp, source, and typed payload.

```
Event:
  type: str
  timestamp: datetime
  source: str                     # "alpha_vantage", "finnhub", "strategy_engine", etc.
  payload: dict
```

#### Market data events (ingestion → event bus)

| Event | Trigger | Payload | Frequency |
|-------|---------|---------|-----------|
| `PriceTick` | Finnhub quote poll | symbol, price, high, low, open, prev_close, volume, timestamp | Every 2min (normal) / 15s (fast) |
| `DailyBar` | Alpha Vantage daily call | symbol, OHLCV, 100-day history | 1× per stock at pre-market |
| `IntradayBar` | Finnhub candle call | symbol, 5-min OHLCV candle | Every 5 min for hot list |
| `NewsEvent` | Finnhub company-news | symbol, headline, source, sentiment, timestamp | 1× per stock at pre-market |
| `EarningsEvent` | Finnhub earnings calendar | symbol, date, time (bmo/amc), eps_estimate | 1× at pre-market |
| `ProfileEvent` | Finnhub stock profile | symbol, shares_outstanding, float_shares, market_cap, sector | 1× per stock at pre-market (cached) |
| `GapperEvent` | Pre-market gap scan | symbol, gap_pct, pre_market_volume, direction, catalyst | Every 15 min during pre-market |
| `ShortInterestEvent` | Finnhub short interest | symbol, short_interest, short_pct_float, short_ratio | 1× at pre-market (cached) |

#### Internal events (component → event bus)

| Event | Producer | Payload | Trigger |
|-------|----------|---------|---------|
| `WatchlistUpdate` | Dynamic watchlist engine | added_symbols, removed_symbols, full_list | On GapperEvent or scheduled discovery |
| `ScoreUpdate` | Scoring engine | symbol, composite_score, factor_scores, weights_used, catalyst_boost, float_score, squeeze_score | On every PriceTick for hot list stocks |
| `TechnicalUpdate` | Technical confirmation | symbol, rsi_14, macd_signal, macd_histogram, key_levels, confirmations | On every IntradayBar |
| `RegimeChange` | Regime detector | new_regime, old_regime, benchmark_data, sector_momentum | Every 15 min or on significant market shift |
| `PhaseChange` | Session phase system | new_phase, old_phase, market | On session phase transition |
| `Signal` | Strategy engine | full Signal entity (see domain model) | When a setup is detected and scored above threshold |
| `SignalUpdate` | State manager | signal_id, new_status, current_price | On every PriceTick for active signals |
| `Alert` | Risk manager | signal + alert metadata | When Signal passes all risk/state gates |
| `RiskBreached` | Risk manager | rule_name, current_value, limit | When a risk limit is hit |
| `FloatRotation` | Float analyzer | symbol, cumulative_volume, float_shares, rotation_count | When daily volume exceeds float |

### 4.2 Event bus design

In-process pub/sub using Python's observer pattern. No external message broker needed for v1.0.

```
EventBus:
  subscribe(event_type, handler)  — Register a handler for an event type
  publish(event)                  — Dispatch event to all subscribed handlers
  
  Guarantees:
    - Handlers execute synchronously in subscription order
    - A failing handler does not block other handlers
    - All events are logged to event_log for replay/debugging
```

Upgrade path to v2.0: swap in-process bus for Redis Pub/Sub or ZeroMQ for multi-process / multi-machine deployment.

### 4.3 Event flow for a typical alert

```
1. PRE-MARKET PHASE:
   a. Dynamic Watchlist Engine runs gap scan
   b. Discovers NVDA gapping up +4.2% on earnings with 1.8M pre-market volume
   c. Emits GapperEvent(NVDA, gap=+4.2%, vol=1.8M, catalyst="earnings")
   d. Watchlist Engine merges NVDA into today's active watchlist
   e. Emits WatchlistUpdate(added=[NVDA], source="GAPPER")
   
2. Scheduler triggers Finnhub poll for NVDA
3. DataIngestion receives quote → emits PriceTick(NVDA, $142.35, vol=45.2M)

4. ScoringEngine receives PriceTick:
   - Reads rolling RVOL from RollingState (3.2×)
   - Reads current VWAP from RollingState ($141.60)
   - Gets dynamic weights for current SessionPhase (MORNING)
   - Gets market_cap_tier: LARGE → uses large-cap weight profile
   - Computes base metrics: rvol=82, volatility=78, momentum=63, volume=70
   - Computes gap score: +4.2% gap → 60
   - Computes float score: 2.4B float → 20 (large float, lower score)
   - Computes short squeeze score: 3.2% short → 10 (low squeeze potential)
   - Composite score: 72
   - Applies catalyst boost: +15 (earnings today) → 87
   - Applies score decay: move happened 1h ago, still strong → no decay
   - Emits ScoreUpdate(NVDA, score=87)

5. TechnicalConfirmation receives IntradayBar:
   - RSI(14) = 62 → bullish but not overbought ✅
   - MACD histogram positive and rising ✅
   - Price above 9-EMA and 20-EMA ✅
   - Near no major resistance (52-wk high is $148) ✅
   - Emits TechnicalUpdate(NVDA, confirmations=4/4)

6. StrategyEngine receives ScoreUpdate + TechnicalUpdate:
   - Score > threshold (75 for TRENDING regime) → evaluate strategies
   - Reads MarketSession: phase=MORNING, regime=TRENDING, ORB high/low set
   - Evaluates ORB: price > ORB high, above VWAP, volume confirmed,
     RSI not overbought, MACD confirming ✅
   - Evaluates Gap & Go: gap +4.2%, volume confirming, VWAP holding ✅
   - Evaluates VWAP Pullback: not near VWAP ❌
   - Evaluates VWAP Breakout: already above VWAP ✅ but ORB has better R:R
   - Selects ORB: entry=$142.50, stop=$140.80, T1=$144.20, T2=$145.90
   - Uses LARGE cap parameter set (tighter stops, higher volume requirements)
   - Applies slippage adjustment (Grade A: none)
   - Attaches key levels: 52-wk high $148, 200-day MA $128, prev day high $139.50
   - Emits Signal(NVDA, ORB, LONG, entry=142.50, ...)

7. StateManager receives Signal:
   - Checks cooldown: last alert on NVDA was 45 min ago ✅
   - Checks dedup: ORB not yet triggered for NVDA today ✅
   - Registers signal as PENDING
   - Emits Signal with status=PENDING to RiskManager

8. RiskManager receives Signal:
   - Checks daily loss: -$80 < -$500 limit ✅
   - Checks concurrent signals: 2 active < 5 limit ✅
   - Checks R:R ratio: 1.0 ≥ 1.0 minimum ✅
   - Checks daily alert count: 8 < 20 limit ✅
   - Checks sector correlation: tech is +0.8% (confirming) ✅
   - Calculates position size: $100 risk / $1.70 per share = 58 shares
   - Emits Alert(NVDA, full trade plan)

9. Notifier receives Alert:
   - Formats message with score, catalyst, trade plan, levels,
     technical confirmations, key S/R levels, float/short data
   - Sends via Gmail SMTP / Telegram / Desktop (all enabled channels)

10. Logger receives Alert:
    - Appends to scan CSV
    - Appends to trades.json
    - Updates state.json
```

-----

## 5. Stateful runtime

### 5.1 In-memory state

The system maintains live state that updates on every tick. This is NOT stored in a database — it lives in memory for speed and is periodically persisted to disk for crash recovery.

#### Rolling calculations (per ticker, updated on each PriceTick)

| Metric | Window | Update method | Initial load |
|--------|--------|---------------|-------------|
| **VWAP** | From market open | Cumulative: `cum_tp_vol += typical_price × volume; vwap = cum_tp_vol / cum_vol` | Reset at market open |
| **RVOL** | 20-day lookback | `today_volume / avg_volume_20d` — avg_volume loaded from DailyBar at pre-market | DailyBar event |
| **ATR (14)** | 14-day daily bars | Loaded once from DailyBar, does not update intraday | DailyBar event |
| **Intraday range %** | Today only | `(day_high - day_low) / price × 100` — updates on each tick if new high/low | Resets at open |
| **Momentum** | Since prev close | `(price - prev_close) / prev_close × 100` | PriceTick |
| **EMA (9, 20)** | Intraday 5-min bars | Standard EMA formula, updated on each IntradayBar | First 20 bars |
| **RSI (14)** | 14 periods (5-min bars) | Wilder's smoothed RSI, updated on each IntradayBar | First 14 bars |
| **MACD (12,26,9)** | 5-min bars | Standard MACD line, signal line, histogram | First 26 bars |
| **Gap %** | Since prev close | `(current_price - prev_close) / prev_close × 100` at open; tracked for fade/hold | PriceTick at open |
| **Cumulative volume** | From market open | Running sum for float rotation detection | PriceTick |
| **Score age** | Since score first exceeded threshold | Tracks how long a stock has been "in play" for score decay | ScoreUpdate |

#### Session state (per market, updated on phase transitions and regime checks)

| State | Updated when | Persisted |
|-------|-------------|-----------|
| Current SessionPhase | Every minute (time check) | No (derived from clock) |
| Current MarketRegime | Every 15 min (benchmark check) | Yes (state.json) |
| ORB high / low | End of OPENING_RANGE phase | Yes |
| Pre-market high / low | During PRE_MARKET phase on each tick | Yes |
| Previous day levels | Loaded from DailyBar at startup | Yes |
| Sector momentum | Every 15 min (sector ETF check) | Yes |
| Today's dynamic watchlist | Pre-market discovery + intraday updates | Yes |
| Key S/R levels per ticker | Calculated from daily bars at pre-market | Yes |

#### Signal tracking state

| State | Updated when | Persisted |
|-------|-------------|-----------|
| Active signals | On Signal emit + every PriceTick (status check) | Yes (state.json) |
| Alert cooldowns | On Alert emit | Yes (state.json) |
| Triggered strategies per ticker | On Signal emit | Yes (resets daily) |
| Daily P&L estimate | On signal close (HIT_T1/T2/STOPPED) | Yes |
| Daily alert count | On Alert emit | Yes (resets daily) |

### 5.2 Storage architecture (local machine)

The system runs locally and splits storage into two layers: fast disposable state for intraday operations, and persistent storage for learning and improvement.

#### Hot layer (in-memory, disposable daily)

Lives in Python dicts/objects. Fast access, no disk I/O during tick processing.

| Data | Lifetime | Size estimate (50 tickers) | Reset trigger |
|------|----------|---------------------------|---------------|
| Latest price per ticker | Intraday | ~3 KB | Market open |
| VWAP cumulative state | Intraday | ~4 KB | Market open |
| RVOL current values | Intraday | ~2 KB | Market open |
| EMA (9, 20) state | Intraday | ~4 KB | Market open |
| RSI (14) state | Intraday | ~4 KB | Market open |
| MACD (12,26,9) state | Intraday | ~6 KB | Market open |
| Intraday high/low per ticker | Intraday | ~2 KB | Market open |
| ORB high/low | Intraday | ~1 KB | End of OPENING_RANGE |
| Pre-market high/low | Intraday | ~1 KB | Market open |
| Intraday 5-min candle buffer | Intraday | ~700 KB (50 tickers × 78 bars × ~180 bytes) | Market open |
| Gap % tracking | Intraday | ~2 KB | Market open |
| Cumulative volume (float rotation) | Intraday | ~2 KB | Market open |
| Score age tracking | Intraday | ~2 KB | Market open |
| Sector momentum cache | Intraday | ~1 KB | Every 15 min |
| Event dispatch queue | Seconds | ~10 KB | Continuous drain |
| **Total hot layer** | — | **~745 KB peak** | — |

This is small. Even tracking 50 tickers with all indicators, hot memory stays well under 5 MB.

#### Cold layer (persisted to disk, retained)

Written to disk on mutation. Small, valuable, never auto-deleted.

| File | Content | Write frequency | Retention |
|------|---------|-----------------|-----------|
| `state.json` | Active signals, cooldowns, daily counters, regime, ORB levels, watchlist | On every state mutation | Overwritten each write (latest only) |
| `trades.json` | Completed signals with entry/exit/result | On signal close | **Keep forever** (append-only, ~600 bytes per signal) |
| `scan_YYYY-MM-DD.csv` | Tick-level scan log | On every scoring cycle | Keep 30 days, archive/delete older |
| `event_log.jsonl` | Raw event stream for debugging/replay | On every event | Keep 7 days, rotate daily |
| `daily_summary_YYYY-MM-DD.json` | End-of-day stats (win rate, P&L, regime, top signals, discovery stats) | Once at market close | **Keep forever** (~3 KB per day) |
| `liquidity_cache.json` | Ticker profiles (float, grade, market cap, short interest) | Once at pre-market | Overwrite daily |
| `catalyst_cache.json` | News + earnings data per ticker | Once at pre-market | Overwrite daily |
| `watchlist_YYYY-MM-DD.json` | Today's dynamic watchlist with sources and reasons | Once at market open | Keep 30 days |

#### Why NOT SQLite for v1.0

SQLite is a solid choice, but adds a dependency and query layer that isn't needed yet. The data volumes are tiny: trades.json will have maybe 20–50 signals per day (~30 KB/day). At that scale, JSON files are simpler, portable (easy to inspect, copy, share), and the append-only pattern avoids corruption risk. Upgrade to SQLite in v2.0 when you want cross-day queries, aggregation, or a dashboard.

### 5.3 Daily cleanup strategy

The system runs a cleanup task at AFTER_HOURS phase transition (market close + 15 min):

| Action | What | When |
|--------|------|------|
| **Purge** hot memory | All intraday rolling state (VWAP, prices, candle buffers, ORB levels, RSI, MACD) | AFTER_HOURS transition |
| **Expire** active signals | Any signal still PENDING/ACTIVE/HIT_T1 → set status EXPIRED | AFTER_HOURS transition |
| **Write** daily summary | Aggregate today's signals + discovery stats into `daily_summary_YYYY-MM-DD.json` | AFTER_HOURS transition |
| **Write** watchlist log | Save today's dynamic watchlist to `watchlist_YYYY-MM-DD.json` | AFTER_HOURS transition |
| **Rotate** event log | Rename `event_log.jsonl` → `event_log_YYYY-MM-DD.jsonl`, start fresh | Midnight local time |
| **Delete** old event logs | Remove `event_log_*.jsonl` older than 7 days | Midnight local time |
| **Delete** old scan CSVs | Remove `scan_*.csv` older than 30 days | Midnight local time |
| **Delete** old watchlist logs | Remove `watchlist_*.json` older than 30 days | Midnight local time |
| **Keep** trades.json | Never delete — this is your learning history | — |
| **Keep** daily summaries | Never delete — tiny files, high value | — |
| **Reset** daily counters | daily_pnl, alerts_sent_today, triggered_today | Next market open |
| **Reset** dynamic watchlist | Clear, rebuild from scratch at next pre-market | Next pre-market |
| **Overwrite** cache files | liquidity_cache.json, catalyst_cache.json refreshed at pre-market | Next pre-market |

Disk growth estimate: ~70 KB/day (scan CSV) + ~30 KB/day (event log) + ~3 KB/day (summary) + ~2 KB/day (watchlist) ≈ **~105 KB/day**. At 250 trading days/year, that's ~26 MB/year. Negligible.

### 5.4 Memory growth management

Even within a single trading day, intraday data accumulates. Safeguards:

| Risk | Mitigation |
|------|-----------|
| Candle buffer grows all day | Cap at 78 bars per ticker (6.5 hours × 12 bars/hour for 5-min). Older bars drop off as a rolling window. |
| Event log grows in memory before flush | Events are written to disk immediately (append), not buffered in memory. |
| PriceTick history | Not stored — only the latest tick per ticker is kept. Previous ticks are discarded after processing. |
| Dynamic watchlist grows | Hard cap: 50 active tickers. When discovery finds new candidates, lowest-scoring existing tickers are evicted. |
| Hot list changes | When a ticker drops off the hot list, its intraday state is pruned from memory within 1 cycle. |
| RSI/MACD history | Only stores the smoothed values needed for next calculation, not full bar history. |
| Memory ceiling | Log a warning if process memory exceeds 150 MB (configurable). At 50 tickers this should never happen. |

### 5.5 Crash recovery flow

On startup, the system checks for existing state and recovers:

```
Startup sequence:
  1. Load config.json
  2. Check results/state.json exists?
     ├─ YES: Load state
     │   ├─ Check state.scan_date == today?
     │   │   ├─ YES: Resume mid-session
     │   │   │   - Restore active signals, cooldowns, daily counters
     │   │   │   - Restore ORB levels, regime, dynamic watchlist
     │   │   │   - Log: "Resumed session with N active signals"
     │   │   │   - WARNING: Rolling calcs (VWAP, EMA, RSI, MACD) are LOST
     │   │   │   - Fetch fresh quotes to rebuild hot state
     │   │   │   - VWAP will be approximate until enough bars accumulate
     │   │   │   - RSI/MACD need 14+ / 26+ bars to stabilize
     │   │   │
     │   │   └─ NO: Stale state from previous day
     │   │       - Expire all active signals
     │   │       - Reset daily counters
     │   │       - Clear dynamic watchlist
     │   │       - Start fresh session
     │   │
     │   └─ Parse error: Log warning, start fresh
     │
     └─ NO: First run ever, start fresh
  
  3. Load liquidity_cache.json + catalyst_cache.json if today's date
  4. Enter PRE_MARKET or appropriate phase based on current time
  5. If mid-session recovery: reduce confidence on VWAP/RSI/MACD-dependent
     strategies for 30 minutes (flag: recovery_mode=True)
  6. Begin scheduler main loop
```

Key limitation: **VWAP cannot be perfectly recovered after a mid-session crash** because it requires cumulative volume × price from market open. RSI and MACD also need warm-up periods. After recovery, these indicators will be approximate. The spec accepts this tradeoff — a warning is logged and indicator-dependent strategies have their confidence reduced for 30 minutes after recovery.

### 5.6 Upgrade path to Redis / SQLite

| Trigger | Upgrade to | What changes |
|---------|-----------|-------------|
| Want multi-process (scanner + dashboard) | Redis | Hot layer moves to Redis hashes, event bus to Redis Pub/Sub |
| Want historical queries / aggregation | SQLite | Cold layer (trades, summaries) moves to SQLite tables |
| Want remote access (phone, VPS) | Redis + SQLite + web server | Add a thin API layer over the storage |
| Want sub-second latency | Redis | Rolling calcs in Redis Streams with consumer groups |

For v1.0: JSON files + in-memory dicts. No external dependencies beyond `requests` and `plyer`.

-----

## 6. Scheduling and orchestration

### 6.1 Scheduler design

The scheduler is a single-threaded event loop with timer-based task dispatching. It is NOT cron-based — it runs continuously during market hours.

#### Task types

| Task | Trigger | Interval | Data source | API calls |
|------|---------|----------|-------------|-----------|
| **Pre-market discovery** | Time-based: 60 min before open, repeats every 15 min | 4× during pre-market | Finnhub (top gainers, quotes), Alpha Vantage (quotes) | FH: ~5 per scan |
| **Pre-market data load** | Time-based: 30 min before open | Once | Alpha Vantage (daily bars), Finnhub (profile, news, earnings, short interest) | AV: ~15, FH: ~50 |
| **Full watchlist scan** | Time-based: 3× per day | At configured times | Alpha Vantage (quotes) | AV: ~10 per scan |
| **Hot list monitor (normal)** | Timer: every 120s | Continuous during market hours | Finnhub (quote) | FH: ~4/min |
| **Hot list monitor (fast)** | Timer: every 15s | Continuous for top 5 | Finnhub (quote) | FH: ~20/min |
| **Regime refresh** | Timer: every 15 min | During market hours | Finnhub (benchmark quote + candles) | FH: ~2 per refresh |
| **Sector momentum update** | Timer: every 15 min | During market hours | Finnhub (sector ETF quotes) | FH: ~3 per refresh |
| **Intraday bar update** | Timer: every 5 min | During market hours for hot list | Finnhub (candles) | FH: ~10 per refresh |
| **Intraday top movers scan** | Timer: every 30 min | During market hours | Finnhub (market status / top movers) | FH: ~2 per scan |
| **Signal status check** | On every PriceTick | Piggybacked on tick processing | No extra API calls | 0 |
| **Phase transition check** | Timer: every 60s | Continuous | No API calls (clock-based) | 0 |
| **State persistence** | On state mutation | Event-driven | No API calls | 0 |
| **Session close tasks** | Time-based: at market close | Once | No API calls | 0 |

#### Main loop

```
main_loop:
  while True:
    now = current_time()
    
    for each registered task:
      if task.is_due(now):
        task.execute()
        task.update_next_run()
    
    sleep(1s)  — main loop granularity
```

The 1-second sleep means:

- Phase transitions detected within 1 second of actual transition
- Fast-mode polls dispatch within 1 second of their 15-second interval
- Signal status checks happen on the next tick after price moves

### 6.2 API budget management

#### Alpha Vantage key rotation

Multiple API keys are supported to multiply the daily call budget. Keys rotate round-robin, with per-key call tracking.

```
Key pool: [key_1, key_2, key_3]
Daily budget per key: 25 calls
Total daily budget: 75 calls

Key rotation strategy:
  - Round-robin across keys
  - Track calls_used per key per day
  - Skip exhausted keys
  - Reset counts at midnight ET
  - If all keys exhausted, fall back to Finnhub-only mode
```

Config:

```
api_keys:
  alpha_vantage:
    - "KEY_1"
    - "KEY_2"
    - "KEY_3"
  finnhub: "FINNHUB_KEY"
```

#### API budget allocation per day (with 3 AV keys = 75 calls)

| Task | AV calls | Finnhub calls/min (peak) |
|------|----------|--------------------------|
| Pre-market daily bars (up to 50 stocks) | 40 | — |
| Full scan × 3 (top 12 stocks each) | 30 | — |
| Spare / retry buffer | 5 | — |
| Pre-market discovery (gappers) | — | 5 (burst, 4× pre-market) |
| Hot list monitor (10 stocks, normal) | — | 5 |
| Hot list fast mode (5 stocks) | — | 20 |
| Regime + sector refresh (every 15 min) | — | 0.33 |
| Intraday bars (every 5 min, 10 stocks) | — | 2 |
| Intraday top movers (every 30 min) | — | 0.07 |
| Pre-market profiles + news + earnings + short interest | — | 4 (burst, once) |
| **Total** | **75 / 75** | **~32 / 60 limit** |

### 6.3 Retry and failover

| Failure | Response | Fallback |
|---------|----------|----------|
| Single API call fails | Retry 3× with exponential backoff (2s, 4s, 8s) | Skip ticker for this cycle |
| API rate limit hit (429) | Back off for 60s, rotate to next key | Switch to Finnhub for that data |
| Alpha Vantage fully exhausted | Log warning, continue Finnhub-only mode | No daily bar refresh, use cached |
| Finnhub down | Pause hot list monitor, rely on full scan schedule | Alert user via desktop notification |
| Network failure | Retry 3×, then pause 5 min | Preserve state, resume when connected |
| All APIs down | Enter "degraded mode" — no scanning, state preserved | Desktop notification: "APIs unreachable" |

-----

## 7. Dynamic watchlist engine

### 7.1 Philosophy

Professional day traders do not trade a static list. They rebuild their watchlist every morning based on what's moving. This engine automates that process.

The watchlist has two layers:

1. **Base watchlist** — A curated list of liquid, well-known stocks that frequently offer day trading setups (e.g., NVDA, TSLA, AAPL). These are always monitored. Configurable in config.json.
2. **Discovery layer** — Stocks found dynamically each session through gap scanning, top movers detection, and short squeeze screening. These are merged into the active watchlist if they pass liquidity filters.

### 7.2 Pre-market gap scanner

Runs 4 times during pre-market (starting 60 min before open, every 15 min).

**Gap scanner criteria (large cap, market cap ≥ $800M)**:

- Gap ≥ 3% from previous close
- Pre-market volume ≥ 20,000 shares
- Average daily volume ≥ 500,000 shares
- Has a catalyst (news, earnings) — preferred but not required

**Gap scanner criteria (small cap, market cap < $800M)**:

- Gap ≥ 10% from previous close
- Pre-market volume ≥ 200,000 shares
- Float < 20M shares — preferred (higher priority)
- Has a catalyst — strongly preferred

**Implementation**: Query Finnhub market status / top movers endpoint during pre-market. Cross-reference with Alpha Vantage quotes for gap calculation. Filter results through the liquidity engine before adding to watchlist.

### 7.3 Intraday top movers scanner

Runs every 30 minutes during market hours. Catches stocks that start moving *after* the open — not just pre-market gappers.

**Criteria**:

- Change % ≥ 5% from previous close (intraday, not gap)
- Volume ≥ 2× average daily volume so far (time-adjusted)
- Not already on the watchlist
- Passes liquidity filter

### 7.4 Short squeeze candidate scanner

Runs once at pre-market as part of discovery.

**Criteria**:

- Short % of float ≥ 15%
- Short ratio (days to cover) ≥ 3
- Showing positive pre-market momentum (gap up or volume spike)
- Float < 50M shares preferred

These get flagged as `source: "SQUEEZE_CANDIDATE"` and receive a short squeeze score boost in the scoring engine.

### 7.5 Watchlist merge logic

```
active_watchlist = base_watchlist (always included)

for each discovery_candidate:
  if candidate passes liquidity filter:
    if len(active_watchlist) < MAX_WATCHLIST_SIZE (50):
      add candidate
    else:
      find lowest-scoring stock in active_watchlist (excluding base)
      if candidate.discovery_score > lowest.score:
        evict lowest, add candidate
      else:
        skip candidate (log as "suppressed")

Emit WatchlistUpdate event with changes
```

### 7.6 Discovery score (for ranking candidates)

Used only for watchlist admission — separate from the main composite score.

```
discovery_score = 
  (gap_pct_normalized × 0.30) +
  (pre_market_volume_normalized × 0.25) +
  (catalyst_present × 0.20) +
  (float_score × 0.15) +        # Lower float = higher score
  (short_squeeze_score × 0.10)   # Higher short interest = higher score
```

-----

## 8. Scoring engine

### 8.1 Input metrics

| Metric | Source | Rolling state | Update frequency |
|--------|--------|--------------|-----------------|
| RVOL | Rolling: today_volume / cached avg_volume_20d | avg_volume_20d from DailyBar | Every PriceTick |
| Volatility (ATR %) | Cached: ATR_14 / price × 100 | ATR_14 from DailyBar | Every PriceTick (price changes) |
| Intraday range % | Rolling: (day_high - day_low) / price × 100 | Tracked per ticker | Every PriceTick |
| Momentum | Live: abs(change_pct) from prev close | prev_close from DailyBar | Every PriceTick |
| Volume | Live: raw daily volume | From PriceTick | Every PriceTick |
| Gap % | Calculated: (open - prev_close) / prev_close × 100 | From open + DailyBar | Once at open, tracked for decay |
| Float score | Static for day: based on float_shares | From ProfileEvent | Once at pre-market |
| Short squeeze score | Static for day: based on short_interest + short_ratio | From ShortInterestEvent | Once at pre-market |

### 8.2 Normalization (each metric → 0–100)

| Metric | Formula | 0 point | 100 point |
|--------|---------|---------|-----------|
| RVOL score | `clamp((rvol - 1.0) × 50, 0, 100)` | RVOL = 1.0 (normal) | RVOL ≥ 3.0 |
| Volatility score | `clamp(volatility_pct × 20, 0, 100)` | 0% range | 5%+ range |
| Momentum score | `clamp(abs(change_pct) × 15, 0, 100)` | 0% change | 6.7%+ change |
| Volume score | `clamp((volume / min_volume - 1) × 10, 0, 100)` | At minimum | 10× minimum |
| Gap score | `clamp(abs(gap_pct) × 10, 0, 100)` | 0% gap | 10%+ gap |
| Float score | See Section 13.2 | Large float (>500M) | Micro float (<5M) |
| Squeeze score | See Section 13.4 | 0% short interest | 30%+ short, high ratio |

### 8.3 Dynamic weights by session phase

| Phase | RVOL | Volatility | Momentum | Volume | Gap | Rationale |
|-------|------|-----------|----------|--------|-----|-----------|
| PRE_MARKET | 0.35 | 0.10 | 0.10 | 0.15 | 0.30 | Gap and unusual volume are the only reliable pre-market signals |
| OPENING_RANGE | 0.15 | 0.20 | 0.30 | 0.15 | 0.20 | Everything is moving; momentum separates real moves from noise |
| MORNING | 0.25 | 0.20 | 0.25 | 0.15 | 0.15 | Balanced — best trading window, gap still relevant |
| MIDDAY | 0.40 | 0.15 | 0.20 | 0.15 | 0.10 | Volume dries up; unusual volume is the standout signal |
| POWER_HOUR | 0.20 | 0.25 | 0.30 | 0.15 | 0.10 | Afternoon moves are sharp; volatility + momentum dominate |
| POST_BREAK | 0.20 | 0.20 | 0.30 | 0.15 | 0.15 | Similar to OPENING_RANGE — second ORB window |

**Note**: Float score and short squeeze score are additive bonuses (see Section 13), not part of the weighted composite. This keeps the primary scoring focused on price action while still rewarding favorable supply/demand dynamics.

Weights are configurable per phase. A static fallback (0.25 / 0.20 / 0.25 / 0.15 / 0.15) is used when dynamic weights are disabled.

### 8.4 Composite score

```
base_score = (rvol_score × w_rvol) + (volatility_score × w_vol) + 
             (momentum_score × w_mom) + (volume_score × w_volume) + 
             (gap_score × w_gap)

bonuses = catalyst_boost + float_bonus + squeeze_bonus

decay = score_decay(time_since_first_move)   # See 8.6

final_score = min(base_score + bonuses - decay, 100)
```

### 8.5 Alert rules

| Rule | Value | Notes |
|------|-------|-------|
| Base alert threshold | 75 | Adjusted by regime (70 trending, 80 choppy, 85 low_vol) |
| Cooldown | 30 min per ticker | Prevents repeat alerts |
| RVOL floor | > 1.5 | Must show above-average activity |
| Volume floor | > market's min_volume (tier-adjusted) | Ensures liquidity |
| Score spike alert | +20 points between ticks | Fires even if below threshold (momentum shift) |
| Technical confirmation minimum | ≥ 2 of 4 checks passing | RSI, MACD, EMA alignment, S/R clearance |

### 8.6 Score decay (staleness of move)

A stock that gapped 8% at open but has been flat since 10:30 AM should have its score decay. This prevents stale movers from clogging the hot list.

```
score_decay(minutes_since_move):
  if minutes_since_move < 30: return 0
  if minutes_since_move < 60: return 5
  if minutes_since_move < 120: return 15
  if minutes_since_move < 180: return 25
  return 35  # Max decay

"Move" is defined as: last time the stock made a new intraday high/low,
or last time RVOL increased by > 0.2×
```

This decay is applied to the final score, meaning a stock must continue showing fresh activity to stay hot.

-----

## 9. Strategy engine

### 9.1 Strategy selection

The engine evaluates all eligible strategies per tick and selects the one with the best risk/reward ratio. Eligibility is controlled by SessionPhase, MarketRegime, and MarketCapTier.

| Strategy | Eligible phases | Eligible regimes | Key inputs |
|----------|----------------|-----------------|------------|
| **ORB Breakout** | MORNING (first 45 min), POWER_HOUR, POST_BREAK | TRENDING, VOLATILE | ORB high/low, VWAP, volume on breakout bar, RSI, MACD |
| **VWAP Pullback** | MORNING, MIDDAY, POWER_HOUR | All regimes | VWAP, day high/low, change %, pullback volume pattern, RSI |
| **VWAP Breakout** | MORNING (after 9:45), MIDDAY, POWER_HOUR | TRENDING, VOLATILE | VWAP, volume, ATR, MACD confirmation |
| **Gap & Go** | MORNING (first 30 min after open) | TRENDING, VOLATILE | Gap %, pre-market high, volume, catalyst, float | 

### 9.2 Strategy 1: ORB Breakout

**Concept**: Trade the breakout of the first 15 minutes' high/low range with volume, VWAP, and technical confirmation.

**Entry conditions (LONG)**:

- 5-min candle closes above ORB high
- Breakout candle volume ≥ 1.5× average 15-min volume
- Price above VWAP
- ORB range between 0.3% and 3.0% of price (large cap) or 0.5% and 5.0% (small cap)
- RSI(14) between 40 and 75 (not overbought, has room to run)
- MACD histogram positive or crossing positive

**Level calculation**:

- Entry: ORB high + tick buffer
- Stop: ORB low − buffer (risk = entire range)
- Target 1: entry + 1× range (1:1 R:R)
- Target 2: entry + 2× range (2:1 R:R)
- Trailing stop after T1: below 9-EMA or VWAP
- Time stop: 30 min with no move → close (large cap) / 20 min (small cap)
- Session stop: flatten before market close
- Key level awareness: if T1 is within 0.5% of a major S/R level, extend T1 to that level or reduce it below

**Short**: mirror of long (break below ORB low, price below VWAP, RSI between 25 and 60).

### 9.3 Strategy 2: VWAP Pullback

**Concept**: Enter on a pullback to VWAP after a strong initial move, when volume declines on the pullback.

**Entry conditions (LONG)**:

- Stock up ≥ 3% on the day (large cap) or ≥ 5% (small cap) with RVOL ≥ 2.0
- Price within 0.5% of VWAP (pulling back from above)
- Pullback volume declining (each of last 2 bars has lower volume than the 3-bar avg before pullback)
- First reversal candle that holds VWAP and closes green
- RSI(14) pulling back from above 60 toward 45–55 range (healthy reset, not collapse)

**Level calculation**:

- Entry: high of reversal candle + tick buffer
- Stop: below pullback low − buffer, or below VWAP (whichever is tighter)
- Target 1: retest of day's high
- Target 2: day's high + 0.5× the initial move
- Exit signal: price closes below VWAP (thesis broken)

### 9.4 Strategy 3: VWAP Breakout

**Concept**: Trade the initial break through VWAP with volume confirmation.

**Entry conditions (LONG)**:

- Price breaks above VWAP with RVOL ≥ 1.5
- Not before 9:45 AM (VWAP needs ~15 min to stabilize)
- MACD signal line crossover confirming direction
- Volume on breakout bar ≥ 1.3× the 5-bar average

**Level calculation**:

- Entry: VWAP + 0.1% buffer
- Stop: VWAP − 0.3%
- Target 1: day's high or prior resistance
- Target 2: VWAP + 1× ATR
- Exit signal: price closes back below VWAP

### 9.5 Strategy 4: Gap & Go (NEW)

**Concept**: Trade continuation of a strong pre-market gap when the stock holds above the gap level with volume confirmation after the open.

**Entry conditions (LONG)**:

- Stock gapped up ≥ 4% (large cap) or ≥ 10% (small cap) with catalyst
- Pre-market volume confirms interest (≥ 20K large cap / ≥ 200K small cap)
- After market open, stock holds above VWAP for first 5 minutes
- First pullback holds above pre-market low OR forms a flag/consolidation pattern
- RSI(14) above 50 (momentum intact)
- RVOL ≥ 2.0

**Level calculation**:

- Entry: breakout above the first 5-min consolidation high
- Stop: below the consolidation low or pre-market low (whichever is tighter)
- Target 1: pre-market high
- Target 2: pre-market high + 1× the gap range
- Time stop: 30 min with no move (gaps lose steam quickly)
- Gap fill warning: if price drops below opening print, thesis is broken → exit

**Gap fill probability heuristic**:

```
if gap_pct < 4% AND no_strong_catalyst:
  gap_fill_likely = True    # Avoid Gap & Go, or play the fade
elif gap_pct >= 4% AND strong_catalyst AND rvol >= 2.0:
  gap_fill_likely = False   # Gap & Go is viable
else:
  gap_fill_likely = "NEUTRAL"  # Use volume confirmation after open
```

**Short (Gap & Fade)**: For gaps without catalyst or with gap_fill_likely=True, a fade setup can be played. Entry below the opening range low, stop above ORB high, targets the gap fill level (previous close).

### 9.6 Strategy timing matrix

| Time window (US ET) | Strategy | Action |
|---------------------|----------|--------|
| 09:00–09:30 | **Dynamic watchlist** | Pre-scan, discover gappers, build watchlist |
| 09:30–09:45 | None (ORB forming) | ORB range forming, VWAP stabilizing |
| 09:45–10:00 | **Gap & Go** (primary), ORB (secondary) | First pullback after gap, ORB established |
| 10:00–10:30 | **ORB** (primary), Gap & Go | Opening range breakouts peak |
| 10:30–11:30 | **VWAP Pullback** (primary), VWAP Breakout | Early movers pulling back |
| 11:30–14:00 | **VWAP Pullback**, VWAP Breakout | Midday — look for VWAP setups |
| 14:00–15:00 | **VWAP Breakout** (primary) | Afternoon momentum |
| 15:00–15:45 | **ORB** (mini, power hour range) | Last-hour range as mini-ORB |
| 15:45–16:00 | None | Flatten all positions |

### 9.7 Multi-market adaptations

| Parameter | US | HK | SG | JP | TW | UK |
|-----------|----|----|----|----|----|----|
| ORB window | 15 min | 15 min | 15 min | 15 min | 15 min | 15 min |
| ORB max range % (large) | 3.0% | 3.5% | 2.5% | 3.0% | 3.0% | 2.5% |
| ORB max range % (small) | 5.0% | 6.0% | 4.0% | 5.0% | 5.0% | 4.0% |
| VWAP buffer | 0.1% | 0.15% | 0.1% | 0.1% | 0.1% | 0.1% |
| Pullback threshold (large) | 3.0% | 3.5% | 2.0% | 3.0% | 3.0% | 2.0% |
| Pullback threshold (small) | 5.0% | 6.0% | 3.5% | 5.0% | 5.0% | 3.5% |
| Gap & Go min gap (large) | 4% | 4% | 3% | 4% | 4% | 3% |
| Gap & Go min gap (small) | 10% | 12% | 8% | 10% | 10% | 8% |
| Post-break ORB | N/A | After midday break | After midday break | After midday break | N/A | N/A |

Markets with midday breaks (HK, SG, JP) treat the first 15 min after break as a second ORB window.

TW has daily price limits of ±10%. This naturally caps ORB range and means limit-lock scenarios are possible — if a stock hits the daily limit, it may become untradeable. The strategy engine should detect when price is within 1% of the daily limit and skip new signals for that ticker.

### 9.8 Position sizing

Based on configurable max risk per trade, adjusted by market cap tier:

```
shares = floor(max_risk_usd / risk_per_share)

Tier adjustments:
  LARGE:  max_risk = config.max_risk_per_trade_usd × 1.0
  MID:    max_risk = config.max_risk_per_trade_usd × 0.8
  SMALL:  max_risk = config.max_risk_per_trade_usd × 0.5

Example (LARGE): $100 max risk / $1.70 risk per share = 58 shares
Example (SMALL): $50 max risk / $0.30 risk per share = 166 shares
```

### 9.9 Slippage adjustment

| Grade | Adjustment | Notes |
|-------|-----------|-------|
| A | None | Institutional-grade liquidity |
| B | 0.05% | Minimal slippage |
| C | Half the estimated spread | Noticeable impact |
| D+ | Excluded | Not tradeable by liquidity filter |

-----

## 10. Market regime detection

### 10.1 Detection method

Uses the broad market benchmark (SPY for US, etc.) to classify the day. Measured from intraday data every 15 minutes.

**Inputs**: benchmark day_high, day_low, open, price, 14-day ATR, intraday 5-min bars.

**Classification logic**:

1. Calculate `range_ratio = (day_high - day_low) / ATR`
2. Calculate `direction_strength = abs(price - open) / (day_high - day_low)`
3. Count direction changes in intraday bars → `choppiness` ratio

| Condition | Regime |
|-----------|--------|
| range_ratio < 0.5 | LOW_VOL |
| range_ratio > 1.5 AND direction_strength > 0.6 | TRENDING |
| range_ratio > 1.5 AND direction_strength < 0.4 | VOLATILE |
| choppiness > 0.6 | CHOPPY |
| direction_strength > 0.5 | TRENDING |
| Default | CHOPPY |

### 10.2 Regime adjustments

| Parameter | TRENDING | CHOPPY | VOLATILE | LOW_VOL |
|-----------|----------|--------|----------|---------|
| Alert threshold | 70 | 80 | 75 | 85 |
| ORB allowed | Yes | No | Yes (wider stops) | No |
| Gap & Go allowed | Yes | No | Yes (careful) | No |
| Stop multiplier | 1.0× | 0.7× | 1.3× | 1.0× |
| Fast mode | On | Off | On | Off |
| Max concurrent signals | 5 | 3 | 4 | 2 |

### 10.3 Sector momentum (NEW)

Every 15 minutes, the system polls a set of sector ETFs to build a sector momentum snapshot:

| Sector | ETF |
|--------|-----|
| Technology | XLK |
| Financial | XLF |
| Energy | XLE |
| Healthcare | XLV |
| Consumer Discretionary | XLY |
| Industrials | XLI |
| Communication | XLC |
| Materials | XLB |

**Usage**: When a signal fires, the system checks if the stock's sector is moving in the same direction as the signal. Confirming sector momentum adds confidence. Conflicting sector momentum reduces signal priority (but does not reject it).

```
sector_alignment:
  CONFIRMING:  sector change same direction, ≥ +0.3%  → +5 bonus to score
  NEUTRAL:     sector change < 0.3% either way         → no change
  CONFLICTING: sector change opposite direction, ≥ -0.3% → -5 penalty to score
```

### 10.4 Benchmark symbols

| Market | Benchmark |
|--------|-----------|
| US | SPY |
| HK | 2800.HK |
| SG | ES3.SI |
| JP | 1321.T |
| TW | 0050.TW |
| UK | ISF.L |
| EU | EXSA.DE |

-----

## 11. Catalyst and news integration

### 11.1 Data sources

| Endpoint | Data | When called |
|----------|------|------------|
| Finnhub `/company-news` | News articles (last 24h) | Pre-market, cached for the day |
| Finnhub `/calendar/earnings` | Earnings dates | Pre-market, cached for the day |
| Finnhub `/stock/profile2` | Company profile | Pre-market, cached for the day |
| Finnhub `/stock/short-interest` | Short interest data | Pre-market, cached for the day |

### 11.2 Catalyst types and score boosts

| Catalyst | Boost | Cap |
|----------|-------|-----|
| Earnings today | +15 | |
| Earnings tomorrow | +8 | |
| Positive news (< 4h old) | +10 | |
| Positive news (4–12h old) | +7 | |
| Positive news (12–24h old) | +4 | |
| Negative news (< 4h old) | +10 (for short setups) | |
| Negative news (4–12h old) | +7 (for short setups) | |
| FDA event | +15 | |
| Analyst upgrade/downgrade | +8 | |
| Short squeeze setup (short % > 20%) | +12 | |
| Neutral news (in play) | +3 | |
| **Total boost cap** | — | **+25 max** |

### 11.3 News recency weighting (NEW)

News from 2 hours ago is far more actionable than news from 18 hours ago. All news catalysts receive a time-decay multiplier:

```
news_recency_multiplier(hours_since_publication):
  if hours < 2:   return 1.0    # Full boost
  if hours < 4:   return 0.85
  if hours < 8:   return 0.60
  if hours < 12:  return 0.40
  if hours < 24:  return 0.25
  return 0.0                     # Stale news, no boost
```

Applied as: `actual_boost = base_boost × news_recency_multiplier`

### 11.4 Sentiment detection (v1.0)

**Primary**: Use Finnhub's built-in sentiment score when available (returned in company-news response). This is more reliable than keyword matching.

**Fallback** (when Finnhub sentiment is unavailable): Keyword-based classification of the lead headline:

- Positive keywords: beat, surge, rally, upgrade, approval, record, exceed, strong, raise, buy, breakout, soar, jump
- Negative keywords: miss, plunge, downgrade, cut, warning, weak, decline, sell, loss, reject, layoff, recall, investigate
- FDA-specific: approval, cleared, fast-track, breakthrough (positive); reject, refuse, warning-letter, recall (negative)

Count hits per category; majority wins. Ties → neutral.

**Important**: A headline like "Beat expectations but lowered guidance" has both signals. When both positive and negative keywords are present in roughly equal count, classify as MIXED and apply only the neutral boost (+3).

Upgrade path to v2.0: replace with an LLM-based classifier for nuanced understanding.

-----

## 12. Liquidity filter

### 12.1 Filter criteria

| Check | Threshold (Large cap) | Threshold (Small cap) | Action |
|-------|----------------------|----------------------|--------|
| Penny stock | Price < $1.00 | Price < $1.00 | Exclude |
| Wide spread | Estimated spread > 0.3% | Estimated spread > 0.8% | Exclude |
| Thin dollar volume | Avg daily dollar vol < $10M | Avg daily dollar vol < $2M | Exclude |
| Liquidity grade F | Grade below D | Grade below D | Exclude |
| Pre-market volume floor | N/A | Pre-market vol < 100K (for gappers) | Exclude from Gap & Go |

**Note**: Low float is NOT an exclusion criterion. Low float stocks are treated differently by the market cap tier system (Section 15) and scored by the float analysis system (Section 13). They are only excluded if they fail spread or dollar volume checks.

### 12.2 Liquidity grading

| Grade | Avg daily dollar volume | Meaning |
|-------|------------------------|---------|
| A | > $100M | Institutional-grade liquidity |
| B | > $20M | Strong retail + institutional |
| C | > $5M | Adequate for day trading |
| D | > $1M | Risky, wide spreads likely — small cap only |
| F | < $1M | Avoid |

### 12.3 Spread estimation

Heuristic from recent daily bars: `estimated_spread = avg(daily_range_pct for last 5 days) / 10`

This overestimates for large caps and approximates for mid/small caps. Conservative is correct here.

**Additional heuristic for small caps**: If average daily volume < 500K shares AND price < $10, multiply the spread estimate by 1.5× (small caps with low volume tend to have wider spreads than the heuristic captures).

-----

## 13. Float and short interest analysis (NEW)

### 13.1 Float data sourcing

Float data comes from Finnhub's `/stock/profile2` endpoint, which includes `shareOutstanding`. True public float (excluding insider/institutional locked shares) is approximated as:

```
estimated_float = shares_outstanding × 0.85   # Conservative default
```

When Finnhub provides `float` data directly (available for some US stocks), use the precise figure.

### 13.2 Float scoring

Float is scored on an inverse scale — lower float = higher score = more potential for explosive moves:

| Float range | Score | Label |
|-------------|-------|-------|
| < 5M shares | 30 | Micro float — extreme volatility potential |
| 5M–20M | 25 | Low float — high volatility potential |
| 20M–50M | 18 | Moderate float |
| 50M–100M | 12 | Normal float |
| 100M–500M | 5 | Large float |
| > 500M | 0 | Mega float — float is not a factor |

The float score is added as a bonus to the composite score (not weighted into the base score), capped at +30.

### 13.3 Float rotation detection

When cumulative daily volume exceeds the stock's float, the float has "rotated" — meaning every available share has changed hands at least once. This is a strong signal of extreme market interest.

```
float_rotation_ratio = cumulative_daily_volume / float_shares

Events:
  rotation >= 1.0:  Emit FloatRotation event, +10 score bonus, log
  rotation >= 2.0:  +15 score bonus (extremely active)
  rotation >= 3.0:  +20 score bonus + special alert flag
```

Float rotation is only meaningful for stocks with float < 100M. For mega-cap stocks with billions of shares in float, rotation is physically impossible in a single day and should not be tracked.

### 13.4 Short squeeze scoring

Short interest data is loaded at pre-market from Finnhub. Each stock receives a squeeze score:

| Metric | Condition | Score contribution |
|--------|-----------|-------------------|
| Short % of float | ≥ 10% | +10 |
| Short % of float | ≥ 20% | +20 (replaces +10) |
| Short % of float | ≥ 30% | +30 (replaces +20) |
| Short ratio (days to cover) | ≥ 3 days | +5 |
| Short ratio | ≥ 5 days | +10 (replaces +5) |
| Stock is gapping up with high short interest | Gap up + short > 15% | +8 (squeeze catalyst) |

Max squeeze bonus: +30 (capped).

The squeeze score is added to the composite as a bonus. When squeeze_score ≥ 20, the stock is flagged as `SQUEEZE_CANDIDATE` in alerts so the trader knows this is a potential squeeze play (different risk profile).

### 13.5 Dilution risk flags

For small-cap stocks (market cap < $800M), dilution risk is a significant concern. Stocks with active shelf registrations or ATM offerings can issue new shares at any time, killing momentum.

**v1.0 implementation**: Simple flag based on available data.

```
has_dilution_risk = True if any of:
  - Recent SEC filing mentioning "shelf registration" or "at-the-market" (from news)
  - Stock has issued new shares within the last 30 days (from news/filings)
  - Recent news headline contains: "offering", "dilution", "shelf", "ATM", "registered direct"
```

When `has_dilution_risk = True`:

- Reduce float score bonus by 50%
- Add a ⚠️ warning flag to the alert message
- Do NOT exclude the stock (traders may still want to trade it with awareness)

**v2.0 upgrade**: Integrate with SEC EDGAR API for real filing data, or a service like Dilution Tracker.

-----

## 14. Technical confirmation layer (NEW)

### 14.1 Purpose

The technical confirmation layer adds a second opinion to every signal. A stock can have a perfect composite score, but if RSI is at 90 (extremely overbought), the entry is dangerous. This layer provides sanity checks that reduce false signals.

### 14.2 Indicators calculated

| Indicator | Period | Update frequency | Purpose |
|-----------|--------|-----------------|---------|
| RSI (14) | 14 × 5-min bars | Every IntradayBar | Overbought/oversold detection |
| MACD (12,26,9) | 5-min bars | Every IntradayBar | Trend direction and momentum confirmation |
| EMA (9) | 5-min bars | Every IntradayBar | Immediate trend (trailing stop reference) |
| EMA (20) | 5-min bars | Every IntradayBar | Short-term trend |
| 50-day MA | Daily bars | Once at pre-market | Key support/resistance level |
| 200-day MA | Daily bars | Once at pre-market | Major support/resistance level |
| 52-week high/low | Daily bars | Once at pre-market | Proximity flags |
| Previous day high/low/close | Daily bars | Once at pre-market | Key levels for breakout/support |

### 14.3 Confirmation checks

Each signal is evaluated against 4 confirmation checks. At least 2 must pass for the signal to be emitted. All 4 passing adds a +5 confidence bonus to the score.

| Check | LONG condition | SHORT condition |
|-------|---------------|-----------------|
| **RSI range** | RSI between 35 and 75 (not overbought, not collapsing) | RSI between 25 and 65 (not oversold, not rallying) |
| **MACD direction** | MACD histogram positive OR crossing positive | MACD histogram negative OR crossing negative |
| **EMA alignment** | Price above 9-EMA AND 9-EMA above 20-EMA | Price below 9-EMA AND 9-EMA below 20-EMA |
| **S/R clearance** | No major resistance within 0.5% above entry (50-day MA, 200-day MA, 52-wk high, prev day high) | No major support within 0.5% below entry |

### 14.4 Key levels reporting

Every signal includes a `key_levels` dict showing nearby support and resistance:

```
key_levels:
  above:
    - {"level": 148.00, "type": "52_week_high", "distance_pct": 3.9}
    - {"level": 145.50, "type": "prev_day_high", "distance_pct": 2.1}
  below:
    - {"level": 141.60, "type": "VWAP", "distance_pct": -0.5}
    - {"level": 139.50, "type": "prev_day_close", "distance_pct": -2.0}
    - {"level": 128.00, "type": "200_day_ma", "distance_pct": -10.1}
```

This gives the trader context for where price might stall or accelerate.

### 14.5 Warm-up period

RSI requires 14 bars (70 min of 5-min bars) to stabilize. MACD requires 26 bars (130 min). During the warm-up period:

- RSI check is skipped (not counted as pass or fail)
- MACD check is skipped
- Only 1 of 2 remaining checks must pass (EMA alignment + S/R clearance)
- Signals are flagged with `warm_up=True` so the trader knows indicators aren't fully online

After recovery from a crash, warm-up period restarts.

-----

## 15. Market cap tier system (NEW)

### 15.1 Why tiers matter

A $2 stock with 5M float behaves completely differently from NVDA. Professional day traders use different strategies, different position sizes, and different expectations based on stock size. This system formalizes those differences.

### 15.2 Tier definitions

| Tier | Market cap range | Float expectation | Typical behavior |
|------|-----------------|-------------------|-----------------|
| SMALL | < $800M | Often < 20M | Explosive moves, wide spreads, gap-heavy, news-driven, higher risk |
| MID | $800M–$10B | 20M–200M | Moderate moves, decent liquidity, mix of technical and catalyst |
| LARGE | > $10B | > 200M | Tight spreads, institutional flow, pattern-reliable, lower per-share risk |

### 15.3 Parameter differences by tier

| Parameter | SMALL | MID | LARGE |
|-----------|-------|-----|-------|
| Min volume (shares) | 300,000 | 500,000 | 1,000,000 |
| Min dollar volume | $2M | $5M | $10M |
| RVOL threshold | 2.0 | 1.5 | 1.5 |
| Gap % for Gap & Go | ≥ 10% | ≥ 5% | ≥ 4% |
| ORB max range % | 5.0% | 3.5% | 3.0% |
| ORB min range % | 0.5% | 0.3% | 0.3% |
| VWAP pullback threshold | 5.0% | 3.5% | 3.0% |
| Max spread % | 0.8% | 0.5% | 0.3% |
| Stop multiplier | 1.3× | 1.0× | 1.0× |
| Time stop (ORB) | 20 min | 25 min | 30 min |
| Pre-market min volume | 200,000 | 50,000 | 20,000 |
| Risk per trade multiplier | 0.5× | 0.8× | 1.0× |
| Float rotation tracking | Yes | Yes (if float < 100M) | No |
| Short squeeze scoring | Yes (emphasized) | Yes | Yes (minimal weight) |
| Dilution risk check | Yes | Optional | No |

### 15.4 Tier assignment

```
def assign_tier(market_cap):
  if market_cap < 800_000_000:
    return "SMALL"
  elif market_cap < 10_000_000_000:
    return "MID"
  else:
    return "LARGE"
```

Tier is assigned once at pre-market profile load and does not change intraday (market cap doesn't shift meaningfully in one day).

-----

## 16. Risk management

### 16.1 Risk rules

| Rule | Default | Behavior when breached |
|------|---------|----------------------|
| Max risk per trade | $100 (adjusted by tier) | Adjust position size down, don't reject |
| Max daily loss | $500 | Stop all alerts for the day |
| Max concurrent signals | 5 (adjusted by regime) | Reject new signals until one closes |
| Max alerts per day | 20 | Stop new alerts for the day |
| Min reward/risk ratio | 1.0 | Reject signal |
| Max small-cap signals | 2 (of the 5 concurrent) | Prevent overexposure to high-risk tier |
| Max same-sector signals | 3 | Prevent sector concentration risk |

### 16.2 Alert prioritization

When multiple stocks trigger simultaneously:

1. Rank by composite score (descending)
2. Apply sector diversification filter (max 2 from same sector in top batch)
3. Apply tier diversification filter (max 1 small cap in top batch)
4. Notify top 3 only
5. Log remaining to CSV as "suppressed"

### 16.3 Signal status tracking

On every PriceTick for stocks with active signals:

- Check if price hit stop loss → status = STOPPED
- Check if price hit T1 → status = HIT_T1, engage trailing stop
- Check if price hit T2 → status = HIT_T2, signal closed
- Check time stop → status = EXPIRED
- Check gap fill (for Gap & Go) → if price < open, thesis broken → STOPPED
- Check float rotation → if rotation event fires, add note to signal
- Update daily P&L estimate on each close

-----

## 17. Notification system

### 17.1 Channels

| Channel | Setup | Free tier |
|---------|-------|-----------|
| **Gmail SMTP** | 2FA + App Password | Unlimited (personal) |
| **Telegram Bot** | BotFather → /newbot | Unlimited |
| **Desktop** | `pip install plyer` | N/A |

### 17.2 Alert message format

```
🔥 DAY TRADE ALERT — Score: 87/100 (+15 catalyst, +25 float, +10 squeeze)
📊 Regime: TRENDING | Phase: MORNING | Tier: LARGE

NVDA  $142.35  ▲ +4.2% gap  [Grade A]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📰 Earnings today (AMC) | EPS est: $0.82
🔍 Source: GAPPER (discovered pre-market)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
RVOL:           3.2x
Volatility:     5.1%
Momentum:       4.2%
Volume:         45.2M
Gap:            +4.2%
Float:          2.4B shares
Short Interest: 3.2% of float (1.8 days to cover)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📐 ORB Breakout — LONG
  Entry:        $142.50
  Stop:         $140.80
  Target 1:     $144.20  (R:R 1:1)
  Target 2:     $145.90  (R:R 2:1)
  Size:         58 shares ($100 risk)
  VWAP:         $141.60 ✅
  ORB range:    $140.80–$142.50 (1.2%)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🔬 Technical Confirmation: 4/4
  RSI(14): 62 ✅ | MACD: bullish ✅
  EMA: aligned ✅ | S/R: clear ✅
  Sector (XLK): +0.8% confirming ✅
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📍 Key Levels:
  Above: $145.50 prev day high | $148.00 52-wk high
  Below: $141.60 VWAP | $139.50 prev close
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
10:32 AM ET | 2 of 5 active signals
```

**Small-cap alert additions**:

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⚡ SMALL CAP — Enhanced volatility expected
Float:          8.2M shares (LOW FLOAT)
Float Rotation: 0.6x (5.1M traded / 8.2M float)
Short Interest: 22% of float ⚠️ SQUEEZE POTENTIAL
Dilution Risk:  ⚠️ Active shelf registration detected
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

### 17.3 Signal update notifications

Notifications also fire on status changes:

```
✅ NVDA HIT T1 — $144.20 reached (+1.2%)
   Trailing stop now at 9-EMA ($143.10)
   Float rotation: 1.2x ⚡
   
⛔ TSLA STOPPED — $265.30 hit (-0.8%)
   Daily P&L: -$120

🔄 MARA FLOAT ROTATED — 15.2M shares traded vs 12.8M float (1.19x)
   Score: 91 | Still ACTIVE at $18.45
```

-----

## 18. Market configuration

### 18.1 US market (fully built)

| Parameter | Value |
|-----------|-------|
| Timezone | America/New_York |
| Market hours | 09:30–16:00 |
| Pre-market scan start | 08:30 |
| Pre-market scan interval | Every 15 min |
| Full scan times | 09:15, 11:30, 15:00 |
| Min volume (large cap) | 1,000,000 |
| Min volume (small cap) | 300,000 |
| Min volatility | 2.0% |
| RVOL threshold | 1.5 |

**US base watchlist** (always monitored): AAPL, MSFT, GOOGL, AMZN, NVDA, META, TSLA, AMD, NFLX, BA, DIS, COIN, SOFI, PLTR, NIO, RIVN, LCID, SNAP, UBER, SQ, SHOP, ROKU, MARA, RIOT, GME, AMC, SPY, QQQ, IWM, INTC, MU, SMCI, ARM, CRWD, SNOW, DKNG

**US sector ETFs** (for sector momentum): XLK, XLF, XLE, XLV, XLY, XLI, XLC, XLB

**Note**: This base watchlist is supplemented daily by the dynamic watchlist engine (Section 7). On a typical day, 10–20 additional stocks will be discovered through gap scanning and top movers detection.

### 18.2 Other markets (templates)

| Market | TZ | Hours | Break | Min vol (large) | Min vol (small) | Min vol % | RVOL |
|--------|-----|-------|-------|-----------------|-----------------|-----------|------|
| HK | Asia/Hong_Kong | 09:30–16:00 | 12:00–13:00 | 500K | 200K | 2.5% | 1.5 |
| SG | Asia/Singapore | 09:00–17:00 | 12:00–13:00 | 200K | 100K | 1.5% | 1.3 |
| JP | Asia/Tokyo | 09:00–15:00 | 11:30–12:30 | 500K | 200K | 2.0% | 1.5 |
| TW | Asia/Taipei | 09:00–13:30 | None | 300K | 150K | 2.0% | 1.5 |
| UK | Europe/London | 08:00–16:30 | None | 300K | 100K | 1.5% | 1.3 |
| EU | Europe/Berlin | 09:00–17:30 | None | 200K | 100K | 1.5% | 1.3 |

-----

## 19. Performance and latency constraints

### 19.1 Latency SLAs

| Operation | Target | Acceptable | Unacceptable |
|-----------|--------|-----------|-------------|
| PriceTick processing (score + technical + strategy) | < 150ms | < 500ms | > 1s |
| Signal detection to Alert dispatch | < 300ms | < 1s | > 2s |
| State persistence (JSON write) | < 50ms | < 200ms | > 500ms |
| Notification delivery (send call) | < 2s | < 5s | > 10s |
| Regime + sector refresh (fetch + calc) | < 3s | < 5s | > 10s |
| Dynamic watchlist merge (on new gapper) | < 100ms | < 300ms | > 500ms |

### 19.2 Data freshness

| Data | Max staleness | Refresh trigger |
|------|-------------|-----------------|
| Hot list quotes (fast tier) | 15 seconds | Timer |
| Hot list quotes (normal tier) | 2 minutes | Timer |
| VWAP | 5 minutes | IntradayBar event |
| RSI / MACD | 5 minutes | IntradayBar event |
| Regime | 15 minutes | Timer |
| Sector momentum | 15 minutes | Timer |
| Catalysts / profiles | 1 day | Pre-market load |
| Daily bars (ATR, avg vol) | 1 day | Pre-market load |
| Short interest | 1 day (exchange data is biweekly) | Pre-market load |
| Dynamic watchlist | Updated every 15 min pre-market, every 30 min intraday | Timer |

### 19.3 Throughput

| Metric | Value |
|--------|-------|
| Max tickers in active watchlist | 50 (base + discovered) |
| Max hot list size | 12 (5 fast + 7 normal) |
| Max active signals | 5 (configurable, regime-adjusted) |
| Max events/second (peak) | ~60 (12 tickers × 5 event types) |
| Event bus throughput target | > 1,000 events/second |

-----

## 20. Backtesting

### 20.1 v1.0: Log replay

Reads completed signals from `trades.json`, scan CSVs, and watchlist logs. Computes:

- Win rate per strategy (ORB, VWAP Pullback, VWAP Breakout, Gap & Go)
- Win rate per market cap tier (SMALL, MID, LARGE)
- Average R:R achieved vs planned
- Profit factor and expectancy
- Score threshold optimization (what cutoff maximizes profit factor?)
- Time-of-day performance heatmap
- Strategy × regime cross-analysis
- Discovery source analysis (which source — GAPPER, TOP_MOVER, SQUEEZE_CANDIDATE, BASE_WATCHLIST — produces the best signals?)
- Float rotation correlation (do float rotation events predict better outcomes?)
- Technical confirmation analysis (does 4/4 confirmation outperform 2/4?)

### 20.2 v2.0 upgrade path: Full replay engine

Feed historical intraday candles through the full pipeline (discovery → scoring → strategy → state → risk) to simulate any parameter combination. Requires:

- Historical intraday data source (Finnhub paid tier or separate data dump)
- Walk-forward validation: train on 60% of days, validate on 40%
- Grid search over scoring weights, thresholds, strategy parameters, tier parameters

The v1.0 log replay module defines the `FullReplayEngine` interface so the upgrade is a drop-in.

-----

## 21. Configuration schema

All configuration lives in a single `config.json` file.

### 21.1 Full field reference

```
config.json
├── api_keys
│   ├── alpha_vantage: list[str]     # Key rotation pool, e.g. ["KEY_1", "KEY_2", "KEY_3"]
│   └── finnhub: str                 # Single Finnhub key
│
├── notifications
│   ├── gmail
│   │   ├── enabled: bool
│   │   ├── email: str               # Sender Gmail address
│   │   ├── app_password: str        # Google App Password (not regular password)
│   │   └── to_email: str            # Recipient email
│   ├── telegram
│   │   ├── enabled: bool
│   │   ├── bot_token: str           # From BotFather
│   │   └── chat_id: str             # From getUpdates API
│   └── desktop
│       └── enabled: bool            # Uses plyer package
│
├── scanner
│   ├── alert_threshold: int         # Default: 75 (adjusted by regime)
│   ├── cooldown_minutes: int        # Default: 30
│   ├── max_watchlist_size: int      # Default: 50
│   ├── hot_list_size: int           # Default: 12
│   ├── monitor_interval_seconds: int  # Default: 120
│   ├── dynamic_weights_enabled: bool  # Default: true
│   ├── score_decay_enabled: bool    # Default: true
│   ├── score_weights                # Fallback when dynamic weights off
│   │   ├── rvol: float              # Default: 0.25
│   │   ├── volatility: float        # Default: 0.20
│   │   ├── momentum: float          # Default: 0.25
│   │   ├── volume: float            # Default: 0.15
│   │   └── gap: float               # Default: 0.15
│   ├── dynamic_weight_overrides: dict  # Per-phase overrides, e.g. {"MIDDAY": {...}}
│   └── fast_mode
│       ├── enabled: bool            # Default: true
│       ├── top_n: int               # Default: 5
│       └── interval_seconds: int    # Default: 15
│
├── discovery
│   ├── enabled: bool                # Default: true
│   ├── gap_scan_enabled: bool       # Default: true
│   ├── top_movers_enabled: bool     # Default: true
│   ├── squeeze_scan_enabled: bool   # Default: true
│   ├── gap_min_pct_large: float     # Default: 3.0
│   ├── gap_min_pct_small: float     # Default: 10.0
│   ├── pre_market_min_vol_large: int  # Default: 20000
│   ├── pre_market_min_vol_small: int  # Default: 200000
│   ├── top_movers_min_change_pct: float  # Default: 5.0
│   ├── squeeze_min_short_pct: float # Default: 15.0
│   └── squeeze_min_short_ratio: float # Default: 3.0
│
├── risk
│   ├── max_risk_per_trade_usd: float  # Default: 100
│   ├── max_daily_loss_usd: float      # Default: 500
│   ├── max_concurrent_signals: int    # Default: 5
│   ├── max_alerts_per_day: int        # Default: 20
│   ├── min_reward_risk_ratio: float   # Default: 1.0
│   ├── max_small_cap_signals: int     # Default: 2
│   └── max_same_sector_signals: int   # Default: 3
│
├── strategy
│   ├── prefer_strategy: str           # "auto", "ORB", "VWAP_PULLBACK", "VWAP_BREAKOUT", "GAP_AND_GO"
│   ├── orb_window_minutes: int        # Default: 15
│   ├── vwap_buffer_pct: float         # Default: 0.1
│   ├── vwap_stop_pct: float           # Default: 0.3
│   ├── gap_and_go_enabled: bool       # Default: true
│   ├── gap_fill_heuristic_enabled: bool  # Default: true
│   └── flatten_before_close_minutes: int  # Default: 15
│   # Tier-specific overrides are in the tiers section
│
├── tiers
│   ├── small
│   │   ├── max_market_cap: int        # Default: 800000000
│   │   ├── min_volume: int            # Default: 300000
│   │   ├── min_dollar_volume: int     # Default: 2000000
│   │   ├── rvol_threshold: float      # Default: 2.0
│   │   ├── orb_max_range_pct: float   # Default: 5.0
│   │   ├── orb_min_range_pct: float   # Default: 0.5
│   │   ├── vwap_pullback_threshold_pct: float  # Default: 5.0
│   │   ├── max_spread_pct: float      # Default: 0.8
│   │   ├── stop_multiplier: float     # Default: 1.3
│   │   ├── time_stop_minutes: int     # Default: 20
│   │   ├── risk_multiplier: float     # Default: 0.5
│   │   ├── track_float_rotation: bool # Default: true
│   │   └── check_dilution: bool       # Default: true
│   ├── mid
│   │   ├── min_market_cap: int        # Default: 800000000
│   │   ├── max_market_cap: int        # Default: 10000000000
│   │   ├── min_volume: int            # Default: 500000
│   │   ├── min_dollar_volume: int     # Default: 5000000
│   │   ├── rvol_threshold: float      # Default: 1.5
│   │   ├── orb_max_range_pct: float   # Default: 3.5
│   │   ├── orb_min_range_pct: float   # Default: 0.3
│   │   ├── vwap_pullback_threshold_pct: float  # Default: 3.5
│   │   ├── max_spread_pct: float      # Default: 0.5
│   │   ├── stop_multiplier: float     # Default: 1.0
│   │   ├── time_stop_minutes: int     # Default: 25
│   │   ├── risk_multiplier: float     # Default: 0.8
│   │   ├── track_float_rotation: bool # Default: true
│   │   └── check_dilution: bool       # Default: false
│   └── large
│       ├── min_market_cap: int        # Default: 10000000000
│       ├── min_volume: int            # Default: 1000000
│       ├── min_dollar_volume: int     # Default: 10000000
│       ├── rvol_threshold: float      # Default: 1.5
│       ├── orb_max_range_pct: float   # Default: 3.0
│       ├── orb_min_range_pct: float   # Default: 0.3
│       ├── vwap_pullback_threshold_pct: float  # Default: 3.0
│       ├── max_spread_pct: float      # Default: 0.3
│       ├── stop_multiplier: float     # Default: 1.0
│       ├── time_stop_minutes: int     # Default: 30
│       ├── risk_multiplier: float     # Default: 1.0
│       ├── track_float_rotation: bool # Default: false
│       └── check_dilution: bool       # Default: false
│
├── technical
│   ├── rsi_enabled: bool              # Default: true
│   ├── rsi_period: int                # Default: 14
│   ├── rsi_overbought: int            # Default: 75
│   ├── rsi_oversold: int              # Default: 25
│   ├── macd_enabled: bool             # Default: true
│   ├── macd_fast: int                 # Default: 12
│   ├── macd_slow: int                 # Default: 26
│   ├── macd_signal: int               # Default: 9
│   ├── ema_periods: list[int]         # Default: [9, 20]
│   ├── key_levels_enabled: bool       # Default: true
│   ├── min_confirmations: int         # Default: 2
│   └── full_confirmation_bonus: int   # Default: 5
│
├── float_analysis
│   ├── float_scoring_enabled: bool    # Default: true
│   ├── float_rotation_enabled: bool   # Default: true
│   ├── max_float_bonus: int           # Default: 30
│   ├── max_squeeze_bonus: int         # Default: 30
│   ├── dilution_check_enabled: bool   # Default: true
│   └── dilution_penalty_pct: float    # Default: 0.5
│
├── regime
│   ├── enabled: bool                  # Default: true
│   ├── refresh_interval_minutes: int  # Default: 15
│   ├── sector_momentum_enabled: bool  # Default: true
│   └── benchmark                      # Per-market benchmark symbols
│       ├── US: str                    # "SPY"
│       ├── HK: str                    # "2800.HK"
│       ├── SG: str                    # "ES3.SI"
│       ├── JP: str                    # "1321.T"
│       ├── TW: str                    # "0050.TW"
│       ├── UK: str                    # "ISF.L"
│       └── EU: str                    # "EXSA.DE"
│
├── catalyst
│   ├── enabled: bool                  # Default: true
│   ├── max_score_boost: int           # Default: 25
│   ├── news_recency_decay: bool       # Default: true
│   ├── use_finnhub_sentiment: bool    # Default: true (fall back to keywords if unavailable)
│   ├── earnings_today_boost: int      # Default: 15
│   ├── earnings_tomorrow_boost: int   # Default: 8
│   ├── news_boost: int                # Default: 10
│   ├── fda_boost: int                 # Default: 15
│   ├── analyst_boost: int             # Default: 8
│   ├── squeeze_catalyst_boost: int    # Default: 12
│   ├── keywords_positive: list[str]   # Customizable keyword list
│   ├── keywords_negative: list[str]   # Customizable keyword list
│   └── keywords_mixed_handling: str   # Default: "neutral" (classify as neutral when tied)
│
├── liquidity
│   ├── enabled: bool                  # Default: true
│   ├── max_spread_pct: float          # Default: 0.5 (overridden per tier)
│   ├── min_price: float               # Default: 1.0
│   └── adjust_slippage: bool          # Default: true
│
└── markets
    └── {MARKET_CODE}                  # "US", "HK", "SG", etc.
        ├── enabled: bool
        ├── timezone: str              # IANA timezone
        ├── market_open: str           # "09:30"
        ├── market_close: str          # "16:00"
        ├── midday_break: list[str] | null  # ["12:00", "13:00"] or null
        ├── pre_market_scan_start: str # "08:30"
        ├── pre_market_scan_interval_minutes: int  # 15
        ├── full_scan_times: list[str] # ["09:15", "11:30", "15:00"]
        ├── base_watchlist: list[str]  # Ticker symbols (always monitored)
        └── sector_etfs: list[str]     # Sector ETFs for momentum tracking
```

-----

## 22. External service registration

All services are free. No credit card required. Total setup time: ~15 minutes.

### 22.1 Required

| # | Service | Purpose | Signup | What you get | Time |
|---|---------|---------|--------|-------------|------|
| 1 | **Alpha Vantage** (×1–3 keys) | Stock data (daily bars, quotes, history) | alphavantage.co/support → "Get Free API Key" | 16-char API key, instant, no approval | 30s per key |
| 2 | **Finnhub** | Real-time quotes, news, earnings, profiles, short interest | finnhub.io/register → verify email → dashboard | API token, email verification required | 2 min |
| 3 | **Python 3.8+** | Runtime | python.org/downloads | Then: `pip install requests plyer` | 5 min |

### 22.2 Optional (depends on notification choice)

| # | Service | Purpose | Signup | Notes |
|---|---------|---------|--------|-------|
| 4 | **Gmail SMTP** | Email alerts | Google Account → Security → 2FA → App Passwords → generate for "Mail" | No third-party signup; uses your Gmail |
| 5 | **Telegram Bot** | Mobile push alerts | Open Telegram → message @BotFather → /newbot → copy token. Then send any message to your bot, visit `api.telegram.org/bot<TOKEN>/getUpdates` to get chat_id | 2 min |
| 6 | **Desktop** | Screen pop-ups | No signup — just `pip install plyer` | Works on Windows, macOS, Linux |

### 22.3 API key management

- **Never commit keys to git.** Add `config.json` to `.gitignore`.
- **Rotate keys** if exposed. Alpha Vantage: get a new one at alphavantage.co/support. Finnhub: rotate in dashboard.
- **Multiple AV keys**: register multiple times with different email addresses to get 2–3 keys. Each adds 25 calls/day to the rotation pool.

-----

## 23. File structure

```
daytrader_scanner/
│
├── config.json              # All configuration
├── scanner.py               # Main entry point + event loop
├── event_bus.py             # In-process pub/sub event bus
├── data_client.py           # Alpha Vantage + Finnhub wrappers + key rotation
├── data_ingestion.py        # Raw API data → normalized events
├── rolling_state.py         # In-memory rolling calculations (VWAP, RVOL, EMA)
├── technical.py             # RSI, MACD, key levels, confirmation checks (NEW)
├── discovery.py             # Dynamic watchlist engine: gap scanner, top movers, squeeze finder (NEW)
├── scoring.py               # Scoring engine + dynamic weights + score decay
├── strategy.py              # ORB, VWAP Pullback, VWAP Breakout, Gap & Go
├── state_manager.py         # Signal lifecycle, cooldowns, dedup
├── risk_manager.py          # Risk rules, position sizing, prioritization, tier limits
├── session_phase.py         # Phase detection, phase rules, pre-market levels
├── regime.py                # Market regime detection + sector momentum + adjustments
├── catalyst.py              # News + earnings detection, sentiment, score boost + decay
├── liquidity.py             # Liquidity profiling, watchlist filter, slippage
├── float_analysis.py        # Float scoring, float rotation, short squeeze, dilution (NEW)
├── market_cap_tiers.py      # Tier assignment + tier-specific parameter resolution (NEW)
├── markets.py               # Market configs, trading hours, base watchlists
├── notifier.py              # Gmail / Telegram / Desktop handlers
├── scheduler.py             # Task scheduling, timer management
├── backtest.py              # Log replay + v2 interface stub
├── utils.py                 # Logging, time helpers
│
├── results/                 # Auto-created
│   ├── state.json           # Persistent runtime state (overwritten)
│   ├── trades.json          # Signal history (append-only, keep forever)
│   ├── scan_YYYY-MM-DD.csv  # Tick-level logs (keep 30 days)
│   ├── event_log.jsonl      # Raw event stream (rotated, keep 7 days)
│   ├── daily_summary_YYYY-MM-DD.json  # Daily stats (keep forever)
│   ├── watchlist_YYYY-MM-DD.json      # Dynamic watchlist log (keep 30 days) (NEW)
│   ├── liquidity_cache.json # Ticker profiles (overwritten daily)
│   └── catalyst_cache.json  # News + earnings cache (overwritten daily)
│
└── README.md                # Setup guide
```

-----

## 24. Development sequence

Build in this order:

1. **`event_bus.py`** — pub/sub foundation (everything depends on this)
2. **`utils.py`** — logging, time helpers
3. **`config.json`** — full schema with tier definitions
4. **`market_cap_tiers.py`** — tier assignment + parameter resolution (used everywhere)
5. **`data_client.py`** — API wrappers + key rotation pool
6. **`data_ingestion.py`** — raw data → typed events (PriceTick, DailyBar, GapperEvent, ShortInterestEvent, etc.)
7. **`rolling_state.py`** — VWAP, RVOL, ATR rolling calculations
8. **`technical.py`** — RSI, MACD, EMA, key levels, confirmation checks
9. **`session_phase.py`** — phase detection + pre-market level tracking
10. **`liquidity.py`** — profiling + watchlist pre-filter (tier-aware)
11. **`float_analysis.py`** — float scoring, float rotation, short squeeze scoring, dilution flags
12. **`discovery.py`** — gap scanner, top movers, squeeze candidates, watchlist merge
13. **`scoring.py`** — normalization + dynamic weights + score decay + gap/float/squeeze bonuses
14. **`regime.py`** — benchmark-based regime detection + sector momentum + adjustments
15. **`catalyst.py`** — news/earnings detection + sentiment (Finnhub first, keyword fallback) + recency decay
16. **`strategy.py`** — ORB, VWAP Pullback, VWAP Breakout, Gap & Go + strategy selector (tier-aware)
17. **`state_manager.py`** — signal lifecycle + cooldowns + dedup
18. **`risk_manager.py`** — risk rules + position sizing + prioritization + tier/sector limits
19. **`markets.py`** — market configs + trading hours + base watchlists + sector ETFs
20. **`notifier.py`** — all 3 channels + alert/update formatting (with new fields)
21. **`scheduler.py`** — task registration + timer loop + API budget tracker + discovery tasks
22. **`scanner.py`** — main entry: wire event bus, register all handlers, start loop
23. **`backtest.py`** — log replay + strategy stats + discovery analysis + tier analysis
24. **`README.md`** — setup guide

-----

## 25. Future enhancements (v2.0+)

- Full replay backtesting engine with historical intraday data
- Parameter optimization with walk-forward validation per tier
- Redis for state + event bus (multi-process)
- WebSocket streaming (Finnhub) for sub-second latency
- NLP/LLM-based news sentiment (replace keyword matching)
- SEC EDGAR integration for real dilution/filing tracking
- Level 2 order flow data (if broker API available)
- Sector heatmap aggregation
- Web dashboard (React) with live charts
- Watchlist auto-discovery from social media (StockTwits, Reddit)
- Options flow integration (unusual options activity as a catalyst)
- Broker API integration (Alpaca / Interactive Brokers)
- Discord / Slack notification channels
- Docker deployment
- Multi-market concurrent scanning (threading/asyncio)
- Machine learning model for signal quality prediction (trained on trades.json history)
