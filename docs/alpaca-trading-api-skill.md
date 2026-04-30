# Alpaca Trading API Reference

**Purpose:** Comprehensive reference for implementing Alpaca broker integration in the LevelSweepAgent trading platform.

**Documentation Base:** https://docs.alpaca.markets

> **Heads-up on code samples**: code blocks in this document are in Rust (carried over from the user's prior trading project). They illustrate API shapes — request payloads, response fields, header conventions, error handling patterns. The actual LevelSweepAgent runtime is **Java 21 / Quarkus**; treat Rust samples as pseudo-code and translate to `alpaca-java` (or the project's hand-rolled HTTP/WS client per ADR-TBD). Field names and JSON shapes are accurate.

> **Quick skill reference**: see `.claude/skills/alpaca-trading-api/SKILL.md` for the auto-loading short version. This file is the deep-dive companion.

---

## Quick Reference

### API Endpoints
- **Paper Trading:** `https://paper-api.alpaca.markets`
- **Live Trading:** `https://api.alpaca.markets`
- **Market Data:** `https://data.alpaca.markets`

### Authentication
```rust
Headers:
- APCA-API-KEY-ID: {api_key}
- APCA-API-SECRET-KEY: {secret_key}
```

---

## 1. Options Trading

**Docs:** https://docs.alpaca.markets/docs/options-trading

### Key Concepts
- **Options contracts** identified by OCC symbols (e.g., `SPY250117C00600000`)
- **0DTE support** - Same-day expiration trading available
- **Contract specifications** - 100 shares per contract
- **Settlement** - Cash-settled for index options, physical for equity options

### Contract Symbol Format
```
SPY250117C00600000
│  │     │ │
│  │     │ └─ Strike price ($600.00)
│  │     └─── Type (C=Call, P=Put)
│  └───────── Expiration (Jan 17, 2025)
└──────────── Underlying symbol
```

### Available Options
- **Equity options** - Individual stocks
- **Index options** - SPX, NDX, RUT
- **ETF options** - SPY, QQQ, IWM (perfect for LevelSweepAgent strategy)


---

## 2. Options Orders

**Docs:** https://docs.alpaca.markets/docs/options-orders

### Order Types
- **Market** - Immediate execution at best available price
- **Limit** - Execute at specified price or better
- **Stop** - Trigger market order when stop price reached
- **Stop Limit** - Trigger limit order when stop price reached

### Order Sides
- **buy_to_open** - Open long position (buy calls/puts)
- **buy_to_close** - Close short position
- **sell_to_open** - Open short position (sell calls/puts)
- **sell_to_close** - Close long position

### Time in Force
- **day** - Valid until market close (default for 0DTE)
- **gtc** - Good till canceled
- **ioc** - Immediate or cancel
- **fok** - Fill or kill

### Example Order Request
```json
POST /v2/orders
{
  "symbol": "SPY250117C00600000",
  "qty": 10,
  "side": "buy_to_open",
  "type": "limit",
  "time_in_force": "day",
  "limit_price": "1.50",
  "order_class": "simple"
}
```

### Order Classes
- **simple** - Single order
- **bracket** - Entry + take profit + stop loss
- **oco** - One-cancels-other
- **oto** - One-triggers-other


---

## 3. Options Level 3 Trading

**Docs:** https://docs.alpaca.markets/docs/options-level-3-trading

### Approval Levels
- **Level 1** - Covered calls, cash-secured puts
- **Level 2** - Long calls/puts (what LevelSweepAgent uses)
- **Level 3** - Spreads, iron condors, butterflies

### Requirements for Level 2
- Account approval for options trading
- Sufficient buying power
- Understanding of options risks

### LevelSweepAgent Strategy Requirements
- **Level 2 approval** - For buying 0DTE calls/puts
- **No margin required** - Cash-secured positions
- **Risk-defined** - Maximum loss = premium paid


---

## 4. Non-Trade Activities for Option Events

**Docs:** https://docs.alpaca.markets/docs/non-trade-activities-for-option-events

### Corporate Actions
- **Assignments** - Short options exercised against you
- **Exercises** - You exercise long options
- **Expirations** - Options expire worthless or ITM

### Activity Types
- **OPTC** - Option contract activity
- **OPTN** - Option assignment/exercise
- **OPTEXP** - Option expiration

### Monitoring Events
```rust
// Check account activities for option events
GET /v2/account/activities?activity_types=OPTC,OPTN,OPTEXP
```

### 0DTE Considerations
- **Auto-exercise** - ITM options auto-exercise at expiration
- **Expiration time** - 4:00 PM ET for equity options
- **Settlement** - T+1 for equity options


---

## 5. Getting Started with Trading API

**Docs:** https://docs.alpaca.markets/docs/getting-started-with-trading-api

### Setup Steps
1. **Create account** at alpaca.markets
2. **Enable options trading** in account settings
3. **Generate API keys** (paper and live)
4. **Set environment variables**

### Environment Variables
```bash
# Paper Trading
ALPACA_PAPER_API_KEY=your_paper_key
ALPACA_PAPER_SECRET_KEY=your_paper_secret

# Live Trading
ALPACA_LIVE_API_KEY=your_live_key
ALPACA_LIVE_SECRET_KEY=your_live_secret
```

### First API Call
```rust
// Check account status
GET /v2/account
Headers:
  APCA-API-KEY-ID: {api_key}
  APCA-API-SECRET-KEY: {secret_key}

Response:
{
  "id": "...",
  "account_number": "...",
  "status": "ACTIVE",
  "currency": "USD",
  "buying_power": "50000.00",
  "cash": "50000.00",
  "portfolio_value": "50000.00",
  "pattern_day_trader": false,
  "trading_blocked": false,
  "transfers_blocked": false,
  "account_blocked": false,
  "options_trading_level": 2
}
```


---

## 6. Orders at Alpaca

**Docs:** https://docs.alpaca.markets/docs/orders-at-alpaca

### Order Lifecycle
```
new → pending_new → accepted → filled
                  ↓
                rejected
                  ↓
                canceled
```

### Order Status
- **new** - Order received
- **accepted** - Order accepted by exchange
- **pending_new** - Waiting for acceptance
- **filled** - Order completely filled
- **partially_filled** - Order partially filled
- **canceled** - Order canceled
- **rejected** - Order rejected

### Order Endpoints
```rust
// Submit order
POST /v2/orders

// Get all orders
GET /v2/orders?status=open&limit=100

// Get order by ID
GET /v2/orders/{order_id}

// Cancel order
DELETE /v2/orders/{order_id}

// Cancel all orders
DELETE /v2/orders
```

### Order Response
```json
{
  "id": "61e69015-8549-4bfd-b9c3-01e75843f47d",
  "client_order_id": "my_order_1",
  "created_at": "2025-01-17T14:30:00Z",
  "updated_at": "2025-01-17T14:30:01Z",
  "submitted_at": "2025-01-17T14:30:00Z",
  "filled_at": "2025-01-17T14:30:01Z",
  "symbol": "SPY250117C00600000",
  "asset_class": "us_option",
  "qty": "10",
  "filled_qty": "10",
  "type": "limit",
  "side": "buy_to_open",
  "time_in_force": "day",
  "limit_price": "1.50",
  "filled_avg_price": "1.48",
  "status": "filled"
}
```


---

## 7. Account Activities

**Docs:** https://docs.alpaca.markets/docs/account-activities

### Activity Types
- **FILL** - Order fills
- **TRANS** - Cash transactions
- **DIV** - Dividends
- **OPTC** - Option contract activities
- **OPTN** - Option assignments/exercises
- **OPTEXP** - Option expirations

### Query Activities
```rust
GET /v2/account/activities?activity_types=FILL,OPTC&date=2025-01-17

Response:
[
  {
    "id": "...",
    "activity_type": "FILL",
    "transaction_time": "2025-01-17T14:30:01Z",
    "type": "fill",
    "price": "1.48",
    "qty": "10",
    "side": "buy",
    "symbol": "SPY250117C00600000",
    "leaves_qty": "0",
    "order_id": "...",
    "cum_qty": "10"
  }
]
```

### Use Cases for LevelSweepAgent
- **Track fills** - Confirm order execution
- **Monitor P&L** - Calculate realized gains/losses
- **Audit trail** - Compliance and reporting
- **Reconciliation** - Match orders to fills


---

## 8. Working with Account

**Docs:** https://docs.alpaca.markets/docs/working-with-account

### Account Information
```rust
GET /v2/account

Key Fields:
- buying_power: Available for trading
- cash: Cash balance
- portfolio_value: Total account value
- equity: Long market value + cash
- options_buying_power: Available for options
- options_approved_level: 0, 1, 2, or 3
- pattern_day_trader: PDT status
```

### Account Configuration
```rust
GET /v2/account/configurations

{
  "dtbp_check": "entry",  // Day trade buying power check
  "no_shorting": false,
  "suspend_trade": false,
  "trade_confirm_email": "all"
}
```

### Buying Power Calculation
```
Options Buying Power = Cash + Margin (if approved)

For 0DTE:
- Long calls/puts: Premium paid (no margin)
- Max loss: Premium paid
- No PDT restrictions for cash accounts
```

### LevelSweepAgent Account Requirements
- **Minimum:** $2,000 (recommended $10,000+)
- **Options Level:** 2 (long calls/puts)
- **Account Type:** Cash or margin
- **PDT Status:** Not applicable for cash accounts


---

## 9. Working with Assets

**Docs:** https://docs.alpaca.markets/docs/working-with-assets

### Get Assets
```rust
// Get all tradable assets
GET /v2/assets?status=active&asset_class=us_option

// Get specific asset
GET /v2/assets/SPY250117C00600000
```

### Asset Response
```json
{
  "id": "...",
  "class": "us_option",
  "exchange": "CBOE",
  "symbol": "SPY250117C00600000",
  "name": "SPY Jan 17 2025 600.00 Call",
  "status": "active",
  "tradable": true,
  "marginable": false,
  "shortable": false,
  "easy_to_borrow": false,
  "fractionable": false,
  "attributes": []
}
```

### Options Contract Lookup
```rust
// Get options contracts for underlying
GET /v2/options/contracts?underlying_symbols=SPY&expiration_date=2025-01-17

Response:
{
  "contracts": [
    {
      "id": "...",
      "symbol": "SPY250117C00600000",
      "name": "SPY Jan 17 2025 600.00 Call",
      "status": "active",
      "tradable": true,
      "expiration_date": "2025-01-17",
      "root_symbol": "SPY",
      "underlying_symbol": "SPY",
      "underlying_asset_id": "...",
      "type": "call",
      "style": "american",
      "strike_price": "600",
      "multiplier": "100",
      "size": "100",
      "open_interest": "50000",
      "open_interest_date": "2025-01-16",
      "close_price": "1.50",
      "close_price_date": "2025-01-16"
    }
  ]
}
```


---

## 10. Working with Orders

**Docs:** https://docs.alpaca.markets/docs/working-with-orders

### Submit Order
```rust
POST /v2/orders
{
  "symbol": "SPY250117C00600000",
  "qty": 10,
  "side": "buy_to_open",
  "type": "limit",
  "time_in_force": "day",
  "limit_price": "1.50",
  "client_order_id": "gadiel_signal_123"  // Optional tracking ID
}
```

### Replace Order
```rust
PATCH /v2/orders/{order_id}
{
  "qty": 15,
  "limit_price": "1.45"
}
```

### Cancel Order
```rust
DELETE /v2/orders/{order_id}
```

### Get Order Status
```rust
GET /v2/orders/{order_id}
```

### List Orders
```rust
// Open orders
GET /v2/orders?status=open

// All orders today
GET /v2/orders?after=2025-01-17T00:00:00Z

// Closed orders
GET /v2/orders?status=closed&limit=100
```

### Order Validation
- **Buying power check** - Sufficient funds
- **Contract validation** - Valid OCC symbol
- **Quantity limits** - Max contracts per order
- **Price limits** - Within NBBO spread


---

## 11. Working with Positions

**Docs:** https://docs.alpaca.markets/docs/working-with-positions

### Get All Positions
```rust
GET /v2/positions

Response:
[
  {
    "asset_id": "...",
    "symbol": "SPY250117C00600000",
    "exchange": "CBOE",
    "asset_class": "us_option",
    "qty": "10",
    "avg_entry_price": "1.48",
    "side": "long",
    "market_value": "1520.00",
    "cost_basis": "1480.00",
    "unrealized_pl": "40.00",
    "unrealized_plpc": "0.027",
    "unrealized_intraday_pl": "40.00",
    "unrealized_intraday_plpc": "0.027",
    "current_price": "1.52",
    "lastday_price": "1.50",
    "change_today": "0.013"
  }
]
```

### Get Single Position
```rust
GET /v2/positions/SPY250117C00600000
```

### Close Position
```rust
DELETE /v2/positions/SPY250117C00600000

// Close specific quantity
DELETE /v2/positions/SPY250117C00600000?qty=5
```

### Close All Positions
```rust
DELETE /v2/positions?cancel_orders=true
```

### Position Tracking for LevelSweepAgent
- **Entry price** - Average fill price
- **Current P&L** - Unrealized gains/losses
- **Position size** - Number of contracts
- **Market value** - Current position value


---

## 12. Account Plans

**Docs:** https://docs.alpaca.markets/docs/account-plans

### Plan Types
- **Free** - Paper trading, limited data
- **Unlimited** - Real-time data, unlimited paper trading
- **Plus** - Enhanced data feeds, priority support

### Data Subscriptions
- **IEX (Free)** - 15-minute delayed
- **SIP (Paid)** - Real-time consolidated feed
- **Options Data** - Real-time options quotes and greeks

### LevelSweepAgent Requirements
- **Minimum:** Unlimited plan for real-time data
- **Recommended:** Plus plan for options data
- **Data feeds:** Real-time options quotes for 0DTE trading

---

## 13. Alpaca Elite Smart Router

**Docs:** https://docs.alpaca.markets/docs/alpaca-elite-smart-router

### Smart Order Routing
- **Price improvement** - Best execution across venues
- **Liquidity aggregation** - Multiple exchanges
- **Low latency** - Sub-millisecond routing

### Supported Venues
- **CBOE** - Primary options exchange
- **ISE** - International Securities Exchange
- **PHLX** - Philadelphia Stock Exchange
- **AMEX** - American Stock Exchange

### Benefits for 0DTE
- **Better fills** - Price improvement on tight spreads
- **Faster execution** - Critical for 0DTE timing
- **Higher fill rates** - Access to more liquidity


---

## 14. WebSocket Streaming

**Docs:** https://docs.alpaca.markets/docs/websocket-streaming

### Trade Updates Stream
```rust
wss://paper-api.alpaca.markets/stream

Subscribe to:
{
  "action": "listen",
  "data": {
    "streams": ["trade_updates"]
  }
}

Events:
- new: Order created
- fill: Order filled
- partial_fill: Order partially filled
- canceled: Order canceled
- rejected: Order rejected
```

### Real-Time Position Updates
```json
{
  "stream": "trade_updates",
  "data": {
    "event": "fill",
    "order": {
      "id": "...",
      "symbol": "SPY250117C00600000",
      "filled_qty": "10",
      "filled_avg_price": "1.48"
    }
  }
}
```

### Use Cases for LevelSweepAgent
- **Order monitoring** - Real-time fill notifications
- **Position tracking** - Live P&L updates
- **Risk management** - Immediate stop loss triggers
- **Latency reduction** - No polling required


---

## 15. Position Average Entry Price Calculation

**Docs:** https://docs.alpaca.markets/docs/position-average-entry-price-calculation

### Calculation Method
```
Average Entry Price = Total Cost Basis / Total Quantity

Example:
- Buy 5 contracts @ $1.50 = $750
- Buy 5 contracts @ $1.40 = $700
- Total: 10 contracts, $1,450 cost basis
- Average: $1,450 / 10 = $1.45 per contract
```

### Multiple Fills
- **Partial fills** - Average across all fills
- **Multiple orders** - Cumulative average
- **Commissions** - Included in cost basis

### P&L Calculation
```
Unrealized P&L = (Current Price - Avg Entry Price) × Qty × Multiplier
Realized P&L = (Exit Price - Avg Entry Price) × Qty × Multiplier

For options:
Multiplier = 100 (shares per contract)
```

### LevelSweepAgent Implementation
```rust
// Track average entry for position sizing
let avg_entry = position.cost_basis / position.qty;
let current_pnl = (current_price - avg_entry) * position.qty * 100.0;
let pnl_pct = current_pnl / position.cost_basis;
```


---

## 16. Alpaca MCP Server

**Docs:** https://docs.alpaca.markets/docs/alpaca-mcp-server

### Model Context Protocol
- **AI integration** - Connect AI models to trading
- **Context sharing** - Share market data with AI
- **Tool calling** - AI can execute trades

### Use Cases
- **Signal generation** - AI-powered trade signals
- **Risk analysis** - AI risk assessment
- **Market analysis** - AI market commentary

### LevelSweepAgent Integration
- **TradingView signals** - Primary signal source
- **AI validation** - Optional AI confirmation layer
- **Risk override** - AI can block risky trades

---

## 17. About Market Data API

**Docs:** https://docs.alpaca.markets/docs/about-market-data-api

### Data Types
- **Bars** - OHLCV candles (1min, 5min, 1hour, 1day)
- **Trades** - Individual trade ticks
- **Quotes** - Bid/ask spreads
- **Snapshots** - Current market state

### Data Sources
- **IEX** - Free, 15-minute delayed
- **SIP** - Paid, real-time consolidated
- **Options** - Real-time options data (paid)

### API Endpoints
```rust
// Historical bars
GET /v2/options/bars?symbols=SPY250117C00600000&timeframe=1Min

// Real-time quotes
GET /v2/options/snapshots?symbols=SPY250117C00600000

// Latest trade
GET /v2/options/trades/latest?symbols=SPY250117C00600000
```


---

## 18. Getting Started with Alpaca Market Data

**Docs:** https://docs.alpaca.markets/docs/getting-started-with-alpaca-market-data

### Setup
1. **Subscribe to data plan** - Options data required
2. **Get API keys** - Separate from trading keys
3. **Choose data source** - IEX (free) or SIP (paid)

### First Data Request
```rust
GET /v2/options/snapshots?symbols=SPY250117C00600000
Headers:
  APCA-API-KEY-ID: {data_api_key}
  APCA-API-SECRET-KEY: {data_secret_key}

Response:
{
  "snapshots": {
    "SPY250117C00600000": {
      "latestTrade": {
        "t": "2025-01-17T14:30:00Z",
        "x": "C",
        "p": 1.52,
        "s": 10
      },
      "latestQuote": {
        "t": "2025-01-17T14:30:01Z",
        "ax": "C",
        "ap": 1.53,
        "as": 50,
        "bx": "C",
        "bp": 1.51,
        "bs": 100
      },
      "impliedVolatility": 0.25,
      "greeks": {
        "delta": 0.52,
        "gamma": 0.08,
        "theta": -0.15,
        "vega": 0.12,
        "rho": 0.05
      }
    }
  }
}
```


---

## 19. Historical Option Data

**Docs:** https://docs.alpaca.markets/docs/historical-option-data

### Bars Endpoint
```rust
GET /v2/options/bars
Parameters:
- symbols: Comma-separated OCC symbols
- timeframe: 1Min, 5Min, 15Min, 1Hour, 1Day
- start: Start timestamp (RFC3339)
- end: End timestamp (RFC3339)
- limit: Max bars to return (default 1000)

Example:
GET /v2/options/bars?symbols=SPY250117C00600000&timeframe=1Min&start=2025-01-17T09:30:00Z&end=2025-01-17T16:00:00Z

Response:
{
  "bars": {
    "SPY250117C00600000": [
      {
        "t": "2025-01-17T09:30:00Z",
        "o": 1.45,
        "h": 1.52,
        "l": 1.43,
        "c": 1.50,
        "v": 1500,
        "n": 45,
        "vw": 1.48
      }
    ]
  }
}
```

### Trades Endpoint
```rust
GET /v2/options/trades
Parameters:
- symbols: OCC symbols
- start: Start timestamp
- end: End timestamp
- limit: Max trades (default 1000)
```

### Use Cases for LevelSweepAgent
- **Backtesting** - Download historical 0DTE data
- **Analysis** - Study past setups
- **Optimization** - Test parameter changes
- **Validation** - Verify strategy performance


---

## 20. Historical Stock Data

**Docs:** https://docs.alpaca.markets/docs/historical-stock-data-1

### Underlying Data
```rust
// Get SPY bars for level calculation
GET /v2/stocks/bars?symbols=SPY&timeframe=2Min&start=2025-01-17T09:30:00Z

Response:
{
  "bars": {
    "SPY": [
      {
        "t": "2025-01-17T09:30:00Z",
        "o": 600.50,
        "h": 600.75,
        "l": 600.25,
        "c": 600.60,
        "v": 1500000,
        "n": 5000,
        "vw": 600.55
      }
    ]
  }
}
```

### Use Cases for LevelSweepAgent
- **Level calculation** - PDH/PDL from underlying
- **EMA calculation** - Trend analysis
- **ATR calculation** - Volatility measurement
- **Signal validation** - Confirm underlying movement


---

## 21. Real-Time Option Data

**Docs:** https://docs.alpaca.markets/docs/real-time-option-data

### WebSocket Streaming
```rust
wss://stream.data.alpaca.markets/v2/options

Subscribe:
{
  "action": "subscribe",
  "trades": ["SPY250117C00600000"],
  "quotes": ["SPY250117C00600000"]
}

Trade Message:
{
  "T": "t",
  "S": "SPY250117C00600000",
  "t": "2025-01-17T14:30:00.123Z",
  "x": "C",
  "p": 1.52,
  "s": 10,
  "c": ["@", "I"]
}

Quote Message:
{
  "T": "q",
  "S": "SPY250117C00600000",
  "t": "2025-01-17T14:30:00.456Z",
  "ax": "C",
  "ap": 1.53,
  "as": 50,
  "bx": "C",
  "bp": 1.51,
  "bs": 100,
  "c": ["R"]
}
```

### Greeks Updates
```rust
Subscribe to greeks:
{
  "action": "subscribe",
  "greeks": ["SPY250117C00600000"]
}

Greeks Message:
{
  "T": "g",
  "S": "SPY250117C00600000",
  "t": "2025-01-17T14:30:00Z",
  "d": 0.52,
  "g": 0.08,
  "r": 0.05,
  "t": -0.15,
  "v": 0.12
}
```

### Use Cases for LevelSweepAgent
- **Entry execution** - Real-time pricing for orders
- **Exit monitoring** - Track P&L in real-time
- **Slippage tracking** - Compare fill vs quote
- **Greeks monitoring** - Track delta, theta decay


---

## 22. Market Data FAQ

**Docs:** https://docs.alpaca.markets/docs/market-data-faq

### Common Questions

**Q: What's the difference between IEX and SIP?**
- **IEX:** Free, 15-minute delayed, single exchange
- **SIP:** Paid, real-time, consolidated from all exchanges

**Q: Do I need a separate subscription for options data?**
- Yes, options data requires a paid subscription

**Q: What's the rate limit for market data API?**
- **Free:** 200 requests/minute
- **Paid:** 10,000 requests/minute

**Q: Can I get historical 0DTE options data?**
- Yes, historical bars available for expired contracts

**Q: How far back does historical data go?**
- **Options:** Varies by contract, typically 1-2 years
- **Stocks:** Up to 5+ years

**Q: What's the latency for real-time data?**
- **WebSocket:** Sub-100ms typical
- **REST API:** 100-500ms typical

**Q: Are Greeks calculated or from exchange?**
- Calculated by Alpaca using Black-Scholes model

**Q: Can I get options chain data?**
- Yes, via `/v2/options/contracts` endpoint

---

## Implementation Checklist for LevelSweepAgent

### Phase 1: Setup
- [ ] Create Alpaca account
- [ ] Enable options trading (Level 2)
- [ ] Subscribe to options data plan
- [ ] Generate paper trading API keys
- [ ] Generate live trading API keys
- [ ] Set environment variables

### Phase 2: Integration
- [ ] Implement Alpaca broker adapter in Rust
- [ ] Add authentication headers
- [ ] Implement order submission
- [ ] Implement position tracking
- [ ] Add WebSocket streaming
- [ ] Implement error handling

### Phase 3: Testing
- [ ] Test with paper trading account
- [ ] Validate order execution
- [ ] Test position tracking
- [ ] Verify P&L calculation
- [ ] Test WebSocket reconnection
- [ ] Load test with multiple orders

### Phase 4: Production
- [ ] Switch to live API keys
- [ ] Start with small position sizes
- [ ] Monitor execution quality
- [ ] Track slippage
- [ ] Optimize order routing
- [ ] Scale up gradually

---

## Rust Implementation Example

```rust
// platform/crates/execution/src/brokers/alpaca.rs

use reqwest::Client;
use serde::{Deserialize, Serialize};

pub struct AlpacaBroker {
    client: Client,
    base_url: String,
    api_key: String,
    secret_key: String,
}

impl AlpacaBroker {
    pub fn new(paper: bool) -> Self {
        let base_url = if paper {
            "https://paper-api.alpaca.markets"
        } else {
            "https://api.alpaca.markets"
        };
        
        Self {
            client: Client::new(),
            base_url: base_url.to_string(),
            api_key: std::env::var("ALPACA_API_KEY").unwrap(),
            secret_key: std::env::var("ALPACA_SECRET_KEY").unwrap(),
        }
    }
    
    pub async fn submit_options_order(
        &self,
        symbol: &str,
        qty: u32,
        side: &str,
        limit_price: f64,
    ) -> Result<OrderResponse, Error> {
        let order = OrderRequest {
            symbol: symbol.to_string(),
            qty,
            side: side.to_string(),
            order_type: "limit".to_string(),
            time_in_force: "day".to_string(),
            limit_price: Some(limit_price),
        };
        
        let response = self.client
            .post(&format!("{}/v2/orders", self.base_url))
            .header("APCA-API-KEY-ID", &self.api_key)
            .header("APCA-API-SECRET-KEY", &self.secret_key)
            .json(&order)
            .send()
            .await?;
        
        Ok(response.json().await?)
    }
}

#[derive(Serialize)]
struct OrderRequest {
    symbol: String,
    qty: u32,
    side: String,
    #[serde(rename = "type")]
    order_type: String,
    time_in_force: String,
    limit_price: Option<f64>,
}

#[derive(Deserialize)]
struct OrderResponse {
    id: String,
    symbol: String,
    status: String,
    filled_avg_price: Option<f64>,
}
```

---

## Additional Resources

- **API Reference:** https://docs.alpaca.markets/reference
- **SDKs:** https://github.com/alpacahq
- **Community:** https://forum.alpaca.markets
- **Status Page:** https://status.alpaca.markets
- **Support:** support@alpaca.markets

---

**Last Updated:** 2026-04-30 (curated 2025-12-24, ported into LevelSweepAgent 2026-04-30)
**Maintained By:** LevelSweepAgent owner (codesidh)
