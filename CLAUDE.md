# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

A Korea Investment & Securities (KIS/한국투자증권) Open API-backed mock stock trading system. Users get virtual seed money (10,000,000원 on signup), see live volume-ranking and price data proxied from KIS, place buy/sell orders, and see them matched against KIS's real-time price feed via a custom order-matching engine (no external brokerage order execution — matching is implemented in this app).

## Commands

Build and run (Gradle wrapper, Java 21 toolchain):

```bash
./gradlew build              # compile + run tests + package
./gradlew bootRun            # run the app locally (reads src/main/resources/application.yaml)
./gradlew test               # run all tests
./gradlew test --tests stockOrder.stockTrade.StockTradeApplicationTests   # run a single test class
```

Local infra the app expects to be running:
- Kafka broker at `localhost:9092` (topics `stock-order`, `stock-order-result` — no auto-create config, so the broker/topics must exist).
- H2 file DB at `~/stockTrade` (created automatically, `spring.jpa.hibernate.ddl-auto: update`). Console at `/h2-console`.

There is no configured linter in this repo.

## Architecture

### Package layout (`src/main/java/stockOrder/stockTrade/`)
- `token` — fetches/holds the KIS OAuth2 access token.
- `kis` — `KisService` is the sole gateway to the external KIS Open API (WebClient-based, reactive). Owns in-memory caches for ranking data and per-stock price data, and exposes both pull (`Mono`) and push (`Flux`/`Sinks`) access to the same data.
- `stock` — JPA entity/repo for end-of-day ranking snapshots persisted to H2.
- `member` — Spring Security `UserDetailsService` integration (`CustomerDetails`/`CustomerDetailsService`) plus member CRUD and seed-money balance. `CustomerDetails` implements both `UserDetails` and `OAuth2User` so `@AuthenticationPrincipal CustomerDetails` works uniformly regardless of whether login was via form (`CustomerDetailsService`) or Kakao OAuth2 (`KakaoOAuth2UserService`). Kakao login auto-provisions a `Member` on first login (`memberId = "kakao_" + kakaoId`, `provider = "KAKAO"`, same starting seed/level as local signup) — see `SecurityConfig.oauth2Login`.
- `order` — order entity/repo/controller/service. Order IDs are generated as `ORD{yyyyMMdd}{seq4}` (see `OrderController.submitOrder`).
- `matching` — `MatchingService`, the self-built order-matching/execution engine (see below).
- `news` — proxies Naver News search API for stock-related news.
- `security` — `SecurityConfig`, form login, permitAll routes for public pages/APIs.
- `view` — `PageController`, Thymeleaf page routes (`/stockHome`, `/stock/{code}`, `/login`, `/join`, `/myPage`).
- `admin` — ops/monitoring for unfilled orders (see "Order monitoring / admin" below).

### Order flow (the core piece to understand before touching order/matching code)
1. `POST /api/order/create` (`OrderController`) persists the order as `PENDING` and publishes it onto the Kafka topic `stock-order` via `OrderService.sendOrder`.
2. `OrderService.processOrder` (`@KafkaListener` on `stock-order`) consumes it and calls `MatchingService.tryMatch` for an immediate fast-order attempt against the current KIS price.
3. If not immediately matched, a `@Scheduled` batch in `MatchingService.matching()` runs every 30s (only while `KisService.isMarketOpen()`), pulls all distinct stock codes with `PENDING`/`PARTIAL` orders, fetches each one's current price from KIS, and matches eligible orders in `createdAt` order (price-then-time priority). Despite the README calling this "Spring Batch," it is a plain `@Scheduled` method — there is no Spring Batch dependency in the project.
4. Order status machine: `PENDING` → `MATCHED` / `PARTIAL` / `CANCELLED` / `FAILED`. `matchedQuantity`/`matchedPrice` are tracked separately from the order's requested `quantity`/`price`.
5. Execution results are pushed to the placing member over SSE: `MatchingService` holds a multicast `Sinks.Many<Order>`, filtered per member in `OrderController.streamOrderResult` (`GET /api/order/stream`).
6. Member seed balance is debited/credited synchronously inside `MatchingService.execute`/`updateSeed` at fill time — buy orders are balance-checked against current price × remaining quantity before being allowed to fill.

### KIS API integration (`KisService` + `KisWebSocketService`)
- REST: one shared bearer token, refreshed every 12h via `@Scheduled` (`TokenService.fetchToken`); KIS tokens expire in 24h.
- Ranking data (`/quotations/volume-rank`) is still REST-polled on a configurable interval (`kis.rank.refresh-interval-ms`, default 30s) and fanned out to all subscribers via a `replay().latest()` `Sink`, distinguishing pre-market/open/after-market/closed via `isMarketOpen()`/`isAfterMarket()` (Asia/Seoul time, 09:00–15:30 open, 15:30–18:00 after-market). KIS has no realtime-push equivalent for a composite ranking list, so this stays REST-based.
- At market close, the current ranking snapshot is persisted once per day to `stock` (`saveStockEndData`, guarded by the `saveToday` flag) and served from that cache after hours; on app restart it's reloaded from DB (`restartFromDb`) so ranking data survives a redeploy.
- **Per-stock price/orderbook is realtime WebSocket, not REST polling.** `KisWebSocketService` holds a single shared connection to KIS's realtime feed (`hantu-openapi.wsUrl`), authenticated with a separate `approval_key` (`TokenService.fetchApprovalKey`, also refreshed every 12h, forcing a reconnect). `subscribe(code)` is idempotent and registers both `H0STCNT0` (체결가) and `H0STASP0` (호가 10단계) for that code; it also does a one-time REST warm-fetch so the UI isn't blank before the first tick arrives. It's triggered from `KisService.getStockDetailStream`'s `doOnSubscribe` (stock detail page SSE) and from `MatchingService` (see below) — never explicitly unsubscribed (capped at `MAX_SUBSCRIPTIONS = 40`; codes beyond the cap just fall back to REST, so nothing breaks, it just stops being realtime). Incoming frames are pipe/caret-delimited (`0|TR_ID|count|f1^f2^...`), routed by `tr_id`; `PINGPONG` control frames must be echoed back verbatim or KIS drops the connection. A 30s `@Scheduled` health check reconnects (and re-subscribes everything) if the session drops.
- `KisService.pushStockDetail(code, dto)` is the write side of the existing `cachedStockData`/`detailSink` — `KisWebSocketService` calls it on every `H0STCNT0` tick instead of the old 15s polling loop (which has been removed). `KisService` holds a `@Lazy`-injected reference to `KisWebSocketService` (and vice versa is a normal constructor dependency) specifically to break the circular bean dependency between them.
- `MatchingService.matching()`/`tryMatch()` read `kisService.getCachedStockData(code)` first (populated by the websocket) and only fall back to a REST call when the cache is empty (brand-new code, no tick yet) — same call-and-ensure-subscribed pattern as the detail page.
- Both KIS REST calls retry up to 3x with backoff only on `PrematureChannelClosureException`; other errors propagate and are logged with the raw KIS error body.

### Order monitoring / admin (`admin` package)
- `Order` carries `lastUnmatchedReason` (`UnmatchReason`: `NO_ELIGIBLE_PRICE` / `NO_ORDERBOOK_DATA` / `SYSTEM_ERROR`) + `lastAttemptAt`, set on every match attempt in `MatchingService` (`matching()`, `tryMatch()`, `execute()`) so a stuck `PENDING`/`PARTIAL` order's cause is queryable instead of only living in logs. `execute()` and the per-stock-code loop in `matching()` are wrapped in try/catch so one order/stock throwing (typically a DB/HikariCP issue) doesn't abort the rest of the batch — those get tagged `SYSTEM_ERROR` and published to `AdminAlertService`'s SSE sink.
- `GET /admin/orders` (Thymeleaf, `adminOrders.html`) lists all `PENDING`/`PARTIAL` orders with their reason, and a "로그 보기" button that queries Loki directly (`LokiClient`, `GET /api/admin/logs`) for that order's log lines around `lastAttemptAt` — no Grafana needed from this screen, though Grafana Explore works too.
- Access to `/admin/**` and `/api/admin/**` is gated by comparing the logged-in member's id against `admin.member-id` (env `ADMIN_MEMBER_ID`, blank by default = nobody can access) inside `AdminController` — there's no role/authority system in this app (`CustomerDetails.getAuthorities()` always returns `ROLE_USER`), so don't reach for `hasRole(...)` here.
- Local log pipeline for this to work: `observability/docker-compose.yml` runs Loki + Grafana (`docker compose up -d` from that directory); the app pushes logs to Loki directly via a Logback appender (`logback-spring.xml`, `loki-logback-appender`) — no Promtail/file tailing involved. `LOKI_URL` env var points both the appender and `LokiClient` elsewhere if not localhost. `observability/export-logs.sh` bulk-exports a time range via Loki's HTTP API for offline analysis.

### Security
- Session-based form login + Kakao OAuth2 login (`SecurityConfig`), CSRF disabled, H2 console frame options relaxed to same-origin.
- `PasswordConfig` (separate `@Configuration` class) owns the `BCryptPasswordEncoder` bean — it used to live in `SecurityConfig`, but `SecurityConfig` now depends on `KakaoOAuth2UserService`, which depends on the encoder, which would have made `SecurityConfig` depend on itself. Keep the encoder bean out of `SecurityConfig` (or any class `SecurityConfig` depends on) to avoid reintroducing that cycle.
- Public (no-auth) routes: `/login`, `/join`, static assets, `/stockHome`, `/stock/**`, `/api/member/**`, `/api/rank/**`, `/api/news/**`, `/api/stock/**`, `/api/volume/**`, `/api/sse/**`, `/h2-console/**`, `/oauth2/**`, `/login/oauth2/**`. Everything else (including `/api/order/**`) requires authentication.
- SSE endpoints need `DispatcherType.ASYNC` explicitly permitted — Spring Security re-checks auth on async dispatch, which will 403 SSE streams if this is missed (documented past incident in README).

### Config
- `src/main/resources/application.yaml` is the dev/default config; `application-prod.yml` overrides for the EC2 deployment.
- `hantu-openapi.appUrl` points at the KIS **mock/paper-trading** endpoint (`openapivts...`); the real endpoint is present but commented out.
- KIS, Naver, and Kakao credentials (`HANTU_APP_KEY`, `HANTU_APP_SECRET`, `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`, `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`) are read from env vars / an optional gitignored `application-secret.yaml` at the repo root (see `application-secret.yaml.example`) — they are **not** committed. Do not hardcode real credentials back into `application.yaml`.

## Documentation guidelines
- When updating README.md or issue.md, include throughput/latency/performance numbers only if they come from an actual measurement (a load test run, a real log, a benchmark). Never invent or estimate figures to fill in a metrics section — leave it out if nothing's been measured.
