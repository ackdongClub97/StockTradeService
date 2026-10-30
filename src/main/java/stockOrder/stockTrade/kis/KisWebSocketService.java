package stockOrder.stockTrade.kis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import stockOrder.stockTrade.token.TokenService;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/* KIS 실시간시세 웹소켓 - 호가(H0STASP0) 종목 단위 구독.
   체결가(H0STCNT0)는 별도로 구독하지 않는다 - 매칭엔진은 원래도 호가만 쓰고, 화면에 필요한
   현재가/등락률/전일대비도 호가 최우선단계 + 전일종가로 계산 가능해서 종목당 실시간 등록 건수를
   2건에서 1건으로 줄였다(KIS 세션당 등록 한도를 아끼기 위함). 다만 누적거래량만은 호가 프레임에
   없는 값이라 REST로 별도 갱신한다(refreshDailyStats 참고).
   랭킹(volume-rank)은 KIS 웹소켓에 대응 API가 없어 REST 폴링(KisService.refreshRank)을 그대로 유지한다. */
@Service
@Slf4j
public class KisWebSocketService extends TextWebSocketHandler {

    private static final String TR_ASKING = "H0STASP0";
    private static final int MAX_SUBSCRIPTIONS = 40;

    @Value("${hantu-openapi.wsUrl}")
    private String wsUrl;

    private final TokenService tokenService;
    private final KisService kisService;
    private final ObjectMapper objectMapper;
    private final MarketDataBus marketDataBus;

    private volatile String approvalKey;
    private volatile WebSocketSession session;

    /* 연결 로그는 "기동 시 첫 시도 / 성공 / 끊김 / 재연결 성공"만 info·warn으로 남긴다.
       끊긴 동안 10초마다 도는 재시도, 12시간마다의 정기 승인키 갱신·재연결, 종목별 구독 요청/응답처럼 계속 반복되는 건 debug */
    private volatile boolean everConnected = false;       // 기동 후 한 번이라도 연결된 적 있는지(첫 연결 vs 재연결 구분)
    private volatile Instant disconnectedAt;               // 예기치 않게 끊긴 시각 - null이면 정상 연결 중
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final Set<String> dailyStatsFailing = ConcurrentHashMap.newKeySet(); // 누적거래량 REST 갱신이 실패 중인 종목
    private volatile boolean plannedReconnect = false;     // 승인키 정기 갱신으로 일부러 끊은 경우

    private final Set<String> subscribedCodes = ConcurrentHashMap.newKeySet();
    private final Map<String, Sinks.Many<AskingPriceDTO>> askingPriceSink = new ConcurrentHashMap<>();
    // 상세페이지를 아무도 안 보고 있어도(=SSE sink 없어도) 매칭엔진이 최신 호가를 읽을 수 있도록 항상 갱신되는 캐시
    private final Map<String, AskingPriceDTO> cachedAskingPrice = new ConcurrentHashMap<>();
    // 종목별 전일종가/최근 누적거래량 - 호가 틱마다 현재가·등락률·전일대비를 계산하는 데 씀(REST 웜업/refreshDailyStats에서 채움)
    private final Map<String, DailyStats> dailyStats = new ConcurrentHashMap<>();

    private record DailyStats(int prevClose, String acmlVol) {}

    public KisWebSocketService(TokenService tokenService, KisService kisService, ObjectMapper objectMapper, MarketDataBus marketDataBus) {
        this.tokenService = tokenService;
        this.kisService = kisService;
        this.objectMapper = objectMapper;
        this.marketDataBus = marketDataBus;
    }

    /* KIS 웹소켓은 수집기(market-data.collector=true) 서버만 연결한다 - 나머지 서버는 Kafka로 시세를 받음(MarketDataBus) */
    @PostConstruct
    public void init() {
        if (!marketDataBus.isCollector()) return;
        try {
            log.info("[KisWS] 웹소켓 승인키 발급·연결 시도(기동)");
            this.approvalKey = tokenService.fetchApprovalKey();
            connect();
        } catch (Exception e) {
            disconnectedAt = Instant.now();
            log.error("[KisWS] 기동 시 승인키 발급 실패 - 10초마다 재시도: {}", e.getMessage());
        }
    }

    /* 접속키 유효기간 24시간 - REST 토큰과 동일 주기로 갱신 후 재연결 */
    @Scheduled(fixedRate = 12 * 60 * 60 * 1000, initialDelay = 12 * 60 * 60 * 1000)
    public void refreshApprovalKey() {
        if (!marketDataBus.isCollector()) return;
        try {
            this.approvalKey = tokenService.fetchApprovalKey();
            log.debug("[KisWS] 승인키 정기 갱신 완료 - 재연결");
            plannedReconnect = true;
            closeSessionQuietly();
            connect();
        } catch (Exception e) {
            log.error("[KisWS] 승인키 정기 갱신 실패 - 기존 연결은 유지, 다음 갱신 때 재시도: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void healthCheck() {
        if (!marketDataBus.isCollector()) return;
        if (session == null || !session.isOpen()) {
            if (disconnectedAt == null) {
                // afterConnectionClosed 없이 끊긴 경우(네트워크 단절 등) - 끊김은 여기서 한 번만 남김
                disconnectedAt = Instant.now();
                log.warn("[KisWS] 연결 끊김 감지 - 10초마다 재연결 시도");
            }
            log.debug("[KisWS] 재연결 시도 #{}", reconnectAttempts.incrementAndGet());
            try {
                if (approvalKey == null) approvalKey = tokenService.fetchApprovalKey(); // 기동 시 승인키 발급부터 실패한 경우
                connect();
            } catch (Exception e) {
                log.debug("[KisWS] 재연결 시도 실패(승인키): {}", e.getMessage());
            }
        }
    }

    private void connect() {
        try {
            // 기본 텍스트 메시지 버퍼(보통 8KB)로는 여러 종목을 구독했을 때 KIS가 보내는 프레임을 못 담아
            // "message too big" 사유로 연결이 끊기는 경우가 있어 넉넉하게 늘려둔다.
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.setDefaultMaxTextMessageBufferSize(1024 * 1024);
            container.setDefaultMaxBinaryMessageBufferSize(1024 * 1024);

            StandardWebSocketClient client = new StandardWebSocketClient(container);
            client.execute(this, new WebSocketHttpHeaders(), URI.create(wsUrl))
                    .thenAccept(newSession -> {
                        this.session = newSession;
                        resubscribeAll();
                        if (!everConnected) {
                            log.info("[KisWS] 연결 성공(기동)");
                        } else if (disconnectedAt != null) {
                            log.info("[KisWS] 재연결 성공 - 끊긴 지 {}초, 재시도 {}회, {}종목 재구독",
                                    Duration.between(disconnectedAt, Instant.now()).toSeconds(), reconnectAttempts.get(), subscribedCodes.size());
                        } else {
                            log.debug("[KisWS] 정기 재연결 완료 - {}종목 재구독", subscribedCodes.size());
                        }
                        everConnected = true;
                        disconnectedAt = null;
                        reconnectAttempts.set(0);
                        plannedReconnect = false;
                    })
                    .exceptionally(ex -> {
                        if (!everConnected && reconnectAttempts.get() == 0) {
                            disconnectedAt = Instant.now();
                            log.error("[KisWS] 기동 시 연결 실패 - 10초마다 재시도: {}", ex.getMessage());
                        } else {
                            log.debug("[KisWS] 재연결 실패: {}", ex.getMessage());
                        }
                        return null;
                    });
        } catch (Exception e) {
            log.debug("[KisWS] 연결 시도 실패: {}", e.getMessage());
        }
    }

    private void closeSessionQuietly() {
        try {
            if (session != null && session.isOpen()) session.close();
        } catch (Exception e) {
            log.warn("[KisWS] 세션 종료 중 오류: {}", e.getMessage());
        }
    }

    /* 어느 서버에서든 호출 - "이 종목 시세가 필요하다"를 수집기에 전달(Kafka). 실제 KIS 등록은 수집기가 subscribeOnKis로 한다 */
    public void subscribe(String code) {
        marketDataBus.requestSubscribe(code);
    }

    /* 수집기 전용 - 종목 실시간 구독 등록. 멱등. 최초 구독 시 REST로 한 번 웜업하여 첫 틱 전까지의 공백을 없앤다
       (장 마감 후엔 틱이 안 오므로 이 웜업 값 = 그날 종가가 Kafka에 남아 모든 서버 화면에 계속 보인다) */
    public void subscribeOnKis(String code) {
        if (subscribedCodes.contains(code)) return;

        if (subscribedCodes.size() >= MAX_SUBSCRIPTIONS) {
            log.warn("[KisWS] 구독 한도({}) 초과 - {} 는 REST 폴백으로만 동작합니다.", MAX_SUBSCRIPTIONS, code);
            return;
        }

        subscribedCodes.add(code);
        sendSubscribe(code);

        kisService.getStockDetail(code).subscribe(
                dto -> {
                    cacheDailyStats(code, dto);
                    marketDataBus.publishStockDetail(code, dto);
                },
                err -> log.warn("[KisWS] {} 웜업 조회 실패: {}", code, err.getMessage())
        );
    }

    /* 누적거래량은 호가 프레임에 없는 값이라 REST로만 얻을 수 있음 - 구독 종목에 한해 30초마다 갱신.
       같은 김에 전일종가도 다시 확인해서(자정 넘어 날짜가 바뀌는 경우 대비) dailyStats를 최신 상태로 유지한다. */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void refreshDailyStats() {
        if (!marketDataBus.isCollector()) return;
        subscribedCodes.forEach(code ->
                kisService.getStockDetail(code).subscribe(
                        dto -> {
                            if (dailyStatsFailing.remove(code)) log.info("[KisWS] {} 누적거래량 갱신 복구", code);
                            cacheDailyStats(code, dto);
                            AskingPriceDTO book = cachedAskingPrice.get(code);
                            if (book != null) pushDerivedDetail(code, book);
                            else marketDataBus.publishStockDetail(code, dto); // 장 마감 후 등 호가 틱이 없으면 REST 값(종가) 그대로
                        },
                        err -> {
                            // KIS가 안 될 때 30초마다 종목 수만큼 반복되지 않도록 첫 실패만 warn, 복구되면 한 줄
                            if (dailyStatsFailing.add(code)) log.warn("[KisWS] {} 누적거래량 갱신 실패 - 복구될 때까지 30초마다 재시도: {}", code, err.getMessage());
                            else log.debug("[KisWS] {} 누적거래량 갱신 재실패: {}", code, err.getMessage());
                        }
                )
        );
    }

    private void cacheDailyStats(String code, ResponseOutputDTO dto) {
        try {
            int prpr = Integer.parseInt(dto.getStckPrpr().trim());
            int vrss = Integer.parseInt(dto.getPrdyVrss().trim());
            dailyStats.put(code, new DailyStats(prpr - vrss, dto.getAcmlVol()));
        } catch (Exception e) {
            log.warn("[KisWS] {} 전일종가 계산 실패: {}", code, e.getMessage());
        }
    }

    /* 체결가(H0STCNT0) 구독 없이 현재가/등락률/전일대비를 재현: 호가 최우선단계(매도1·매수1) 중간값을
       현재가로 근사하고, dailyStats에 캐시해둔 전일종가와 비교해서 계산한다. 누적거래량은 REST 웜업/
       refreshDailyStats에서 마지막으로 받아둔 값을 그대로 씀(다음 갱신 전까지는 다소 지연될 수 있음). */
    private void pushDerivedDetail(String code, AskingPriceDTO book) {
        DailyStats stats = dailyStats.get(code);
        if (stats == null) return; // 아직 REST 웜업 전 - 다음 틱/갱신에서 재시도

        Integer current = deriveCurrentPrice(book);
        if (current == null) return;

        int vrss = current - stats.prevClose();
        double ctrt = stats.prevClose() == 0 ? 0 : vrss * 100.0 / stats.prevClose();

        ResponseOutputDTO dto = new ResponseOutputDTO();
        dto.setMkscShrnIscd(code);
        dto.setStckPrpr(String.valueOf(current));
        dto.setPrdyVrss(String.valueOf(vrss));
        dto.setPrdyCtrt(String.format(java.util.Locale.US, "%.2f", ctrt));
        dto.setAcmlVol(stats.acmlVol());

        marketDataBus.publishStockDetail(code, dto);
    }

    private static Integer deriveCurrentPrice(AskingPriceDTO book) {
        int ask = parseIntSafe(book.getAskPrices()[0]);
        int bid = parseIntSafe(book.getBidPrices()[0]);
        if (ask > 0 && bid > 0) return Math.round((ask + bid) / 2f);
        if (ask > 0) return ask;
        if (bid > 0) return bid;
        return null;
    }

    private static int parseIntSafe(String s) {
        try {
            return (s == null || s.isBlank()) ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public Flux<AskingPriceDTO> getAskingPriceStream(String code) {
        Sinks.Many<AskingPriceDTO> sink = askingPriceSink.computeIfAbsent(code, k -> Sinks.many().replay().latest());

        return sink.asFlux()
                .doOnSubscribe(s -> {
                    subscribe(code);

                    // subscribe()는 이미 구독 중인 종목이면 아무것도 안 하므로, 새로고침 등으로 재접속한
                    // 시점에 캐시된 마지막 호가가 있으면 바로 하나 밀어준다 (없으면 다음 틱까지 빈 화면으로 남는다).
                    AskingPriceDTO cached = cachedAskingPrice.get(code);
                    if (cached != null) {
                        sink.tryEmitNext(cached);
                    }
                })
                // sink를 map에서 지우지 않는다 - replay().latest()가 마지막 값을 들고 있어야
                // 재접속(새로고침) 시 새 틱 없이도 마지막 값을 바로 다시 보여줄 수 있다.
                .onErrorResume(e -> {           // ← 에러 시에만 새 Sink로 교체
                    askingPriceSink.remove(code);
                    return getAskingPriceStream(code);
                });
    }

    private void resubscribeAll() {
        subscribedCodes.forEach(this::sendSubscribe);
    }

    private void sendSubscribe(String code) {
        send(TR_ASKING, code);
    }

    private void send(String trId, String code) {
        if (session == null || !session.isOpen()) {
            log.debug("[KisWS] 세션 미연결 상태 - {} {} 구독 요청 보류(재연결 시 전체 재구독)", trId, code);
            return;
        }
        try {
            Map<String, Object> header = new HashMap<>();
            header.put("approval_key", approvalKey);
            header.put("custtype", "P");
            header.put("tr_type", "1");
            header.put("content-type", "utf-8");

            Map<String, String> input = new HashMap<>();
            input.put("tr_id", trId);
            input.put("tr_key", code);

            Map<String, Object> message = new HashMap<>();
            message.put("header", header);
            message.put("body", Map.of("input", input));

            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
            log.debug("[KisWS] 구독 요청: {} / {}", trId, code);
        } catch (Exception e) {
            log.error("[KisWS] 구독 요청 실패: {} / {} / {}", trId, code, e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String raw = message.getPayload();

        if (raw.startsWith("0|") || raw.startsWith("1|")) {
            handleRealtimeFrame(raw);
        } else {
            handleControlMessage(session, raw);
        }
    }

    /* PINGPONG은 KIS가 연결 유지를 위해 주기적으로 보내는 JSON 메시지 - 동일 payload를 그대로 echo 해야 함 */
    private void handleControlMessage(WebSocketSession session, String raw) {
        try {
            JsonNode header = objectMapper.readTree(raw).get("header");
            String trId = (header != null && header.get("tr_id") != null) ? header.get("tr_id").asText() : null;

            if ("PINGPONG".equals(trId)) {
                session.sendMessage(new TextMessage(raw));
                return;
            }

            // 구독 요청마다 오는 응답(SUBSCRIBE SUCCESS 등)은 반복 로그라 debug, 실패 응답(rt_cd != 0)만 남긴다
            JsonNode body = objectMapper.readTree(raw).get("body");
            String rtCd = (body != null && body.get("rt_cd") != null) ? body.get("rt_cd").asText() : "0";
            if ("0".equals(rtCd)) {
                log.debug("[KisWS] 제어 메시지: {}", raw);
            } else {
                log.warn("[KisWS] 구독 요청 실패 응답: {}", raw);
            }
        } catch (Exception e) {
            log.warn("[KisWS] 제어 메시지 파싱 실패: {}", raw);
        }
    }

    /* 실시간 데이터 프레임: "0|TR_ID|건수|필드1^필드2^..." */
    private void handleRealtimeFrame(String raw) {
        String[] parts = raw.split("\\|", 4);
        if (parts.length < 4) return;

        String trId = parts[1];
        String[] fields = parts[3].split("\\^");

        if (TR_ASKING.equals(trId)) {
            handleAskingPrice(fields);
        }
    }

    // H0STASP0 필드 순서: 0 MKSC_SHRN_ISCD, 3~12 ASKP1~10, 13~22 BIDP1~10,
    // 23~32 ASKP_RSQN1~10, 33~42 BIDP_RSQN1~10, 43 TOTAL_ASKP_RSQN, 44 TOTAL_BIDP_RSQN
    private void handleAskingPrice(String[] f) {
        if (f.length < 45) return;

        String code = f[0];
        AskingPriceDTO dto = new AskingPriceDTO();
        dto.setStockCode(code);

        String[] askPrices = new String[10];
        String[] bidPrices = new String[10];
        String[] askVolumes = new String[10];
        String[] bidVolumes = new String[10];

        for (int i = 0; i < 10; i++) {
            askPrices[i] = f[3 + i];
            bidPrices[i] = f[13 + i];
            askVolumes[i] = f[23 + i];
            bidVolumes[i] = f[33 + i];
        }

        dto.setAskPrices(askPrices);
        dto.setBidPrices(bidPrices);
        dto.setAskVolumes(askVolumes);
        dto.setBidVolumes(bidVolumes);
        dto.setTotalAskVolume(f[43]);
        dto.setTotalBidVolume(f[44]);

        // 수집기는 받은 틱을 Kafka로 발행만 하고, 자기 캐시도 다른 서버와 똑같이 Kafka에서 받아 갱신한다(applyAskingPrice)
        marketDataBus.publishAskingPrice(dto);
        pushDerivedDetail(code, dto);
    }

    /* 모든 서버 - Kafka로 받은 호가 틱을 캐시(매칭엔진용)와 SSE에 반영.
       틱마다 새 객체라 MatchingService의 "같은 틱이냐"(객체 identity) 판별이 그대로 동작한다 */
    public void applyAskingPrice(AskingPriceDTO dto) {
        String code = dto.getStockCode();
        cachedAskingPrice.put(code, dto);

        Sinks.Many<AskingPriceDTO> sink = askingPriceSink.get(code);
        if (sink != null) sink.tryEmitNext(dto);
    }

    /* 매칭엔진이 체결가능수량/가격을 판단할 때 쓰는 최신 호가 스냅샷 - 없으면 null(아직 첫 프레임 전) */
    public AskingPriceDTO getCachedAskingPrice(String code) {
        return cachedAskingPrice.get(code);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        this.session = null;
        if (plannedReconnect) {
            log.debug("[KisWS] 승인키 갱신으로 연결 종료: {}", status);
        } else if (disconnectedAt == null) {
            disconnectedAt = Instant.now();
            log.warn("[KisWS] 연결 끊김: {} - 10초마다 재연결 시도", status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("[KisWS] transport error: {}", exception.getMessage()); // 끊김 자체는 afterConnectionClosed/healthCheck가 한 번만 남김
    }
}
