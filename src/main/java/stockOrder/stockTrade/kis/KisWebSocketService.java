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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/* KIS 실시간시세 웹소켓 - 체결가(H0STCNT0)/호가(H0STASP0) 종목 단위 구독.
   랭킹(volume-rank)은 KIS 웹소켓에 대응 API가 없어 REST 폴링(KisService.refreshRank)을 그대로 유지한다. */
@Service
@Slf4j
public class KisWebSocketService extends TextWebSocketHandler {

    private static final String TR_CCNL = "H0STCNT0";
    private static final String TR_ASKING = "H0STASP0";
    private static final int MAX_SUBSCRIPTIONS = 40;

    @Value("${hantu-openapi.wsUrl}")
    private String wsUrl;

    private final TokenService tokenService;
    private final KisService kisService;
    private final ObjectMapper objectMapper;

    private volatile String approvalKey;
    private volatile WebSocketSession session;

    private final Set<String> subscribedCodes = ConcurrentHashMap.newKeySet();
    private final Map<String, Sinks.Many<AskingPriceDTO>> askingPriceSink = new ConcurrentHashMap<>();
    // 상세페이지를 아무도 안 보고 있어도(=SSE sink 없어도) 매칭엔진이 최신 호가를 읽을 수 있도록 항상 갱신되는 캐시
    private final Map<String, AskingPriceDTO> cachedAskingPrice = new ConcurrentHashMap<>();

    public KisWebSocketService(TokenService tokenService, KisService kisService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.kisService = kisService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            this.approvalKey = tokenService.fetchApprovalKey();
            connect();
        } catch (Exception e) {
            log.error("[KisWS] 초기화 실패: {}", e.getMessage());
        }
    }

    /* 접속키 유효기간 24시간 - REST 토큰과 동일 주기로 갱신 후 재연결 */
    @Scheduled(fixedRate = 12 * 60 * 60 * 1000, initialDelay = 12 * 60 * 60 * 1000)
    public void refreshApprovalKey() {
        try {
            this.approvalKey = tokenService.fetchApprovalKey();
            log.info("[KisWS] 승인키 갱신 완료 - 재연결");
            closeSessionQuietly();
            connect();
        } catch (Exception e) {
            log.error("[KisWS] 승인키 갱신 실패: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void healthCheck() {
        if (session == null || !session.isOpen()) {
            log.warn("[KisWS] 세션 끊김 감지 - 재연결 시도");
            connect();
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
                        log.info("[KisWS] 연결 성공");
                        resubscribeAll();
                    })
                    .exceptionally(ex -> {
                        log.error("[KisWS] 연결 실패: {}", ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.error("[KisWS] 연결 시도 실패: {}", e.getMessage());
        }
    }

    private void closeSessionQuietly() {
        try {
            if (session != null && session.isOpen()) session.close();
        } catch (Exception e) {
            log.warn("[KisWS] 세션 종료 중 오류: {}", e.getMessage());
        }
    }

    /* 종목 실시간 구독 등록 - 멱등. 최초 구독 시 REST로 한 번 웜업하여 첫 틱 전까지의 공백을 없앤다 */
    public void subscribe(String code) {
        if (subscribedCodes.contains(code)) return;

        if (subscribedCodes.size() >= MAX_SUBSCRIPTIONS) {
            log.warn("[KisWS] 구독 한도({}) 초과 - {} 는 REST 폴백으로만 동작합니다.", MAX_SUBSCRIPTIONS, code);
            return;
        }

        subscribedCodes.add(code);
        sendSubscribe(code);

        kisService.getStockDetail(code).subscribe(
                dto -> kisService.pushStockDetail(code, dto),
                err -> log.warn("[KisWS] {} 웜업 조회 실패: {}", code, err.getMessage())
        );
    }

    public Flux<AskingPriceDTO> getAskingPriceStream(String code) {
        Sinks.Many<AskingPriceDTO> sink = askingPriceSink.computeIfAbsent(code, k -> Sinks.many().replay().latest());

        return sink.asFlux()
                .doOnSubscribe(s -> subscribe(code))
                .doOnCancel(() -> askingPriceSink.remove(code))
                .doOnTerminate(() -> askingPriceSink.remove(code));
    }

    private void resubscribeAll() {
        subscribedCodes.forEach(this::sendSubscribe);
    }

    private void sendSubscribe(String code) {
        send(TR_CCNL, code);
        send(TR_ASKING, code);
    }

    private void send(String trId, String code) {
        if (session == null || !session.isOpen()) {
            log.warn("[KisWS] 세션 미연결 상태 - {} {} 구독 요청 보류", trId, code);
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
            log.info("[KisWS] 구독 요청: {} / {}", trId, code);
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

            log.info("[KisWS] 제어 메시지: {}", raw);
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

        if (TR_CCNL.equals(trId)) {
            handleCcnl(fields);
        } else if (TR_ASKING.equals(trId)) {
            handleAskingPrice(fields);
        }
    }

    // H0STCNT0 필드 순서(KIS 공식 예제 기준): 0 MKSC_SHRN_ISCD, 2 STCK_PRPR, 4 PRDY_VRSS, 5 PRDY_CTRT, 13 ACML_VOL
    private void handleCcnl(String[] f) {
        if (f.length <= 13) return;

        String code = f[0];
        ResponseOutputDTO dto = new ResponseOutputDTO();
        dto.setMkscShrnIscd(code);
        dto.setStckPrpr(f[2]);
        dto.setPrdyVrss(f[4]);
        dto.setPrdyCtrt(f[5]);
        dto.setAcmlVol(f[13]);

        kisService.pushStockDetail(code, dto);
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
        log.warn("[KisWS] 연결 종료: {}", status);
        this.session = null;
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("[KisWS] transport error: {}", exception.getMessage());
    }
}
