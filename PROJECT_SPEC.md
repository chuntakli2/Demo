# Day Trader Stock Scanner — Project Specification

> **Version**: 1.2
> **Last updated**: 2026-03-31
> **Status**: Ready for development

---

## 1. Project overview

A Python-based stock scanner that identifies high-potential day trading candidates across multiple markets. The system scores stocks using universal day-trading metrics (volume, volatility, momentum, relative volume), runs on a tiered schedule during market hours, and sends real-time alerts through the user's preferred notification channel. Each alert includes **estimated entry price, stop loss, and profit targets** calculated from three built-in trading strategies (ORB, VWAP Pullback, VWAP Breakout), giving the trader actionable trade plans — not just watchlist names.

Primary market: **US** (fully built).
Architecture supports: **HK, SG, JP, TW, UK, EU** (config templates included, ready to enable).

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────┐
│                    SCHEDULER                         │
│  Full scan: 2–3x/day (Alpha Vantage)                │
│  Hot list monitor: every 2 min (Finnhub)            │
└──────────────┬──────────────────────┬───────────────┘
               │                      │
       ┌───────▼───────┐      ┌───────▼───────┐
       │  DATA LAYER   │      │  DATA LAYER   │
       │ Alpha Vantage │      │   Finnhub     │
       │ (daily bars,  │      │ (real-time    │
       │  quotes)      │      │  quotes)      │
       └───────┬───────┘      └───────┬───────┘
               │                      │
               └──────────┬───────────┘
                          │
                ┌─────────▼─────────┐
                │  SCORING ENGINE   │
                │  (universal)      │
                │                   │
                │  RVOL ——— 30%     │
                │  Volatility — 25% │
                │  Momentum — 25%   │
                │  Volume ——— 20%   │
                └─────────┬─────────┘
                          │
               ┌──────────▼──────────┐
               │  MARKET CONFIG      │
               │  (per-market        │
               │   thresholds,       │
               │   watchlists,       │
               │   trading hours)    │
               └──────────┬──────────┘
                          │
               ┌──────────▼──────────┐
               │  STRATEGY ENGINE    │
               │  (entry/exit calc)  │
               │                     │
               │  ORB Breakout       │
               │  VWAP Pullback      │
               │  VWAP Breakout      │
               │                     │
               │  → entry price      │
               │  → stop loss        │
               │  → target 1 & 2    │
               │  → risk/reward      │
               └──────────┬──────────┘
                          │
          ┌───────────────┼───────────────┐
          │               │               │
   ┌──────▼──────┐ ┌─────▼──────┐ ┌──────▼──────┐
   │ NOTIFIER    │ │ CONSOLE    │ │ LOGGER      │
   │ Gmail SMTP  │ │ Terminal   │ │ CSV + JSON  │
   │ Desktop pop │ │ table      │ │ historical  │
   │ Telegram    │ │ output     │ │ records     │
   └─────────────┘ └────────────┘ └─────────────┘
```

---

## 3. External services and API keys

### 3.1 Required

| Service | Purpose | Free tier limits | Signup URL | What you get |
|---------|---------|-----------------|------------|--------------|
| **Alpha Vantage** | Full watchlist scans (daily bars, quotes, historical data) | 25 API calls/day | `alphavantage.co/support` → "Get Free API Key" | 16-char API key, instant, no approval |
| **Finnhub** | Hot list real-time monitoring (quotes every 2 min) | 60 API calls/min | `finnhub.io/register` | API token, email verification required |
| **Python 3.8+** | Runtime | N/A | `python.org/downloads` | Install, then run `pip install requests` |

### 3.2 Optional (depends on chosen notification method)

| Service | Purpose | Free tier limits | Signup URL | Notes |
|---------|---------|-----------------|------------|-------|
| **Gmail SMTP** | Email alerts | Unlimited (personal use) | Requires 2FA + App Password in Google Account → Security | No third-party signup; uses your existing Gmail |
| **Telegram Bot** | Mobile push alerts | Unlimited | Message `@BotFather` on Telegram → `/newbot` | Takes ~2 min; gives you a bot token + chat ID |
| **Desktop notifications** | Screen pop-ups | N/A | No signup needed | Uses OS-native notifications via `plyer` package |

### 3.3 Python packages

```
requests          # HTTP client (required)
plyer             # Desktop notifications (optional, install if using desktop alerts)
```

Install command:
```bash
pip install requests plyer
```

---

## 4. Scoring engine specification

The scoring engine is **universal** — identical logic for every market. Only the thresholds and watchlists change per market.

### 4.1 Input metrics

| Metric | Source | Calculation |
|--------|--------|-------------|
| **RVOL** (Relative Volume) | Alpha Vantage daily bars | `today_volume / avg_volume_20d` |
| **Volatility** (ATR %) | Alpha Vantage daily bars | `ATR(14) / current_price × 100` |
| **Intraday range %** | Alpha Vantage / Finnhub quote | `(day_high - day_low) / price × 100` |
| **Momentum** | Alpha Vantage / Finnhub quote | `abs(change_percent)` from previous close |
| **Volume** | Alpha Vantage / Finnhub quote | Raw daily volume (liquidity floor) |

### 4.2 Normalization (each metric → 0–100 scale)

```
rvol_score       = clamp((rvol - 1.0) × 50, 0, 100)
                   — 1.0 = normal, 3.0+ = exceptional (score 100)

volatility_score = clamp(volatility_pct × 20, 0, 100)
                   — 5%+ intraday range = score 100

momentum_score   = clamp(abs(change_pct) × 15, 0, 100)
                   — 6.7%+ move = score 100

volume_score     = clamp((volume / market_min_volume - 1) × 10, 0, 100)
                   — 10x min volume = score 100
```

### 4.3 Composite score

```
composite = (rvol_score       × 0.30)
          + (volatility_score × 0.25)
          + (momentum_score   × 0.25)
          + (volume_score     × 0.20)
```

**Range**: 0–100. Higher = better day-trading candidate.

### 4.4 Alert rules

| Rule | Value | Notes |
|------|-------|-------|
| **Alert threshold** | Score > 80 | Configurable in `config.json` |
| **Cooldown** | 30 minutes per stock | Prevents repeat alerts on the same ticker |
| **RVOL floor** | > 1.5 | Must show above-average volume activity |
| **Volume floor** | Must exceed market's `min_volume` | Ensures liquidity |

---

## 5. Scheduler specification

### 5.1 Tiered scanning

| Scan type | Frequency | Data source | Scope | Purpose |
|-----------|-----------|-------------|-------|---------|
| **Full scan** | 2–3× per day | Alpha Vantage | Entire watchlist (25–35 tickers) | Score and rank all stocks, rebuild hot list |
| **Hot list monitor** | Every 2 minutes | Finnhub | Top 10 scorers from last full scan | Track fast-moving stocks in near-real-time |

### 5.2 Full scan schedule (US market)

| Time (ET) | Event | Notes |
|-----------|-------|-------|
| 09:15 | Pre-market scan | Score full watchlist, build initial hot list |
| 11:30 | Midday rescan | Refresh rankings, catch late-morning movers |
| 15:00 | Power hour scan | Final rescan for last-hour momentum plays |

### 5.3 Hot list monitor behavior

- Runs every **2 minutes** between 09:30 ET and 16:00 ET
- Tracks the **top 10** stocks from the most recent full scan
- Recalculates score on each tick using fresh Finnhub quote data
- Fires alert **only when** score crosses above 80 and cooldown has expired
- Also fires if score **jumps 20+ points** between consecutive checks (sudden momentum shift)

### 5.4 API budget

| Source | Limit | Usage per day | Headroom |
|--------|-------|---------------|----------|
| Alpha Vantage | 25 calls/day | 3 full scans × ~8 calls each = ~24 | ~1 spare call |
| Finnhub | 60 calls/min | 10 stocks × 1 call every 2 min = 5 calls/min peak | 55 calls/min spare |

> **Note on Alpha Vantage budget**: With 25 calls/day and a 35-stock watchlist, we cannot quote every stock individually per scan. Strategy: use the `GLOBAL_QUOTE` endpoint for the full watchlist across all 3 scans, and use `TIME_SERIES_DAILY` (for ATR/RVOL history) only once per stock at the first scan of the day, then cache it. This keeps us within 25 calls/day.

---

## 6. Market configuration

### 6.1 US market (fully built)

```json
{
  "market_code": "US",
  "enabled": true,
  "timezone": "America/New_York",
  "market_open": "09:30",
  "market_close": "16:00",
  "full_scan_times": ["09:15", "11:30", "15:00"],
  "min_volume": 1000000,
  "min_volatility_pct": 2.0,
  "rvol_threshold": 1.5,
  "watchlist": [
    "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA",
    "AMD", "NFLX", "BA", "DIS", "COIN", "SOFI", "PLTR", "NIO",
    "RIVN", "LCID", "SNAP", "UBER", "SQ", "SHOP", "ROKU",
    "MARA", "RIOT", "GME", "AMC", "SPY", "QQQ", "IWM",
    "INTC", "MU", "SMCI", "ARM", "CRWD", "SNOW", "DKNG"
  ]
}
```

### 6.2 Other markets (templates — disabled by default)

| Market | Timezone | Hours | Min volume | Min volatility | Notes |
|--------|----------|-------|------------|----------------|-------|
| **HK** | Asia/Hong_Kong | 09:30–12:00, 13:00–16:00 | 500,000 | 2.5% | Has midday break |
| **SG** | Asia/Singapore | 09:00–12:00, 13:00–17:00 | 200,000 | 1.5% | Has midday break |
| **JP** | Asia/Tokyo | 09:00–11:30, 12:30–15:00 | 500,000 | 2.0% | Has midday break |
| **TW** | Asia/Taipei | 09:00–13:30 | 300,000 | 2.0% | Daily price limit ±10% |
| **UK** | Europe/London | 08:00–16:30 | 300,000 | 1.5% | Continuous session |
| **EU** | Europe/Berlin | 09:00–17:30 | 200,000 | 1.5% | Varies by exchange |

> When enabling non-US markets, the user must verify Alpha Vantage symbol format for that exchange (e.g. `0700.HK` for Hong Kong, `7203.T` for Tokyo).

---

## 7. Notification system

### 7.1 Notification channels

The user selects their preferred channel(s) in `config.json`. Multiple channels can be active simultaneously.

#### Gmail SMTP

```json
{
  "type": "gmail",
  "enabled": true,
  "email": "your.email@gmail.com",
  "app_password": "xxxx xxxx xxxx xxxx",
  "to_email": "your.email@gmail.com"
}
```

**Setup**: Google Account → Security → 2-Step Verification → App passwords → generate one for "Mail".

#### Telegram bot

```json
{
  "type": "telegram",
  "enabled": false,
  "bot_token": "123456:ABC-DEF...",
  "chat_id": "your_chat_id"
}
```

**Setup**:
1. Open Telegram, search for `@BotFather`
2. Send `/newbot`, follow prompts, copy the bot token
3. Start a chat with your new bot, send any message
4. Visit `https://api.telegram.org/bot<TOKEN>/getUpdates` to find your `chat_id`

#### Desktop notifications

```json
{
  "type": "desktop",
  "enabled": false
}
```

**Setup**: Just install `plyer` (`pip install plyer`). Works on Windows, macOS, and Linux.

### 7.2 Alert message format

```
🔥 DAY TRADE ALERT — Score: 87/100

NVDA  $142.35  ▲ +4.2%
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
RVOL:        3.2x (score: 100)
Volatility:  5.1% (score: 100)
Momentum:    4.2% (score: 63)
Volume:      45.2M (score: 88)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📐 TRADE PLAN (ORB Breakout)
  Entry:      $142.50  (break of ORB high)
  Stop loss:  $140.80  (below ORB low)
  Target 1:   $144.20  (1x range, R:R 1:1)
  Target 2:   $145.90  (2x range, R:R 1:2)
  Trail:      9-EMA after T1 hit
  Risk/share: $1.70
  ────────────────────────────
  VWAP:       $141.60  (price above ✅)
  ORB range:  $140.80 – $142.50
  Range size: $1.70 (1.2% of price)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Signal: Volume surge + ORB breakout setup
Time: 10:32 AM ET
```

### 7.3 Cooldown logic

```python
# Pseudocode
last_alert = {}  # { "NVDA": datetime(2026, 3, 31, 10, 32) }

def should_alert(symbol, score):
    if score < ALERT_THRESHOLD:
        return False
    if symbol in last_alert:
        elapsed = now() - last_alert[symbol]
        if elapsed < timedelta(minutes=30):
            return False
    return True
```

---

## 8. File structure

```
daytrader_scanner/
│
├── config.json              # User's API keys, market settings, notification prefs
├── scanner.py               # Main entry point — run this to start
├── data_client.py           # Alpha Vantage + Finnhub API wrappers
├── scoring.py               # Universal scoring engine + dynamic weights
├── strategy.py              # Entry/exit calculator (ORB, VWAP Pullback, VWAP Breakout)
├── state_manager.py         # State persistence: active trades, cooldowns, dedup, signals
├── session_phase.py         # Session phase detection + phase-specific rules
├── risk_manager.py          # Daily loss limit, max concurrent alerts, position sizing
├── regime.py                # Market regime detection (trending/choppy/volatile/low_vol)
├── catalyst.py              # News + earnings catalyst detection and score boost
├── liquidity.py             # Liquidity filter: float, spread, dollar volume, slippage adj
├── markets.py               # Market configs, watchlists, trading hours
├── notifier.py              # Gmail SMTP / Telegram bot / Desktop notifications
├── scheduler.py             # Tiered scan loop (full scan + hot list monitor + fast mode)
├── utils.py                 # Helpers: logging, time formatting, rate limiting
├── backtest.py              # Lightweight log replay + signal validation
│
├── results/                 # Auto-created output directory
│   ├── scan_YYYY-MM-DD.csv  # Daily scan logs (append per scan)
│   ├── trades.json          # Trade plan history (entry/exit/result tracking)
│   ├── alerts.json          # Alert history (for cooldown tracking)
│   └── state.json           # Persistent state (survives restarts)
│
└── README.md                # Beginner-friendly setup + usage guide
```

---

## 9. config.json schema

```json
{
  "api_keys": {
    "alpha_vantage": "YOUR_KEY_HERE",
    "finnhub": "YOUR_KEY_HERE"
  },

  "notifications": {
    "gmail": {
      "enabled": false,
      "email": "you@gmail.com",
      "app_password": "xxxx xxxx xxxx xxxx",
      "to_email": "you@gmail.com"
    },
    "telegram": {
      "enabled": false,
      "bot_token": "",
      "chat_id": ""
    },
    "desktop": {
      "enabled": true
    }
  },

  "scanner": {
    "alert_threshold": 80,
    "cooldown_minutes": 30,
    "hot_list_size": 10,
    "monitor_interval_seconds": 120,
    "fast_mode": {
      "enabled": true,
      "top_n": 3,
      "interval_seconds": 15
    },
    "score_weights": {
      "rvol": 0.30,
      "volatility": 0.25,
      "momentum": 0.25,
      "volume": 0.20
    }
  },

  "risk": {
    "max_risk_per_trade_usd": 100,
    "max_daily_loss_usd": 500,
    "max_concurrent_signals": 5,
    "max_alerts_per_day": 20,
    "min_reward_risk_ratio": 1.0
  },

  "markets": {
    "US": {
      "enabled": true,
      "timezone": "America/New_York",
      "market_open": "09:30",
      "market_close": "16:00",
      "full_scan_times": ["09:15", "11:30", "15:00"],
      "min_volume": 1000000,
      "min_volatility_pct": 2.0,
      "rvol_threshold": 1.5,
      "watchlist": [
        "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA",
        "AMD", "NFLX", "BA", "DIS", "COIN", "SOFI", "PLTR", "NIO",
        "RIVN", "LCID", "SNAP", "UBER", "SQ", "SHOP", "ROKU",
        "MARA", "RIOT", "GME", "AMC", "SPY", "QQQ", "IWM",
        "INTC", "MU", "SMCI", "ARM", "CRWD", "SNOW", "DKNG"
      ]
    },
    "HK": {
      "enabled": false,
      "timezone": "Asia/Hong_Kong",
      "market_open": "09:30",
      "market_close": "16:00",
      "midday_break": ["12:00", "13:00"],
      "full_scan_times": ["09:15", "13:15"],
      "min_volume": 500000,
      "min_volatility_pct": 2.5,
      "rvol_threshold": 1.5,
      "watchlist": [
        "0700.HK", "9988.HK", "1810.HK", "0005.HK", "0941.HK",
        "2318.HK", "3690.HK", "9618.HK", "0388.HK", "1299.HK"
      ]
    },
    "SG": {
      "enabled": false,
      "timezone": "Asia/Singapore",
      "market_open": "09:00",
      "market_close": "17:00",
      "midday_break": ["12:00", "13:00"],
      "full_scan_times": ["08:45", "13:15"],
      "min_volume": 200000,
      "min_volatility_pct": 1.5,
      "rvol_threshold": 1.3,
      "watchlist": [
        "D05.SI", "O39.SI", "U11.SI", "Z74.SI", "C6L.SI",
        "A17U.SI", "M44U.SI", "C38U.SI", "N2IU.SI", "Y92.SI"
      ]
    },
    "JP": {
      "enabled": false,
      "timezone": "Asia/Tokyo",
      "market_open": "09:00",
      "market_close": "15:00",
      "midday_break": ["11:30", "12:30"],
      "full_scan_times": ["08:45", "12:45"],
      "min_volume": 500000,
      "min_volatility_pct": 2.0,
      "rvol_threshold": 1.5,
      "watchlist": [
        "7203.T", "6758.T", "9984.T", "8306.T", "7974.T",
        "6861.T", "9433.T", "4063.T", "6098.T", "8035.T"
      ]
    },
    "TW": {
      "enabled": false,
      "timezone": "Asia/Taipei",
      "market_open": "09:00",
      "market_close": "13:30",
      "full_scan_times": ["08:45", "11:30"],
      "min_volume": 300000,
      "min_volatility_pct": 2.0,
      "rvol_threshold": 1.5,
      "watchlist": [
        "2330.TW", "2317.TW", "2454.TW", "2308.TW", "2382.TW",
        "2881.TW", "2891.TW", "2303.TW", "3711.TW", "2412.TW"
      ]
    },
    "UK": {
      "enabled": false,
      "timezone": "Europe/London",
      "market_open": "08:00",
      "market_close": "16:30",
      "full_scan_times": ["07:45", "12:00", "15:30"],
      "min_volume": 300000,
      "min_volatility_pct": 1.5,
      "rvol_threshold": 1.3,
      "watchlist": [
        "SHEL.L", "AZN.L", "HSBA.L", "ULVR.L", "BP.L",
        "GSK.L", "RIO.L", "LSEG.L", "DGE.L", "BATS.L"
      ]
    },
    "EU": {
      "enabled": false,
      "timezone": "Europe/Berlin",
      "market_open": "09:00",
      "market_close": "17:30",
      "full_scan_times": ["08:45", "12:30", "16:00"],
      "min_volume": 200000,
      "min_volatility_pct": 1.5,
      "rvol_threshold": 1.3,
      "watchlist": [
        "SAP.DE", "SIE.DE", "ALV.DE", "BAS.DE", "MBG.DE",
        "AIR.PA", "MC.PA", "OR.PA", "SAN.PA", "BNP.PA"
      ]
    }
  }
}
```

---

## 10. Trading strategy engine

The scanner finds *what* to trade. The strategy engine calculates *how* to trade it — computing estimated entry price, stop loss, profit targets, and risk/reward ratio for every stock that crosses the alert threshold. The engine evaluates three strategies per stock and selects the best fit based on current market conditions and time of day.

### 10.1 Strategy selection logic

```python
def select_strategy(stock_data, time_of_day, market_config):
    """
    Evaluate all three strategies, return the best-fit trade plan.
    Priority: ORB (first 45 min) > VWAP Pullback (mid-session) > VWAP Breakout (any time)
    """
    minutes_since_open = time_since_market_open(time_of_day, market_config)

    strategies = []

    # ORB is only valid in the first ~45 minutes after open
    if minutes_since_open <= 45:
        orb = evaluate_orb(stock_data, market_config)
        if orb["valid"]:
            strategies.append(orb)

    # VWAP Pullback requires a prior strong move + pullback pattern
    vwap_pb = evaluate_vwap_pullback(stock_data)
    if vwap_pb["valid"]:
        strategies.append(vwap_pb)

    # VWAP Breakout can trigger any time
    vwap_bo = evaluate_vwap_breakout(stock_data)
    if vwap_bo["valid"]:
        strategies.append(vwap_bo)

    # Return the strategy with the best risk/reward ratio
    if strategies:
        return max(strategies, key=lambda s: s["reward_risk_ratio"])
    return None
```

### 10.2 Strategy 1: Opening Range Breakout (ORB)

The most structured and beginner-friendly strategy. It uses the first 15 minutes of trading to define a price range, then trades the breakout.

#### When to use

- First 45 minutes after market open only
- Best on days with a news catalyst, earnings, or sector momentum
- Scanner score > 80 with RVOL > 2.0

#### Rules

| Component | Long setup | Short setup |
|-----------|-----------|-------------|
| **Opening range** | High and low of first 15 minutes (3 × 5-min candles) | Same |
| **Entry trigger** | 5-min candle closes above ORB high | 5-min candle closes below ORB low |
| **Volume confirm** | Breakout candle volume ≥ 1.5× avg 15-min volume | Same |
| **VWAP confirm** | Price must be above VWAP | Price must be below VWAP |
| **Entry price** | ORB high + $0.02 buffer (1 tick above) | ORB low − $0.02 buffer |
| **Stop loss** | ORB low − $0.05 (below entire range) | ORB high + $0.05 |
| **Target 1** | Entry + 1× range height (1:1 R:R) | Entry − 1× range height |
| **Target 2** | Entry + 2× range height (2:1 R:R) | Entry − 2× range height |
| **Trailing stop** | Below 9-EMA or VWAP after T1 hit | Above 9-EMA or VWAP |
| **Time stop** | Exit if no move within 30 min of entry | Same |
| **Session stop** | Close all positions before market close | Same |
| **Max trades** | 1 trade per side per session per stock | Same |

#### Price calculation (pseudocode)

```python
def calculate_orb_levels(stock_data):
    """
    Requires: first 15 minutes of intraday bars (3 × 5-min candles).
    """
    orb_high = max(bar["high"] for bar in first_15min_bars)
    orb_low  = min(bar["low"]  for bar in first_15min_bars)
    orb_range = orb_high - orb_low
    current_price = stock_data["price"]
    vwap = stock_data["vwap"]

    # Range too wide = too risky. Skip if range > 3% of price.
    range_pct = (orb_range / current_price) * 100
    if range_pct > 3.0:
        return {"valid": False, "reason": "ORB range too wide"}

    # Range too tight = no edge. Skip if range < 0.3% of price.
    if range_pct < 0.3:
        return {"valid": False, "reason": "ORB range too tight"}

    # Determine direction
    if current_price > orb_high and current_price > vwap:
        direction = "LONG"
        entry     = orb_high + 0.02
        stop_loss = orb_low - 0.05
        target_1  = entry + orb_range       # 1:1
        target_2  = entry + (orb_range * 2) # 2:1
    elif current_price < orb_low and current_price < vwap:
        direction = "SHORT"
        entry     = orb_low - 0.02
        stop_loss = orb_high + 0.05
        target_1  = entry - orb_range
        target_2  = entry - (orb_range * 2)
    else:
        return {"valid": False, "reason": "No breakout detected"}

    risk_per_share = abs(entry - stop_loss)
    reward_1 = abs(target_1 - entry)
    reward_2 = abs(target_2 - entry)

    return {
        "valid": True,
        "strategy": "ORB",
        "direction": direction,
        "entry": round(entry, 2),
        "stop_loss": round(stop_loss, 2),
        "target_1": round(target_1, 2),
        "target_2": round(target_2, 2),
        "risk_per_share": round(risk_per_share, 2),
        "reward_risk_ratio": round(reward_1 / risk_per_share, 2),
        "orb_high": round(orb_high, 2),
        "orb_low": round(orb_low, 2),
        "orb_range": round(orb_range, 2),
        "orb_range_pct": round(range_pct, 2),
        "vwap": round(vwap, 2),
    }
```

### 10.3 Strategy 2: VWAP Pullback

For stocks that have already made a strong move and are pulling back to VWAP. Best used after the first hour of trading.

#### When to use

- Stock is up/down 3%+ on the day with RVOL ≥ 2.0
- Price has pulled back toward VWAP or 20-period MA
- Best during 10:00 AM – 2:00 PM window

#### Rules

| Component | Long setup | Short setup |
|-----------|-----------|-------------|
| **Pre-condition** | Stock up ≥ 3% on the day, RVOL ≥ 2.0 | Stock down ≥ 3%, RVOL ≥ 2.0 |
| **Pullback zone** | Price within 0.5% of VWAP (from above) | Price within 0.5% of VWAP (from below) |
| **Volume on pullback** | Declining (lower than the move candles) | Declining |
| **Entry trigger** | First 5-min candle that holds VWAP and closes green | First 5-min candle that holds below VWAP and closes red |
| **Entry price** | High of the reversal candle + $0.02 | Low of the reversal candle − $0.02 |
| **Stop loss** | Below the pullback low − $0.10 | Above the pullback high + $0.10 |
| **Target 1** | Retest of day's high | Retest of day's low |
| **Target 2** | Day's high + 0.5× the prior move | Day's low − 0.5× the prior move |
| **Trailing stop** | Below VWAP (if it loses VWAP, thesis broken) | Above VWAP |

#### Price calculation (pseudocode)

```python
def calculate_vwap_pullback_levels(stock_data):
    """
    Requires: current quote, VWAP, day high/low, change %.
    """
    price      = stock_data["price"]
    vwap       = stock_data["vwap"]
    day_high   = stock_data["day_high"]
    day_low    = stock_data["day_low"]
    change_pct = stock_data["change_pct"]
    prev_close = stock_data["prev_close"]

    # Must have made a strong move already
    if abs(change_pct) < 3.0:
        return {"valid": False, "reason": "Move too small for pullback play"}

    # Price must be near VWAP (within 0.5%)
    distance_to_vwap_pct = abs(price - vwap) / price * 100
    if distance_to_vwap_pct > 0.5:
        return {"valid": False, "reason": "Price not near VWAP"}

    if change_pct > 0 and price >= vwap:
        # Bullish pullback — stock is up, pulling back to VWAP from above
        direction  = "LONG"
        entry      = price + 0.02
        stop_loss  = min(vwap, day_low) - 0.10
        target_1   = day_high
        move_size  = day_high - prev_close
        target_2   = day_high + (move_size * 0.5)
    elif change_pct < 0 and price <= vwap:
        direction  = "SHORT"
        entry      = price - 0.02
        stop_loss  = max(vwap, day_high) + 0.10
        target_1   = day_low
        move_size  = prev_close - day_low
        target_2   = day_low - (move_size * 0.5)
    else:
        return {"valid": False, "reason": "Price/VWAP alignment mismatch"}

    risk_per_share = abs(entry - stop_loss)
    if risk_per_share < 0.05:
        return {"valid": False, "reason": "Risk too small (spread noise)"}

    reward_1 = abs(target_1 - entry)

    return {
        "valid": True,
        "strategy": "VWAP_PULLBACK",
        "direction": direction,
        "entry": round(entry, 2),
        "stop_loss": round(stop_loss, 2),
        "target_1": round(target_1, 2),
        "target_2": round(target_2, 2),
        "risk_per_share": round(risk_per_share, 2),
        "reward_risk_ratio": round(reward_1 / risk_per_share, 2) if risk_per_share > 0 else 0,
        "vwap": round(vwap, 2),
        "pullback_distance_pct": round(distance_to_vwap_pct, 2),
    }
```

### 10.4 Strategy 3: VWAP Breakout

The simplest strategy. Trades the initial break through VWAP with volume confirmation. Can trigger at any time during the session.

#### When to use

- Any time during market hours
- Price is crossing VWAP with strong volume (RVOL ≥ 1.5)
- Works best after 9:45 AM (VWAP needs time to stabilize)

#### Rules

| Component | Long setup | Short setup |
|-----------|-----------|-------------|
| **Entry trigger** | Price breaks above VWAP with volume ≥ 1.5× average | Price breaks below VWAP with volume ≥ 1.5× average |
| **Time filter** | Not before 9:45 AM (VWAP needs ~15 min to stabilize) | Same |
| **Entry price** | VWAP + 0.1% buffer | VWAP − 0.1% buffer |
| **Stop loss** | VWAP − 0.3% (just below VWAP) | VWAP + 0.3% |
| **Target 1** | Day's high (if long) or prior resistance | Day's low (if short) or prior support |
| **Target 2** | VWAP + 1× ATR | VWAP − 1× ATR |
| **Exit signal** | Price closes back below VWAP (thesis broken) | Price closes back above VWAP |

#### Price calculation (pseudocode)

```python
def calculate_vwap_breakout_levels(stock_data):
    """
    Requires: current quote, VWAP, ATR, day high/low.
    """
    price    = stock_data["price"]
    vwap     = stock_data["vwap"]
    atr      = stock_data["atr"]
    day_high = stock_data["day_high"]
    day_low  = stock_data["day_low"]
    rvol     = stock_data["rvol"]

    # Need volume confirmation
    if rvol < 1.5:
        return {"valid": False, "reason": "Insufficient volume for VWAP break"}

    buffer = vwap * 0.001  # 0.1% buffer
    stop_distance = vwap * 0.003  # 0.3% stop

    if price > vwap + buffer:
        direction = "LONG"
        entry     = vwap + buffer
        stop_loss = vwap - stop_distance
        target_1  = day_high if day_high > price else price + (atr * 0.5)
        target_2  = vwap + atr
    elif price < vwap - buffer:
        direction = "SHORT"
        entry     = vwap - buffer
        stop_loss = vwap + stop_distance
        target_1  = day_low if day_low < price else price - (atr * 0.5)
        target_2  = vwap - atr
    else:
        return {"valid": False, "reason": "Price not decisively through VWAP"}

    risk_per_share = abs(entry - stop_loss)
    if risk_per_share < 0.05:
        return {"valid": False, "reason": "Risk too small"}

    reward_1 = abs(target_1 - entry)

    return {
        "valid": True,
        "strategy": "VWAP_BREAKOUT",
        "direction": direction,
        "entry": round(entry, 2),
        "stop_loss": round(stop_loss, 2),
        "target_1": round(target_1, 2),
        "target_2": round(target_2, 2),
        "risk_per_share": round(risk_per_share, 2),
        "reward_risk_ratio": round(reward_1 / risk_per_share, 2) if risk_per_share > 0 else 0,
        "vwap": round(vwap, 2),
    }
```

### 10.5 Strategy timing matrix

| Time window (US ET) | Best strategy | Why |
|---------------------|---------------|-----|
| 09:15 – 09:30 | Pre-scan only | Build watchlist, no trades yet |
| 09:30 – 09:45 | Wait | ORB range forming, VWAP not stable |
| 09:45 – 10:15 | **ORB Breakout** | Opening range established, first breakouts fire |
| 10:15 – 11:30 | **VWAP Pullback** | Early movers pulling back to VWAP for re-entry |
| 11:30 – 14:00 | **VWAP Pullback** or **VWAP Breakout** | Midday — look for setups at VWAP |
| 14:00 – 15:00 | **VWAP Breakout** | Afternoon momentum picks up |
| 15:00 – 15:45 | **ORB Breakout** (power hour) | Use last-hour range as a mini-ORB |
| 15:45 – 16:00 | Flatten all | Close all positions before market close |

### 10.6 Multi-market adaptations

The three strategies work on every market, but the parameters adjust:

| Parameter | US | HK | SG | JP | TW | UK |
|-----------|----|----|----|----|----|----|
| ORB window | 15 min | 15 min | 15 min | 15 min | 15 min | 15 min |
| ORB max range % | 3.0% | 3.5% | 2.5% | 3.0% | 3.0%* | 2.5% |
| VWAP buffer | 0.1% | 0.15% | 0.1% | 0.1% | 0.1% | 0.1% |
| VWAP stop | 0.3% | 0.4% | 0.3% | 0.3% | 0.3% | 0.3% |
| Pullback threshold | 3.0% | 3.5% | 2.0% | 3.0% | 3.0% | 2.0% |
| Post-break ORB | After midday break | After midday break | After midday break | After midday break | N/A | N/A |

> *TW has daily price limits of ±10%, which naturally caps the ORB range. Adjust stop loss to account for limit-lock scenarios.

> **Midday break markets** (HK, SG, JP): The midday break creates a second opening range. The strategy engine treats the first 15 minutes after the break resumes as a new ORB window — this "afternoon ORB" often produces strong moves as traders react to lunchtime order accumulation.

### 10.7 Position sizing (informational)

The strategy engine calculates risk/reward but does **not** auto-trade. It outputs a suggested position size based on a configurable max risk per trade:

```python
def suggest_position_size(risk_per_share, max_risk_dollars):
    """
    max_risk_dollars = how much you're willing to lose on this trade.
    Default: $100 (configurable in config.json)
    """
    if risk_per_share <= 0:
        return 0
    shares = int(max_risk_dollars / risk_per_share)
    return shares

# Example:
# risk_per_share = $1.70 (distance from entry to stop)
# max_risk_dollars = $100
# shares = 58 shares
```

Config setting:
```json
{
  "strategy": {
    "max_risk_per_trade_usd": 100,
    "prefer_strategy": "auto",
    "orb_window_minutes": 15,
    "orb_max_range_pct": 3.0,
    "vwap_pullback_threshold_pct": 3.0,
    "vwap_buffer_pct": 0.1,
    "vwap_stop_pct": 0.3,
    "time_stop_minutes": 30,
    "flatten_before_close_minutes": 15
  }
}
```

### 10.8 VWAP calculation

Since Alpha Vantage and Finnhub don't directly provide VWAP, the engine calculates it from intraday bars:

```python
def calculate_vwap(intraday_bars):
    """
    VWAP = cumulative(typical_price × volume) / cumulative(volume)
    typical_price = (high + low + close) / 3
    
    Calculated from market open. Resets each day.
    For midday-break markets, VWAP continues across the break (does not reset).
    """
    cumulative_tp_vol = 0
    cumulative_vol = 0

    for bar in intraday_bars:
        typical_price = (bar["high"] + bar["low"] + bar["close"]) / 3
        cumulative_tp_vol += typical_price * bar["volume"]
        cumulative_vol += bar["volume"]

    if cumulative_vol == 0:
        return None

    return cumulative_tp_vol / cumulative_vol
```

> **API budget note**: VWAP calculation requires intraday bars. Finnhub `/stock/candle` with 5-min resolution provides this within the free tier. One call per stock per scan cycle. This does NOT use the Alpha Vantage budget.

### 10.9 Trade plan output structure

Every alert includes a full trade plan object:

```json
{
  "symbol": "NVDA",
  "timestamp": "2026-03-31T10:32:00-04:00",
  "composite_score": 87,
  "strategy": "ORB",
  "direction": "LONG",
  "entry_price": 142.50,
  "stop_loss": 140.80,
  "target_1": 144.20,
  "target_2": 145.90,
  "risk_per_share": 1.70,
  "reward_risk_ratio": 1.0,
  "suggested_shares": 58,
  "max_risk_usd": 100.00,
  "vwap": 141.60,
  "orb_high": 142.50,
  "orb_low": 140.80,
  "orb_range_pct": 1.2,
  "conditions": {
    "vwap_aligned": true,
    "volume_confirmed": true,
    "range_valid": true
  },
  "notes": "ORB breakout with 3.2x RVOL. VWAP support at $141.60. Range 1.2% — normal width."
}
```

This object is:
- Printed to console in the formatted alert
- Sent via the chosen notification channel
- Logged to `results/trades.json` for historical tracking
- Appended to `results/scan_YYYY-MM-DD.csv` with entry/exit columns

### 10.10 State manager (`state_manager.py`)

The state manager is the central nervous system that prevents duplicate alerts, tracks active trade signals, manages cooldowns, and persists across restarts. Without it, the 2-minute hot list monitor would spam identical alerts.

#### State structure

```python
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional
import json
from pathlib import Path

STATE_FILE = Path("results/state.json")

@dataclass
class TradeSignal:
    symbol: str
    strategy: str
    direction: str           # "LONG" or "SHORT"
    entry_price: float
    stop_loss: float
    target_1: float
    target_2: float
    composite_score: float
    triggered_at: str        # ISO timestamp
    status: str              # "PENDING" | "ACTIVE" | "HIT_T1" | "HIT_T2" | "STOPPED" | "EXPIRED" | "CLOSED"
    result_pct: Optional[float] = None
    closed_at: Optional[str] = None

@dataclass
class StateManager:
    active_signals: dict = field(default_factory=dict)    # { "NVDA": TradeSignal }
    alert_cooldowns: dict = field(default_factory=dict)   # { "NVDA": "2026-03-31T10:32:00" }
    triggered_today: dict = field(default_factory=dict)   # { "NVDA": ["ORB", "VWAP_BREAKOUT"] }
    daily_pnl_usd: float = 0.0
    alerts_sent_today: int = 0
    scan_date: str = ""                                   # Resets when date changes

    def should_alert(self, symbol, strategy, score, config):
        """
        Returns True only if ALL conditions pass:
        1. Score above threshold
        2. Cooldown expired for this ticker
        3. This strategy hasn't already fired for this ticker today
        4. Not at max concurrent signals
        5. Daily loss limit not hit
        6. Max alerts per day not hit
        """
        now = datetime.now()

        # Check date rollover — reset daily counters
        today = now.strftime("%Y-%m-%d")
        if self.scan_date != today:
            self._reset_daily(today)

        # 1. Score threshold
        if score < config["scanner"]["alert_threshold"]:
            return False, "Score below threshold"

        # 2. Cooldown
        if symbol in self.alert_cooldowns:
            last_alert = datetime.fromisoformat(self.alert_cooldowns[symbol])
            cooldown_min = config["scanner"]["cooldown_minutes"]
            if (now - last_alert).total_seconds() < cooldown_min * 60:
                return False, f"Cooldown active ({cooldown_min}min)"

        # 3. Dedup — same strategy already fired today
        if symbol in self.triggered_today:
            if strategy in self.triggered_today[symbol]:
                return False, f"{strategy} already triggered for {symbol} today"

        # 4. Max concurrent signals
        active_count = len([s for s in self.active_signals.values() if s.status in ("PENDING", "ACTIVE", "HIT_T1")])
        max_concurrent = config.get("risk", {}).get("max_concurrent_signals", 5)
        if active_count >= max_concurrent:
            return False, f"Max concurrent signals ({max_concurrent}) reached"

        # 5. Daily loss limit
        max_daily_loss = config.get("risk", {}).get("max_daily_loss_usd", 500)
        if self.daily_pnl_usd <= -max_daily_loss:
            return False, f"Daily loss limit (${max_daily_loss}) hit"

        # 6. Max alerts per day
        max_alerts = config.get("risk", {}).get("max_alerts_per_day", 20)
        if self.alerts_sent_today >= max_alerts:
            return False, f"Max daily alerts ({max_alerts}) reached"

        return True, "OK"

    def register_signal(self, symbol, trade_plan):
        """Record a new signal and update all state."""
        now = datetime.now().isoformat()

        signal = TradeSignal(
            symbol=symbol,
            strategy=trade_plan["strategy"],
            direction=trade_plan["direction"],
            entry_price=trade_plan["entry"],
            stop_loss=trade_plan["stop_loss"],
            target_1=trade_plan["target_1"],
            target_2=trade_plan["target_2"],
            composite_score=trade_plan.get("composite_score", 0),
            triggered_at=now,
            status="PENDING"
        )

        self.active_signals[symbol] = signal
        self.alert_cooldowns[symbol] = now
        self.alerts_sent_today += 1

        if symbol not in self.triggered_today:
            self.triggered_today[symbol] = []
        self.triggered_today[symbol].append(trade_plan["strategy"])

        self.persist()

    def update_signal_status(self, symbol, current_price):
        """
        Check if an active signal has hit target or stop.
        Called on every hot list tick.
        """
        if symbol not in self.active_signals:
            return None

        signal = self.active_signals[symbol]
        if signal.status in ("STOPPED", "EXPIRED", "CLOSED", "HIT_T2"):
            return None

        is_long = signal.direction == "LONG"

        # Check stop loss
        if (is_long and current_price <= signal.stop_loss) or \
           (not is_long and current_price >= signal.stop_loss):
            signal.status = "STOPPED"
            signal.result_pct = ((current_price - signal.entry_price) / signal.entry_price) * 100
            if not is_long:
                signal.result_pct = -signal.result_pct
            signal.closed_at = datetime.now().isoformat()
            self.daily_pnl_usd += signal.result_pct  # simplified
            self.persist()
            return "STOPPED"

        # Check target 1
        if signal.status == "PENDING" or signal.status == "ACTIVE":
            if (is_long and current_price >= signal.target_1) or \
               (not is_long and current_price <= signal.target_1):
                signal.status = "HIT_T1"
                self.persist()
                return "HIT_T1"

        # Check target 2
        if signal.status == "HIT_T1":
            if (is_long and current_price >= signal.target_2) or \
               (not is_long and current_price <= signal.target_2):
                signal.status = "HIT_T2"
                signal.result_pct = ((current_price - signal.entry_price) / signal.entry_price) * 100
                if not is_long:
                    signal.result_pct = -signal.result_pct
                signal.closed_at = datetime.now().isoformat()
                self.persist()
                return "HIT_T2"

        return None

    def expire_all_signals(self):
        """Called at market close — expire any still-active signals."""
        now = datetime.now().isoformat()
        for symbol, signal in self.active_signals.items():
            if signal.status in ("PENDING", "ACTIVE", "HIT_T1"):
                signal.status = "EXPIRED"
                signal.closed_at = now
        self.persist()

    def _reset_daily(self, today):
        """Reset daily counters on date change."""
        self.scan_date = today
        self.daily_pnl_usd = 0.0
        self.alerts_sent_today = 0
        self.triggered_today = {}
        # Move yesterday's active signals to expired
        self.expire_all_signals()
        self.active_signals = {}
        self.persist()

    def persist(self):
        """Save state to disk for crash recovery."""
        STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "scan_date": self.scan_date,
            "daily_pnl_usd": self.daily_pnl_usd,
            "alerts_sent_today": self.alerts_sent_today,
            "alert_cooldowns": self.alert_cooldowns,
            "triggered_today": self.triggered_today,
            "active_signals": {
                k: vars(v) for k, v in self.active_signals.items()
            }
        }
        with open(STATE_FILE, "w") as f:
            json.dump(data, f, indent=2)

    @classmethod
    def load(cls):
        """Load state from disk, or return fresh state."""
        if STATE_FILE.exists():
            try:
                with open(STATE_FILE, "r") as f:
                    data = json.load(f)
                state = cls()
                state.scan_date = data.get("scan_date", "")
                state.daily_pnl_usd = data.get("daily_pnl_usd", 0.0)
                state.alerts_sent_today = data.get("alerts_sent_today", 0)
                state.alert_cooldowns = data.get("alert_cooldowns", {})
                state.triggered_today = data.get("triggered_today", {})
                for sym, sig_data in data.get("active_signals", {}).items():
                    state.active_signals[sym] = TradeSignal(**sig_data)
                return state
            except Exception:
                pass
        return cls()
```

#### State flow diagram

```
New scan tick
    │
    ▼
StateManager.should_alert(symbol, strategy, score, config)
    │
    ├─ NO  → skip, log to CSV only (no notification)
    │
    └─ YES → Strategy engine calculates trade plan
              │
              ▼
         StateManager.register_signal(symbol, trade_plan)
              │
              ▼
         Notifier sends alert with trade plan
              │
              ▼
         Signal status = "PENDING"
              │
    ┌─────────┴──────────────────────────────────┐
    │ Every 2-min tick (or 15s in fast mode):     │
    │ StateManager.update_signal_status(sym, px)  │
    │                                             │
    │   price hits stop  → status = "STOPPED"     │
    │   price hits T1    → status = "HIT_T1"      │
    │   price hits T2    → status = "HIT_T2"      │
    │   market close     → status = "EXPIRED"     │
    └─────────────────────────────────────────────┘
```

### 10.11 Fast polling mode

For the top-scoring stocks, 2-minute polling may miss fast breakouts. Fast mode drops to 15-second polling for a small subset.

#### Design

```python
# In scheduler.py — the hot list loop becomes two-tier:

FAST_MODE_TOP_N = 3          # Configurable in config.json
FAST_MODE_INTERVAL_SEC = 15  # Configurable
NORMAL_INTERVAL_SEC = 120    # 2 minutes

def hot_list_loop(hot_list, state, config):
    """
    Split hot list into fast tier and normal tier.
    Fast tier: top 3 by score, polled every 15 seconds.
    Normal tier: remaining stocks, polled every 2 minutes.
    """
    fast_tier = hot_list[:FAST_MODE_TOP_N]    # Top 3
    normal_tier = hot_list[FAST_MODE_TOP_N:]  # Rest

    # Fast tier runs in a tight loop
    fast_last_check = {}
    normal_last_check = {}

    while market_is_open():
        now = time.time()

        # Fast tier — every 15 seconds
        for symbol in fast_tier:
            last = fast_last_check.get(symbol, 0)
            if now - last >= FAST_MODE_INTERVAL_SEC:
                quote = finnhub_client.get_quote(symbol)
                process_tick(symbol, quote, state, config)
                fast_last_check[symbol] = now

        # Normal tier — every 2 minutes
        for symbol in normal_tier:
            last = normal_last_check.get(symbol, 0)
            if now - last >= NORMAL_INTERVAL_SEC:
                quote = finnhub_client.get_quote(symbol)
                process_tick(symbol, quote, state, config)
                normal_last_check[symbol] = now

        time.sleep(5)  # Main loop sleeps 5s, checks which tier is due
```

#### API budget (fast mode)

```
Fast tier:  3 stocks × 4 calls/min (every 15s) = 12 calls/min
Normal tier: 7 stocks × 0.5 calls/min (every 2m) = 3.5 calls/min
Total: ~16 calls/min — well within Finnhub's 60/min limit
```

#### Config

```json
{
  "scanner": {
    "fast_mode": {
      "enabled": true,
      "top_n": 3,
      "interval_seconds": 15
    },
    "normal_interval_seconds": 120
  }
}
```

### 10.12 Session phase system (`session_phase.py`)

Markets behave differently at different times of day. The session phase system controls which strategies are eligible, what thresholds apply, and what data is being collected.

#### Phase definitions

```python
from enum import Enum

class SessionPhase(Enum):
    PRE_MARKET    = "pre_market"      # Before open: collect levels, no alerts
    OPENING_RANGE = "opening_range"   # First 15 min: building ORB, no trades
    MORNING       = "morning"         # 09:45–11:30: ORB + VWAP strategies active
    MIDDAY        = "midday"          # 11:30–14:00: VWAP strategies only, lower vol
    POWER_HOUR    = "power_hour"      # 14:00–15:45: All strategies, second ORB window
    CLOSING       = "closing"         # Last 15 min: flatten all, no new signals
    AFTER_HOURS   = "after_hours"     # Market closed: log results, reset state
    MIDDAY_BREAK  = "midday_break"    # HK/SG/JP only: break between sessions
    POST_BREAK    = "post_break"      # First 15 min after midday break: second ORB
```

#### Phase rules matrix

| Phase | Strategies allowed | Alert threshold | Scanner active | Special behavior |
|-------|-------------------|----------------|----------------|-----------------|
| `PRE_MARKET` | None | — | Collecting data only | Record pre-market high/low, overnight levels |
| `OPENING_RANGE` | None | — | Building ORB range | Record ORB high/low for first 15 min |
| `MORNING` | ORB, VWAP Pullback, VWAP Breakout | 80 (standard) | Full | Best period for ORB breakouts |
| `MIDDAY` | VWAP Pullback, VWAP Breakout | 85 (raised) | Normal tier only | Lower volume, raise threshold to reduce noise |
| `POWER_HOUR` | ORB (mini), VWAP Pullback, VWAP Breakout | 80 (standard) | Full + fast mode | Use 15:00–15:15 as mini-ORB window |
| `CLOSING` | None | — | Monitoring only | Expire all pending signals, no new alerts |
| `AFTER_HOURS` | None | — | Off | Persist final state, log daily summary |
| `MIDDAY_BREAK` | None | — | Paused | HK/SG/JP: scanner pauses during break |
| `POST_BREAK` | ORB (afternoon), VWAP | 80 | Full | Treat first 15 min post-break as new ORB window |

#### Phase detection

```python
from datetime import datetime, time as dtime
from zoneinfo import ZoneInfo

def get_session_phase(market_config) -> SessionPhase:
    """
    Determine current session phase based on market timezone and hours.
    """
    tz = ZoneInfo(market_config["timezone"])
    now = datetime.now(tz)
    current_time = now.time()

    market_open = dtime.fromisoformat(market_config["market_open"])
    market_close = dtime.fromisoformat(market_config["market_close"])

    # Pre-market: 30 min before open
    pre_market_start = _subtract_minutes(market_open, 30)
    orb_end = _add_minutes(market_open, 15)
    morning_end = dtime(11, 30)
    power_hour_start = dtime(14, 0)
    closing_start = _subtract_minutes(market_close, 15)

    # Handle midday break markets
    midday_break = market_config.get("midday_break")
    if midday_break:
        break_start = dtime.fromisoformat(midday_break[0])
        break_end = dtime.fromisoformat(midday_break[1])
        post_break_end = _add_minutes(break_end, 15)

        if break_start <= current_time < break_end:
            return SessionPhase.MIDDAY_BREAK
        if break_end <= current_time < post_break_end:
            return SessionPhase.POST_BREAK

    if current_time < pre_market_start:
        return SessionPhase.AFTER_HOURS
    if pre_market_start <= current_time < market_open:
        return SessionPhase.PRE_MARKET
    if market_open <= current_time < orb_end:
        return SessionPhase.OPENING_RANGE
    if orb_end <= current_time < morning_end:
        return SessionPhase.MORNING
    if morning_end <= current_time < power_hour_start:
        return SessionPhase.MIDDAY
    if power_hour_start <= current_time < closing_start:
        return SessionPhase.POWER_HOUR
    if closing_start <= current_time < market_close:
        return SessionPhase.CLOSING

    return SessionPhase.AFTER_HOURS
```

#### Pre-market data collection

During `PRE_MARKET` phase, the system collects key levels without generating alerts:

```python
@dataclass
class PreMarketLevels:
    pre_market_high: float = 0.0
    pre_market_low: float = float('inf')
    previous_day_high: float = 0.0
    previous_day_low: float = 0.0
    previous_day_close: float = 0.0
    overnight_high: float = 0.0
    overnight_low: float = float('inf')

    def update(self, price):
        self.pre_market_high = max(self.pre_market_high, price)
        self.pre_market_low = min(self.pre_market_low, price)
```

These levels are passed to the strategy engine as additional context for entry/exit decisions (e.g., an ORB break that also clears the pre-market high is a stronger signal).

### 10.13 Risk manager (`risk_manager.py`)

Enforces portfolio-level risk controls. Even though the system doesn't auto-execute trades, it controls whether alerts are sent based on risk exposure.

#### Risk rules

```python
@dataclass
class RiskManager:
    config: dict

    def check_risk(self, state: StateManager, trade_plan: dict) -> tuple:
        """
        Returns (allowed: bool, reason: str).
        Checks all risk rules before allowing an alert.
        """
        risk_config = self.config.get("risk", {})

        # 1. Max risk per trade
        max_risk = risk_config.get("max_risk_per_trade_usd", 100)
        suggested_shares = trade_plan.get("suggested_shares", 0)
        trade_risk = trade_plan["risk_per_share"] * suggested_shares
        if trade_risk > max_risk:
            # Adjust position size down, don't reject
            trade_plan["suggested_shares"] = int(max_risk / trade_plan["risk_per_share"])

        # 2. Max daily loss
        max_daily_loss = risk_config.get("max_daily_loss_usd", 500)
        if state.daily_pnl_usd <= -max_daily_loss:
            return False, f"Daily loss limit ${max_daily_loss} reached (current: ${state.daily_pnl_usd:.2f})"

        # 3. Max concurrent signals
        active_count = len([
            s for s in state.active_signals.values()
            if s.status in ("PENDING", "ACTIVE", "HIT_T1")
        ])
        max_concurrent = risk_config.get("max_concurrent_signals", 5)
        if active_count >= max_concurrent:
            return False, f"Max concurrent signals ({max_concurrent}) reached"

        # 4. Max alerts per day
        max_alerts = risk_config.get("max_alerts_per_day", 20)
        if state.alerts_sent_today >= max_alerts:
            return False, f"Max daily alerts ({max_alerts}) reached"

        # 5. Max exposure per sector (future enhancement, placeholder)
        # sector = get_sector(trade_plan["symbol"])
        # ...

        # 6. Minimum reward/risk ratio
        min_rr = risk_config.get("min_reward_risk_ratio", 1.0)
        if trade_plan.get("reward_risk_ratio", 0) < min_rr:
            return False, f"R:R ratio {trade_plan['reward_risk_ratio']} below minimum {min_rr}"

        return True, "OK"

    def calculate_position_size(self, trade_plan: dict) -> int:
        """
        Calculate shares based on max risk per trade.
        """
        max_risk = self.config.get("risk", {}).get("max_risk_per_trade_usd", 100)
        risk_per_share = trade_plan.get("risk_per_share", 0)
        if risk_per_share <= 0:
            return 0
        return int(max_risk / risk_per_share)
```

#### Risk config

```json
{
  "risk": {
    "max_risk_per_trade_usd": 100,
    "max_daily_loss_usd": 500,
    "max_concurrent_signals": 5,
    "max_alerts_per_day": 20,
    "min_reward_risk_ratio": 1.0
  }
}
```

#### Alert ranking / prioritization

When multiple stocks trigger simultaneously, the system ranks by composite score and only alerts on the top N:

```python
def prioritize_alerts(candidates, max_alerts=3):
    """
    From a list of scored candidates, return only the top N.
    Rest are logged to CSV but don't fire notifications.
    """
    ranked = sorted(candidates, key=lambda c: c["composite_score"], reverse=True)
    notify = ranked[:max_alerts]
    log_only = ranked[max_alerts:]

    return notify, log_only
```

### 10.14 Backtesting module (`backtest.py`)

A lightweight log replay system that validates scoring weights and strategy performance against historical data. Designed to be upgraded to a full replay engine in v2.

#### v1.0 scope: log replay

Reads `results/trades.json` (populated by the live scanner) and `results/scan_*.csv` files, then calculates:
- Win rate per strategy (ORB, VWAP Pullback, VWAP Breakout)
- Average R:R achieved vs planned
- Average time to target / stop
- Score threshold analysis (what score cutoff maximizes profit factor?)
- Best/worst performing stocks and time-of-day patterns

```python
import json
import csv
from pathlib import Path
from datetime import datetime
from collections import defaultdict

class BacktestReplay:
    """
    Lightweight backtester that replays trade signal logs.
    
    v1.0: Reads completed signals from trades.json, computes stats.
    v2.0 upgrade path: Accept historical intraday data, replay through
          the full scoring + strategy engine to simulate new parameters.
    """

    def __init__(self, results_dir="results"):
        self.results_dir = Path(results_dir)
        self.trades = []
        self.scan_data = []

    def load_trades(self):
        """Load all completed trade signals."""
        trades_file = self.results_dir / "trades.json"
        if trades_file.exists():
            with open(trades_file, "r") as f:
                self.trades = json.load(f)
        return len(self.trades)

    def load_scan_history(self, days=30):
        """Load CSV scan logs for the last N days."""
        csv_files = sorted(self.results_dir.glob("scan_*.csv"))[-days:]
        for csv_file in csv_files:
            with open(csv_file, "r") as f:
                reader = csv.DictReader(f)
                for row in reader:
                    self.scan_data.append(row)
        return len(self.scan_data)

    def strategy_performance(self):
        """
        Compute per-strategy stats:
        - Win rate
        - Avg profit %
        - Avg loss %
        - Profit factor
        - Expectancy
        """
        by_strategy = defaultdict(list)
        for trade in self.trades:
            if trade.get("status") in ("STOPPED", "HIT_T1", "HIT_T2", "EXPIRED"):
                by_strategy[trade["strategy"]].append(trade)

        results = {}
        for strategy, trades in by_strategy.items():
            wins = [t for t in trades if t.get("result_pct", 0) > 0]
            losses = [t for t in trades if t.get("result_pct", 0) <= 0]

            win_rate = len(wins) / len(trades) * 100 if trades else 0
            avg_win = sum(t["result_pct"] for t in wins) / len(wins) if wins else 0
            avg_loss = sum(t["result_pct"] for t in losses) / len(losses) if losses else 0

            gross_profit = sum(t["result_pct"] for t in wins)
            gross_loss = abs(sum(t["result_pct"] for t in losses))
            profit_factor = gross_profit / gross_loss if gross_loss > 0 else float('inf')

            expectancy = (win_rate / 100 * avg_win) + ((1 - win_rate / 100) * avg_loss)

            results[strategy] = {
                "total_trades": len(trades),
                "wins": len(wins),
                "losses": len(losses),
                "win_rate_pct": round(win_rate, 1),
                "avg_win_pct": round(avg_win, 2),
                "avg_loss_pct": round(avg_loss, 2),
                "profit_factor": round(profit_factor, 2),
                "expectancy_pct": round(expectancy, 2),
            }

        return results

    def score_threshold_analysis(self):
        """
        Test different score thresholds to find optimal cutoff.
        Answers: 'What if I only took signals with score > X?'
        """
        thresholds = [70, 75, 80, 85, 90, 95]
        results = {}

        for threshold in thresholds:
            filtered = [t for t in self.trades if t.get("composite_score", 0) >= threshold]
            if not filtered:
                results[threshold] = {"trades": 0}
                continue

            wins = [t for t in filtered if t.get("result_pct", 0) > 0]
            win_rate = len(wins) / len(filtered) * 100
            avg_result = sum(t.get("result_pct", 0) for t in filtered) / len(filtered)

            results[threshold] = {
                "trades": len(filtered),
                "win_rate_pct": round(win_rate, 1),
                "avg_result_pct": round(avg_result, 2),
            }

        return results

    def time_of_day_analysis(self):
        """
        Break down performance by hour to find best trading windows.
        """
        by_hour = defaultdict(list)
        for trade in self.trades:
            if trade.get("triggered_at"):
                hour = datetime.fromisoformat(trade["triggered_at"]).hour
                by_hour[hour].append(trade)

        results = {}
        for hour, trades in sorted(by_hour.items()):
            wins = [t for t in trades if t.get("result_pct", 0) > 0]
            win_rate = len(wins) / len(trades) * 100 if trades else 0
            results[f"{hour:02d}:00"] = {
                "trades": len(trades),
                "win_rate_pct": round(win_rate, 1),
            }

        return results

    def generate_report(self):
        """
        Generate a full backtest report.
        Output: dict suitable for console display or JSON export.
        """
        self.load_trades()
        self.load_scan_history()

        return {
            "summary": {
                "total_signals": len(self.trades),
                "total_scan_rows": len(self.scan_data),
                "date_range": self._get_date_range(),
            },
            "strategy_performance": self.strategy_performance(),
            "score_threshold_analysis": self.score_threshold_analysis(),
            "time_of_day_analysis": self.time_of_day_analysis(),
        }

    def _get_date_range(self):
        if not self.trades:
            return "No data"
        dates = [t.get("triggered_at", "")[:10] for t in self.trades if t.get("triggered_at")]
        if dates:
            return f"{min(dates)} to {max(dates)}"
        return "No data"


# --- v2.0 UPGRADE PATH ---
# The following class is a placeholder that documents the interface
# for the full replay engine. v1.0 ships with BacktestReplay only.

class FullReplayEngine:
    """
    v2.0: Full historical replay through scoring + strategy engine.

    Requires:
    - Historical intraday candles (1-min or 5-min) from data provider
    - Ability to feed candles through scoring.py and strategy.py
    - Simulates the scheduler tick-by-tick

    Interface (to be implemented in v2.0):
    """

    def replay_day(self, date, intraday_data, config):
        """
        Replay a single trading day:
        1. Feed candles sequentially through scoring engine
        2. Run strategy engine on each tick
        3. Track simulated entries/exits
        4. Return trade log as if the scanner ran live that day
        """
        raise NotImplementedError("v2.0 — requires historical intraday data source")

    def replay_range(self, start_date, end_date, config):
        """Replay multiple days and aggregate results."""
        raise NotImplementedError("v2.0")

    def optimize_parameters(self, param_grid, date_range):
        """
        Grid search over scoring weights and strategy thresholds.
        WARNING: Must use walk-forward validation to avoid overfitting.
        Split: train on 60% of days, validate on 40%.
        """
        raise NotImplementedError("v2.0")
```

#### Running the backtest

```bash
# After the scanner has been running for a few days:
python backtest.py

# Output:
# ═══════════════════════════════════════
#  BACKTEST REPORT — 2026-03-25 to 2026-03-31
# ═══════════════════════════════════════
#
#  STRATEGY PERFORMANCE
#  ────────────────────
#  ORB Breakout:     42 trades | 62% win rate | PF 1.85 | Expectancy +0.8%
#  VWAP Pullback:    28 trades | 57% win rate | PF 1.42 | Expectancy +0.5%
#  VWAP Breakout:    35 trades | 51% win rate | PF 1.21 | Expectancy +0.3%
#
#  SCORE THRESHOLD ANALYSIS
#  ────────────────────────
#  Score > 70:  105 trades | 52% win rate | Avg +0.3%
#  Score > 80:   68 trades | 58% win rate | Avg +0.6%  ← current setting
#  Score > 90:   22 trades | 68% win rate | Avg +1.1%
#
#  BEST TRADING HOURS
#  ──────────────────
#  09:00  12 trades | 67% win rate  ← morning is strongest
#  10:00  18 trades | 61% win rate
#  14:00  15 trades | 60% win rate  ← power hour
```

### 10.15 Dynamic scoring weights (`scoring.py` enhancement)

Static scoring weights assume every hour of the trading day behaves the same — they don't. Volume patterns, volatility profiles, and momentum characteristics shift dramatically across the session. Dynamic weights adjust automatically based on the current session phase.

#### Weight profiles per phase

```python
# In scoring.py — weight profiles keyed by SessionPhase

DYNAMIC_WEIGHTS = {
    "PRE_MARKET": {
        # Pre-market: volume is the only reliable signal (thin liquidity)
        "rvol": 0.50,
        "volatility": 0.15,
        "momentum": 0.15,
        "volume": 0.20,
    },
    "OPENING_RANGE": {
        # ORB forming: momentum dominant, everything is moving
        "rvol": 0.20,
        "volatility": 0.20,
        "momentum": 0.40,
        "volume": 0.20,
    },
    "MORNING": {
        # Best trading window: balanced, slight RVOL emphasis
        "rvol": 0.30,
        "volatility": 0.25,
        "momentum": 0.25,
        "volume": 0.20,
    },
    "MIDDAY": {
        # Volume dries up: RVOL is king — anything with unusual volume matters
        "rvol": 0.45,
        "volatility": 0.15,
        "momentum": 0.20,
        "volume": 0.20,
    },
    "POWER_HOUR": {
        # Afternoon momentum: volatility + momentum spike
        "rvol": 0.20,
        "volatility": 0.30,
        "momentum": 0.30,
        "volume": 0.20,
    },
    "CLOSING": {
        # Last 15 min: no new trades, but track for next-day watchlist
        "rvol": 0.30,
        "volatility": 0.25,
        "momentum": 0.25,
        "volume": 0.20,
    },
    "MIDDAY_BREAK": {
        # HK/SG/JP break: scanner paused, use default
        "rvol": 0.30,
        "volatility": 0.25,
        "momentum": 0.25,
        "volume": 0.20,
    },
    "POST_BREAK": {
        # After midday break: similar to OPENING_RANGE — second ORB window
        "rvol": 0.25,
        "volatility": 0.20,
        "momentum": 0.35,
        "volume": 0.20,
    },
}
```

#### Integration with scoring engine

```python
def get_weights(session_phase, config):
    """
    Return scoring weights for the current session phase.
    Falls back to config-defined static weights if dynamic is disabled.
    """
    if config["scanner"].get("dynamic_weights_enabled", True):
        return DYNAMIC_WEIGHTS.get(session_phase.value, DYNAMIC_WEIGHTS["MORNING"])
    else:
        return config["scanner"]["score_weights"]

def compute_composite_score(metrics, session_phase, config):
    """
    Compute composite score using phase-appropriate weights.
    """
    weights = get_weights(session_phase, config)

    return (
        metrics["rvol_score"]       * weights["rvol"]
      + metrics["volatility_score"] * weights["volatility"]
      + metrics["momentum_score"]   * weights["momentum"]
      + metrics["volume_score"]     * weights["volume"]
    )
```

#### Config

```json
{
  "scanner": {
    "dynamic_weights_enabled": true,
    "score_weights": {
      "rvol": 0.30,
      "volatility": 0.25,
      "momentum": 0.25,
      "volume": 0.20
    },
    "dynamic_weight_overrides": {}
  }
}
```

> `score_weights` serves as the fallback when `dynamic_weights_enabled` is `false`. `dynamic_weight_overrides` allows the user to override specific phases without editing code (e.g., `{"MIDDAY": {"rvol": 0.50, ...}}`).

### 10.16 Market regime detection (`regime.py`)

Not every day is the same. Trending days reward breakout strategies; choppy days punish them. A simple regime detector adjusts strategy eligibility and alert thresholds in real-time.

#### Regime types

```python
from enum import Enum

class MarketRegime(Enum):
    TRENDING    = "trending"     # Strong directional day — favor ORB, breakouts
    CHOPPY      = "choppy"       # Range-bound, mean-reverting — favor VWAP pullback
    VOLATILE    = "volatile"     # High ATR but no clear direction — widen stops, tighten alerts
    LOW_VOL     = "low_vol"      # Dead market — raise thresholds, reduce activity
```

#### Detection heuristic

Uses a broad market benchmark (SPY or QQQ for US, HSI for HK, STI for SG, etc.) to classify the day's regime. One extra Finnhub API call.

```python
def detect_regime(benchmark_data, market_config):
    """
    Classify today's market regime from benchmark intraday data.
    
    Inputs:
        benchmark_data: dict with keys:
            - "day_high", "day_low", "price", "open"
            - "atr_14": 14-day ATR from daily bars
            - "intraday_bars": list of 5-min candles so far today
    
    Returns: MarketRegime
    """
    price = benchmark_data["price"]
    day_high = benchmark_data["day_high"]
    day_low = benchmark_data["day_low"]
    open_price = benchmark_data["open"]
    atr = benchmark_data["atr_14"]

    # Today's range as multiple of ATR
    todays_range = day_high - day_low
    range_ratio = todays_range / atr if atr > 0 else 1.0

    # Directional strength: how far from open vs range
    if todays_range > 0:
        direction_strength = abs(price - open_price) / todays_range
    else:
        direction_strength = 0

    # Count direction changes in intraday bars (choppiness indicator)
    bars = benchmark_data.get("intraday_bars", [])
    direction_changes = 0
    for i in range(1, len(bars)):
        prev_dir = bars[i-1]["close"] - bars[i-1]["open"]
        curr_dir = bars[i]["close"] - bars[i]["open"]
        if (prev_dir > 0 and curr_dir < 0) or (prev_dir < 0 and curr_dir > 0):
            direction_changes += 1

    choppiness = direction_changes / len(bars) if bars else 0

    # Classification logic
    if range_ratio < 0.5:
        return MarketRegime.LOW_VOL

    if range_ratio > 1.5 and direction_strength > 0.6:
        return MarketRegime.TRENDING

    if range_ratio > 1.5 and direction_strength < 0.4:
        return MarketRegime.VOLATILE

    if choppiness > 0.6:
        return MarketRegime.CHOPPY

    if direction_strength > 0.5:
        return MarketRegime.TRENDING

    return MarketRegime.CHOPPY
```

#### Regime adjustments

| Parameter | TRENDING | CHOPPY | VOLATILE | LOW_VOL |
|-----------|----------|--------|----------|---------|
| Alert threshold | 75 (lowered) | 85 (raised) | 80 (standard) | 90 (high) |
| Preferred strategies | ORB, VWAP Breakout | VWAP Pullback only | VWAP Pullback, Breakout | VWAP Pullback only |
| ORB allowed | Yes | No (high failure rate) | Yes (with wider stops) | No |
| Stop loss adjustment | Standard | Tighter (0.7×) | Wider (1.3×) | Standard |
| Fast mode | Enabled | Disabled (saves API calls) | Enabled | Disabled |
| Score weight boost | Momentum +0.05 | RVOL +0.05 | Volatility +0.05 | RVOL +0.10 |

```python
def apply_regime_adjustments(config, regime):
    """
    Return a modified config dict with regime-specific overrides.
    Does not mutate the original config.
    """
    import copy
    adjusted = copy.deepcopy(config)

    ADJUSTMENTS = {
        MarketRegime.TRENDING: {
            "alert_threshold": 75,
            "strategies_allowed": ["ORB", "VWAP_BREAKOUT", "VWAP_PULLBACK"],
            "stop_multiplier": 1.0,
            "fast_mode_enabled": True,
            "weight_boost": {"momentum": 0.05},
        },
        MarketRegime.CHOPPY: {
            "alert_threshold": 85,
            "strategies_allowed": ["VWAP_PULLBACK"],
            "stop_multiplier": 0.7,
            "fast_mode_enabled": False,
            "weight_boost": {"rvol": 0.05},
        },
        MarketRegime.VOLATILE: {
            "alert_threshold": 80,
            "strategies_allowed": ["VWAP_PULLBACK", "VWAP_BREAKOUT"],
            "stop_multiplier": 1.3,
            "fast_mode_enabled": True,
            "weight_boost": {"volatility": 0.05},
        },
        MarketRegime.LOW_VOL: {
            "alert_threshold": 90,
            "strategies_allowed": ["VWAP_PULLBACK"],
            "stop_multiplier": 1.0,
            "fast_mode_enabled": False,
            "weight_boost": {"rvol": 0.10},
        },
    }

    adj = ADJUSTMENTS.get(regime, {})
    adjusted["scanner"]["alert_threshold"] = adj.get("alert_threshold", 80)
    adjusted["_regime"] = regime.value
    adjusted["_strategies_allowed"] = adj.get("strategies_allowed", [])
    adjusted["_stop_multiplier"] = adj.get("stop_multiplier", 1.0)
    adjusted["scanner"]["fast_mode"]["enabled"] = adj.get("fast_mode_enabled", True)

    return adjusted
```

#### Benchmark symbols per market

```json
{
  "regime": {
    "enabled": true,
    "benchmark": {
      "US": "SPY",
      "HK": "2800.HK",
      "SG": "ES3.SI",
      "JP": "1321.T",
      "TW": "0050.TW",
      "UK": "ISF.L",
      "EU": "EXSA.DE"
    },
    "refresh_interval_minutes": 15
  }
}
```

> Regime is recalculated every 15 minutes using the benchmark's intraday data. One Finnhub call per refresh. Budget: 4 calls/hour × 1 symbol = negligible.

### 10.17 News and catalyst integration (`catalyst.py`)

Stocks with catalysts (earnings, FDA news, analyst upgrades) move more predictably and further. The catalyst module checks for recent news and upcoming events, then applies a score boost.

#### Data sources (all within Finnhub free tier)

| Endpoint | Data | Free tier | Usage |
|----------|------|-----------|-------|
| `/company-news` | Recent news articles | 60/min shared | Check for news in last 24h |
| `/calendar/earnings` | Upcoming earnings dates | 60/min shared | Flag stocks reporting today/tomorrow |
| `/press-releases` | Company press releases | 60/min shared | Detect material announcements |

#### Catalyst types and score boosts

```python
from enum import Enum
from datetime import datetime, timedelta

class CatalystType(Enum):
    EARNINGS_TODAY     = "earnings_today"      # Reporting today (pre/post market)
    EARNINGS_TOMORROW  = "earnings_tomorrow"   # Reporting tomorrow
    NEWS_POSITIVE      = "news_positive"       # Positive news in last 24h
    NEWS_NEGATIVE      = "news_negative"       # Negative news in last 24h
    NEWS_NEUTRAL       = "news_neutral"        # News exists but neutral
    FDA_EVENT          = "fda_event"           # FDA approval/rejection (biotech)
    ANALYST_UPGRADE    = "analyst_upgrade"     # Upgrade/target raise
    ANALYST_DOWNGRADE  = "analyst_downgrade"   # Downgrade/target cut
    NO_CATALYST        = "no_catalyst"         # Nothing found

CATALYST_SCORE_BOOST = {
    CatalystType.EARNINGS_TODAY:     15,   # Major — high vol expected
    CatalystType.EARNINGS_TOMORROW:  8,    # Anticipation builds
    CatalystType.NEWS_POSITIVE:      10,   # Directional edge
    CatalystType.NEWS_NEGATIVE:      10,   # Directional edge (for shorts)
    CatalystType.NEWS_NEUTRAL:       3,    # At least it's in play
    CatalystType.FDA_EVENT:          15,   # Biotech — extreme moves
    CatalystType.ANALYST_UPGRADE:    8,
    CatalystType.ANALYST_DOWNGRADE:  8,
    CatalystType.NO_CATALYST:        0,
}
```

#### Catalyst detection

```python
def detect_catalysts(symbol, finnhub_client):
    """
    Check for catalysts on a given stock.
    Returns list of CatalystType + details.
    
    API budget: 2 calls per stock (news + earnings calendar).
    Call once per stock at the pre-market scan, cache for the day.
    """
    catalysts = []
    now = datetime.now()
    yesterday = (now - timedelta(days=1)).strftime("%Y-%m-%d")
    today = now.strftime("%Y-%m-%d")
    tomorrow = (now + timedelta(days=1)).strftime("%Y-%m-%d")

    # 1. Check recent news (last 24 hours)
    try:
        news = finnhub_client.company_news(symbol, _from=yesterday, to=today)
        if news:
            catalysts.append({
                "type": CatalystType.NEWS_NEUTRAL,
                "count": len(news),
                "headline": news[0].get("headline", ""),
                "source": news[0].get("source", ""),
            })

            # Simple keyword-based sentiment (v1.0 — upgrade to NLP in v2)
            positive_keywords = ["beat", "surge", "rally", "upgrade", "approval",
                                 "record", "exceed", "strong", "raise", "buy"]
            negative_keywords = ["miss", "plunge", "downgrade", "cut", "warning",
                                 "weak", "decline", "sell", "loss", "reject"]

            headline_lower = news[0].get("headline", "").lower()
            pos_hits = sum(1 for kw in positive_keywords if kw in headline_lower)
            neg_hits = sum(1 for kw in negative_keywords if kw in headline_lower)

            if pos_hits > neg_hits:
                catalysts[-1]["type"] = CatalystType.NEWS_POSITIVE
            elif neg_hits > pos_hits:
                catalysts[-1]["type"] = CatalystType.NEWS_NEGATIVE

            # Check for specific catalyst types
            fda_keywords = ["fda", "approval", "clinical trial", "phase 3", "nda"]
            if any(kw in headline_lower for kw in fda_keywords):
                catalysts.append({"type": CatalystType.FDA_EVENT, "headline": headline_lower})

            analyst_keywords = ["upgrade", "downgrade", "price target", "initiate"]
            if any(kw in headline_lower for kw in analyst_keywords):
                if "upgrade" in headline_lower or "raise" in headline_lower:
                    catalysts.append({"type": CatalystType.ANALYST_UPGRADE})
                elif "downgrade" in headline_lower or "cut" in headline_lower:
                    catalysts.append({"type": CatalystType.ANALYST_DOWNGRADE})

    except Exception:
        pass  # News check is best-effort, don't block on failure

    # 2. Check earnings calendar
    try:
        earnings = finnhub_client.earnings_calendar(
            _from=today, to=tomorrow, symbol=symbol
        )
        if earnings and earnings.get("earningsCalendar"):
            for event in earnings["earningsCalendar"]:
                event_date = event.get("date", "")
                if event_date == today:
                    catalysts.append({
                        "type": CatalystType.EARNINGS_TODAY,
                        "time": event.get("hour", "unknown"),  # "bmo" or "amc"
                        "eps_estimate": event.get("epsEstimate"),
                    })
                elif event_date == tomorrow:
                    catalysts.append({
                        "type": CatalystType.EARNINGS_TOMORROW,
                        "eps_estimate": event.get("epsEstimate"),
                    })
    except Exception:
        pass

    if not catalysts:
        catalysts.append({"type": CatalystType.NO_CATALYST})

    return catalysts


def apply_catalyst_boost(composite_score, catalysts):
    """
    Apply score boost from detected catalysts.
    Multiple catalysts stack, but cap total boost at 25 points.
    """
    total_boost = 0
    for catalyst in catalysts:
        boost = CATALYST_SCORE_BOOST.get(catalyst["type"], 0)
        total_boost += boost

    total_boost = min(total_boost, 25)  # Cap at 25
    boosted_score = min(composite_score + total_boost, 100)  # Cap at 100

    return boosted_score, total_boost
```

#### API budget for catalysts

```
Pre-market scan: 2 calls per stock × ~12 stocks = ~24 Finnhub calls
This is a one-time cost at 09:15 — results are cached for the day.
At 60 calls/min, this takes ~25 seconds. No budget conflict with hot list monitor.
```

#### Updated alert format with catalyst info

```
🔥 DAY TRADE ALERT — Score: 92/100 (+10 catalyst boost)

NVDA  $142.35  ▲ +4.2%
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📰 CATALYST: Earnings today (AMC) | EPS est: $0.82
   + 2 news articles in last 24h
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
RVOL:        3.2x (score: 100)
Volatility:  5.1% (score: 100)
Momentum:    4.2% (score: 63)
Volume:      45.2M (score: 88)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📐 TRADE PLAN (ORB Breakout)
  ...
```

#### Config

```json
{
  "catalyst": {
    "enabled": true,
    "max_score_boost": 25,
    "check_at_premarket": true,
    "earnings_boost": 15,
    "news_boost": 10,
    "keywords_positive": ["beat", "surge", "rally", "upgrade", "approval"],
    "keywords_negative": ["miss", "plunge", "downgrade", "cut", "warning"]
  }
}
```

### 10.18 Liquidity filter (`liquidity.py`)

Low-float stocks and wide-spread names are traps for day traders. A stock might score high on RVOL and momentum, but if you can't get in and out cleanly, the score is meaningless. The liquidity filter runs as a pre-screen before scoring.

#### What it filters

| Risk | How we detect it | Action |
|------|-----------------|--------|
| **Low float** | Shares outstanding < threshold (via Finnhub profile) | Exclude from watchlist or flag as high-risk |
| **Wide spread** | Estimated spread > 0.5% of price | Exclude or adjust target to account for slippage |
| **Thin order book** | Average volume < market minimum AND low RVOL | Exclude — no liquidity to trade |
| **Penny stocks** | Price < $1.00 | Exclude — prone to manipulation |
| **Recently halted** | Trading halt in last session | Flag as caution — may gap violently |

#### Implementation

```python
from dataclasses import dataclass
from typing import Optional

@dataclass
class LiquidityProfile:
    symbol: str
    shares_outstanding: int          # Total shares (proxy for float)
    avg_daily_volume_20d: int        # 20-day average volume
    avg_dollar_volume_20d: float     # Volume × price (liquidity depth)
    estimated_spread_pct: float      # Estimated bid-ask spread as % of price
    price: float
    market_cap: float
    is_low_float: bool
    is_wide_spread: bool
    is_penny_stock: bool
    liquidity_grade: str             # "A" (excellent) to "F" (avoid)
    pass_filter: bool                # True = tradeable, False = skip


def build_liquidity_profile(symbol, quote, profile_data, daily_bars):
    """
    Build a liquidity profile for a stock.
    
    Inputs:
        quote: current quote from Finnhub
        profile_data: from Finnhub /stock/profile2 (cached daily)
        daily_bars: last 20 daily bars (already cached for ATR calc)
    """
    price = quote.get("c", 0)  # current price
    shares = profile_data.get("shareOutstanding", 0) * 1_000_000  # millions
    market_cap = profile_data.get("marketCapitalization", 0) * 1_000_000

    # 20-day average volume
    volumes = [bar.get("v", 0) for bar in daily_bars[-20:]]
    avg_volume = sum(volumes) / len(volumes) if volumes else 0
    avg_dollar_volume = avg_volume * price

    # Estimate spread from daily bars (heuristic)
    if daily_bars:
        avg_range_pct = sum(
            (bar["h"] - bar["l"]) / bar["c"] * 100
            for bar in daily_bars[-5:]
        ) / min(5, len(daily_bars))
        estimated_spread_pct = avg_range_pct / 10
    else:
        estimated_spread_pct = 1.0

    # Classification
    is_penny = price < 1.0
    is_low_float = shares < 10_000_000
    is_wide_spread = estimated_spread_pct > 0.5

    # Liquidity grade
    if avg_dollar_volume > 100_000_000:
        grade = "A"
    elif avg_dollar_volume > 20_000_000:
        grade = "B"
    elif avg_dollar_volume > 5_000_000:
        grade = "C"
    elif avg_dollar_volume > 1_000_000:
        grade = "D"
    else:
        grade = "F"

    pass_filter = (
        not is_penny
        and not is_wide_spread
        and grade in ("A", "B", "C")
        and avg_volume > 0
    )

    return LiquidityProfile(
        symbol=symbol,
        shares_outstanding=int(shares),
        avg_daily_volume_20d=int(avg_volume),
        avg_dollar_volume_20d=round(avg_dollar_volume, 2),
        estimated_spread_pct=round(estimated_spread_pct, 3),
        price=price,
        market_cap=market_cap,
        is_low_float=is_low_float,
        is_wide_spread=is_wide_spread,
        is_penny_stock=is_penny,
        liquidity_grade=grade,
        pass_filter=pass_filter,
    )


def filter_watchlist(watchlist, liquidity_profiles, config):
    """
    Pre-filter watchlist before scoring.
    Returns: (tradeable_symbols, excluded_symbols_with_reasons)
    """
    liq_config = config.get("liquidity", {})
    min_dollar_volume = liq_config.get("min_avg_dollar_volume", 5_000_000)
    max_spread_pct = liq_config.get("max_spread_pct", 0.5)
    allow_low_float = liq_config.get("allow_low_float", False)
    min_price = liq_config.get("min_price", 1.0)

    tradeable = []
    excluded = []

    for symbol in watchlist:
        profile = liquidity_profiles.get(symbol)
        if not profile:
            tradeable.append(symbol)
            continue

        reasons = []
        if profile.is_penny_stock or profile.price < min_price:
            reasons.append(f"Price ${profile.price:.2f} below min ${min_price}")
        if profile.is_wide_spread or profile.estimated_spread_pct > max_spread_pct:
            reasons.append(f"Spread {profile.estimated_spread_pct:.2f}% too wide")
        if profile.avg_dollar_volume_20d < min_dollar_volume:
            reasons.append(f"Dollar vol ${profile.avg_dollar_volume_20d:,.0f} below min")
        if profile.is_low_float and not allow_low_float:
            reasons.append(f"Low float: {profile.shares_outstanding:,.0f} shares")

        if reasons:
            excluded.append({"symbol": symbol, "reasons": reasons, "grade": profile.liquidity_grade})
        else:
            tradeable.append(symbol)

    return tradeable, excluded
```

#### Slippage adjustment for strategy targets

```python
def adjust_for_slippage(trade_plan, liquidity_profile):
    """
    Adjust trade plan entry/targets for estimated slippage.
    Grade A/B: no adjustment. Grade C: half spread. Grade D+: full spread.
    """
    spread_pct = liquidity_profile.estimated_spread_pct
    price = trade_plan["entry"]

    if liquidity_profile.liquidity_grade in ("A", "B"):
        slippage = 0
    elif liquidity_profile.liquidity_grade == "C":
        slippage = price * (spread_pct / 100) * 0.5
    else:
        slippage = price * (spread_pct / 100)

    if trade_plan["direction"] == "LONG":
        trade_plan["entry"] = round(trade_plan["entry"] + slippage, 2)
        trade_plan["target_1"] = round(trade_plan["target_1"] - slippage, 2)
        trade_plan["target_2"] = round(trade_plan["target_2"] - slippage, 2)
    else:
        trade_plan["entry"] = round(trade_plan["entry"] - slippage, 2)
        trade_plan["target_1"] = round(trade_plan["target_1"] + slippage, 2)
        trade_plan["target_2"] = round(trade_plan["target_2"] + slippage, 2)

    trade_plan["slippage_adj"] = round(slippage, 2)
    trade_plan["liquidity_grade"] = liquidity_profile.liquidity_grade
    return trade_plan
```

#### API budget for liquidity profiles

```
Finnhub /stock/profile2: 1 call per stock, cached daily.
Called once during pre-market scan alongside catalyst check.
~12 stocks × 1 call = 12 calls. Combined with catalyst calls:
Total pre-market: ~12 (profile) + ~24 (catalyst) = ~36 calls.
At 60 calls/min, completes in ~36 seconds. No conflict.
```

#### Config

```json
{
  "liquidity": {
    "enabled": true,
    "min_avg_dollar_volume": 5000000,
    "max_spread_pct": 0.5,
    "min_price": 1.0,
    "allow_low_float": false,
    "adjust_slippage": true
  }
}
```

---

## 11. Implementation notes

### 11.1 API call budget optimization

Alpha Vantage's 25 calls/day is tight. The strategy:

1. **First scan of the day (09:15 ET)**: Call `TIME_SERIES_DAILY` for each stock to get 100 days of history (for ATR + avg volume calculation). Cache this data in memory — it only needs refreshing once per day. Budget: ~12 calls for a 12-stock subset (rotate through watchlist across days if needed).
2. **All scans**: Call `GLOBAL_QUOTE` for each stock in the hot list candidate pool. Budget: ~8 calls per scan × 3 scans = ~24 calls.
3. **Fallback**: If daily budget is exhausted, rely solely on Finnhub for the rest of the day (quotes only, no historical depth).

### 11.2 Finnhub usage

Finnhub provides:
- `/quote` — real-time quote (price, high, low, open, previous close, volume)
- `/stock/candle` — historical candles (for VWAP calculation + regime detection)
- `/stock/profile2` — company profile (shares outstanding, market cap — for liquidity filter)
- `/company-news` — recent news articles (for catalyst detection)
- `/calendar/earnings` — earnings calendar (for catalyst detection)

The hot list monitor uses `/quote` — 1 call per stock per check. At 10 stocks every 2 minutes = 5 calls/min (or ~16 calls/min with fast mode), well within the 60/min limit.

Pre-market data collection: ~36 calls for profiles + catalysts + benchmark, completed in ~36 seconds.

### 11.3 Caching strategy

```
cache = {
    "daily_bars": {
        "NVDA": { "data": {...}, "fetched_at": "2026-03-31T09:15:00" },
        ...
    },
    "hot_list": ["NVDA", "TSLA", "AMD", ...],
    "last_scores": {
        "NVDA": { "score": 87, "timestamp": "2026-03-31T10:32:00" },
        ...
    },
    "alert_cooldowns": {
        "NVDA": "2026-03-31T10:32:00",
        ...
    }
}
```

Daily bars are cached for the entire trading day. Hot list is rebuilt on each full scan. Scores and cooldowns persist in memory and are saved to `results/alerts.json` for crash recovery.

### 11.4 Error handling

- API rate limit hit → back off exponentially (12s, 24s, 48s), log warning
- API returns empty/error → skip stock for this cycle, try next cycle
- Network failure → retry 3 times with backoff, then skip cycle
- All notifications fail → fall back to console output (always active)
- Console output is **always on** regardless of notification settings

### 11.5 Logging

Every scan cycle writes to `results/scan_YYYY-MM-DD.csv`:

```csv
timestamp,symbol,price,change_pct,volume,rvol,volatility_pct,momentum_pct,composite_score,session_phase,strategy,direction,entry_price,stop_loss,target_1,target_2,risk_reward,alert_sent,signal_status
2026-03-31T10:32:00,NVDA,142.35,4.2,45200000,3.2,5.1,4.2,87,MORNING,ORB,LONG,142.50,140.80,144.20,145.90,1.0,true,PENDING
2026-03-31T10:32:00,TSLA,178.90,2.8,32100000,2.1,3.8,2.8,72,MORNING,,,,,,,false,
```

---

## 12. Future enhancements (v2.0+)

These are documented for future development but **not included in v1.0**:

- **Full replay backtesting engine**: Feed historical intraday candles through the scoring + strategy engine to simulate trades (v1.0 ships the interface stub in `backtest.py`)
- **Parameter optimization**: Grid search over scoring weights and strategy thresholds with walk-forward validation (built on top of full replay engine)
- **News sentiment scoring**: Use Finnhub `/company-news` + Alpha Vantage News API to add a catalyst/sentiment factor to the scoring engine
- **Sector heatmap**: Aggregate scores by GICS sector to show which sectors are running hot
- **Web dashboard**: React or HTML dashboard that reads `results/` data and displays live charts + trade plans
- **Watchlist auto-discovery**: Scan top gainers/losers lists to automatically add new tickers
- **Multi-market simultaneous**: Run scanners for multiple markets concurrently using threading
- **WebSocket streaming**: Replace Finnhub REST polling with WebSocket for sub-second latency (upgrade from fast mode)
- **Discord/Slack notifications**: Additional notification channels
- **Broker API integration**: Connect to Alpaca or Interactive Brokers for semi-automated execution
- **Docker container**: Package everything for one-command deployment
- **Max exposure per sector**: Risk manager enhancement — limit concentration in one sector

---

## 13. How to run

```bash
# 1. Clone the repo
git clone <your-repo-url>
cd daytrader_scanner

# 2. Install dependencies
pip install requests plyer

# 3. Edit config.json with your API keys
#    - Add Alpha Vantage key
#    - Add Finnhub key
#    - Enable your preferred notification channel
#    - Review risk settings

# 4. Run the scanner
python scanner.py

# 5. (Optional) Run in background
nohup python scanner.py &

# 6. Check results
cat results/scan_2026-03-31.csv

# 7. Run backtest after a few days of data
python backtest.py

# 8. Check active state
cat results/state.json
```

---

## 14. Development sequence

When building from this spec, implement in this order:

1. **`config.json`** — create with full schema from section 9 (include strategy, risk, fast_mode, regime, catalyst, liquidity settings)
2. **`utils.py`** — logging, time helpers, rate limiter
3. **`data_client.py`** — Alpha Vantage + Finnhub wrappers with rate limiting and caching
4. **`liquidity.py`** — liquidity profiling, watchlist pre-filter, slippage adjustment
5. **`scoring.py`** — normalization functions + composite score calculator + dynamic weight profiles per session phase
6. **`session_phase.py`** — session phase detection + phase rules matrix + pre-market level collection
7. **`regime.py`** — market regime detection (trending/choppy/volatile/low_vol) + regime adjustment overrides
8. **`catalyst.py`** — news + earnings catalyst detection, keyword sentiment, score boost
9. **`strategy.py`** — ORB, VWAP Pullback, VWAP Breakout calculators + strategy selector + VWAP calculation
10. **`state_manager.py`** — state persistence: active signals, cooldowns, dedup, signal status tracking
11. **`risk_manager.py`** — daily loss limit, max concurrent signals, position sizing, alert prioritization
12. **`markets.py`** — load market configs, validate trading hours, determine active market
13. **`notifier.py`** — Gmail SMTP + Telegram + Desktop notification handlers (alert format with trade plan + catalyst info)
14. **`scheduler.py`** — tiered loop: full scan + hot list monitor + fast mode (15s top 3) + regime refresh
15. **`scanner.py`** — main entry point: data → liquidity filter → scoring (dynamic weights) → regime adjust → catalyst boost → strategy → state → risk → notify
16. **`backtest.py`** — log replay engine + strategy performance + score threshold analysis + v2 interface stub
17. **`README.md`** — beginner-friendly setup and usage guide
