package stockOrder.stockTrade.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import stockOrder.stockTrade.fds.FraudDetectionService;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.kis.KisWebSocketService;
import stockOrder.stockTrade.kis.ResponseOutputDTO;
import stockOrder.stockTrade.matching.MatchingService;
import stockOrder.stockTrade.member.CustomerDetails;
import stockOrder.stockTrade.outbox.OutboxRelay;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderRepository orderRepository;
    private final MatchingService matchingService;
    private final KisService kisService;
    private final KisWebSocketService kisWebSocketService;
    private final RealizedPnlService realizedPnlService;
    private final FraudDetectionService fraudDetectionService;
    private final OrderCommandService orderCommandService;
    private final OutboxRelay outboxRelay;

    // 잔고 예약 + 주문 저장은 OrderCommandService.submit 트랜잭션 안에서 처리하고(회원 행 락으로 동시 주문 직렬화),
    // 아래 Kafka 발행(Outbox 즉시 발행)은 그 트랜잭션이 커밋된 뒤에 실행된다. 호가창(OrderBook) 등록은 그 종목을 맡은 서버의 컨슈머가 한다 - 커밋 전에 컨슈머가 주문을 읽는 일이 없도록.
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> submitOrder(@RequestBody Order order, @AuthenticationPrincipal CustomerDetails customerDetails) {
        if (kisService.isBeforeOrderWindow()) {
            return ResponseEntity.badRequest().body(Map.of("message", "주문 접수는 오전 8시부터 가능합니다."));
        }

        String memberId = customerDetails.getMember().getMemberId();

        switch (orderCommandService.submit(order, memberId)) {
            case INSUFFICIENT_SEED:
                return ResponseEntity.badRequest().body(Map.of("message", "잔고가 부족합니다."));
            case INSUFFICIENT_QUANTITY:
                return ResponseEntity.badRequest().body(Map.of("message", "보유 수량이 부족합니다."));
            default:
                break;
        }

        log.info("order status {} / order Type {}", order.getOrderStatus(), order.getOrderType());

        fraudDetectionService.checkOnSubmit(order);
        outboxRelay.publishForAggregate(order.getOrderId()); // 커밋 후 즉시 발행 - 실패해도 OutboxRelay 주기 발행이 재시도

        return ResponseEntity.ok(Map.of("orderId", order.getOrderId()));
    }

    @GetMapping(value="/stream", produces= MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> streamOrderResult(@AuthenticationPrincipal CustomerDetails customerDetails) {
        String memberId = customerDetails.getMember().getMemberId();

        return matchingService.getOrderResultStream(memberId)
                .map(order -> ServerSentEvent.builder()
                .event("order-result")
                .data((Object) Map.of(
                        "orderId", order.getOrderId(),
                        "stockCode", order.getStockCode(),
                        "stockName", order.getStockName(),
                        "orderStatus", order.getOrderStatus(),
                        "quantity", order.getQuantity(),
                        "price", order.getPrice(),
                        "orderType", order.getOrderType()
                        )).build()
                )
                .doOnSubscribe(s -> log.info("[SSE] order/stream 연결 {}", memberId))
                .doFinally(signal -> log.info("[SSE] order/stream 종료 {} ({})", memberId, signal));
    }

    @GetMapping("/holding")
    public ResponseEntity<List<Map<String, Object>>> getHolding(@AuthenticationPrincipal CustomerDetails customerDetails) {
        String memberId = customerDetails.getMember().getMemberId();
        return ResponseEntity.ok(computeHolding(memberId));
    }

    /* 보유주식 실시간 스트림 - 보유 종목의 체결가가 갱신되거나 주문이 체결될 때마다 전체 목록을 다시 계산해서 내려줌 */
    @GetMapping(value = "/holding/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> streamHolding(@AuthenticationPrincipal CustomerDetails customerDetails) {
        String memberId = customerDetails.getMember().getMemberId();

        List<Map<String, Object>> initial = computeHolding(memberId);
        Set<String> stockCodes = initial.stream()
                .map(m -> (String) m.get("stockCode"))
                .collect(Collectors.toSet());

        // 보유 종목은 상세페이지를 아무도 안 보고 있어도 실시간 가격을 받아야 하므로 직접 구독 보장
        stockCodes.forEach(kisWebSocketService::subscribe);

        Flux<Object> trigger = Flux.merge(
                kisService.getPriceUpdateStream().filter(stockCodes::contains).cast(Object.class),
                matchingService.getOrderResultStream(memberId).cast(Object.class)
        ).sample(Duration.ofSeconds(1));

        Flux<List<Map<String, Object>>> updates = trigger.map(x -> computeHolding(memberId));

        return Flux.concat(Mono.just(initial), updates)
                .map(list -> ServerSentEvent.builder()
                        .event("holding")
                        .data((Object) list)
                        .build())
                .doOnSubscribe(s -> log.info("[SSE] holding/stream 연결 {}", memberId))
                .doFinally(signal -> log.info("[SSE] holding/stream 종료 {} ({})", memberId, signal));
    }

    private List<Map<String, Object>> computeHolding(String memberId) {
        Map<String, RealizedPnlService.Position> positions = realizedPnlService.getPositions(memberId);

        return positions.values().stream()
                .filter(p -> p.getQuantity() > 0)
                .map(p -> {
            Map<String, Object> map = new HashMap<>();
            long currentQuantity = p.getQuantity();
            double avgPrice = p.getAvgCost();

            map.put("stockName", p.getStockName());
            map.put("stockCode", p.getStockCode());
            map.put("totalQuantity", currentQuantity);
            map.put("avgPrice", avgPrice);

            ResponseOutputDTO cached = kisService.getCachedStockData(p.getStockCode());
            int currentPrice = 0;

            if(cached != null && cached.getStckPrpr() != null) {
                currentPrice = Integer.parseInt(cached.getStckPrpr());
            } else {
                try{
                    ResponseOutputDTO stockDetail = kisService.getStockDetail(p.getStockCode()).block();
                    currentPrice = (stockDetail != null) ? Integer.parseInt(stockDetail.getStckPrpr()) : 0;

                    if(stockDetail != null) {
                        kisService.putCachedStockData(p.getStockCode(), stockDetail);
                    }
                } catch (Exception e){
                    log.info("현재가 조회 실패 {} : {}", p.getStockCode(), e.getMessage());
                }
            }
            map.put("currentPrice", currentPrice);

            // 수익율
            double profitRate = avgPrice == 0 ? 0 : (currentPrice - avgPrice) / avgPrice * 100;
            map.put("profitRate", profitRate);
            // 평가 금액
            map.put("totalValue", (currentPrice * currentQuantity));
            // 매수 금액
            map.put("buyValue", (avgPrice * currentQuantity));
            // 수익 금액
            map.put("profitValue", (currentPrice - avgPrice) * currentQuantity);

            return map;
        }).toList();
    }

    /* 매매차익(실현손익) - 파라미터 없으면 이번 달(1일~오늘) 기준 */
    @GetMapping("/realized-pnl")
    public ResponseEntity<Map<String, Object>> getRealizedPnl(@AuthenticationPrincipal CustomerDetails customerDetails,
                                                                @RequestParam(required = false) String from,
                                                                @RequestParam(required = false) String to) {
        if(customerDetails == null){
            return ResponseEntity.status(401).build();
        }

        String memberId = customerDetails.getMember().getMemberId();
        LocalDate toDate = (to != null) ? LocalDate.parse(to) : LocalDate.now();
        LocalDate fromDate = (from != null) ? LocalDate.parse(from) : toDate.withDayOfMonth(1);

        return ResponseEntity.ok(realizedPnlService.getRealizedPnl(memberId, fromDate, toDate));
    }

    @GetMapping("/history")
    public ResponseEntity<List<Order>> getHistory(@AuthenticationPrincipal CustomerDetails customerDetails) {
        String memberId = customerDetails.getMember().getMemberId();
        List<Order> orderHistoryList = orderRepository.findByMemberOrderHistory(memberId);
        return ResponseEntity.ok(orderHistoryList);
    }

    /* 환급 + 취소 상태 저장은 OrderCommandService.cancel 트랜잭션 안에서 처리 - 체결과 동시에 일어나면
       Order의 @Version 충돌로 나중 쪽이 롤백되고, 취소가 진 경우 409로 다시 시도하라고 응답한다 */
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<Map<String, String>> cancelOrder(@AuthenticationPrincipal CustomerDetails customerDetails,
                                                             @PathVariable Long orderId) {
        if(customerDetails == null){
            return ResponseEntity.status(401).build();
        }

        String memberId = customerDetails.getMember().getMemberId();

        return switch (orderCommandService.cancel(memberId, orderId)) {
            case NOT_FOUND -> ResponseEntity.status(404).body(Map.of("message", "주문을 찾을 수 없습니다."));
            case NOT_CANCELLABLE -> ResponseEntity.badRequest().body(Map.of("message", "이미 처리되어 취소할 수 없는 주문입니다."));
            case CONFLICT -> ResponseEntity.status(409).body(Map.of("message", "체결 처리 중이라 취소하지 못했습니다. 잠시 후 다시 시도해 주세요."));
            case OK -> ResponseEntity.ok(Map.of("message", "SUCCESS"));
        };
    }

    @GetMapping("/memberStockInfo")
    public ResponseEntity<Map<String,Object>> getMemberStock(@AuthenticationPrincipal CustomerDetails customerDetails,
                                                             @RequestParam String stockCode) {
        if(customerDetails == null){
            return ResponseEntity.status(401).build();
        }

        String memberId = customerDetails.getMember().getMemberId();
        RealizedPnlService.Position position = realizedPnlService.getPosition(memberId, stockCode);

        // 실제 체결된 잔여수량에서 이미 대기 중인 다른 매도주문의 미체결 물량을 뺀, "지금 추가로 매도 주문 낼 수 있는 수량"
        long reserved = orderRepository.sumReservedSellQuantity(memberId, stockCode);
        long totalQuantity = Math.max(0, position.getQuantity() - reserved);

        return ResponseEntity.ok(Map.of(
                "totalQuantity", totalQuantity,
                "avgPrice", position.getAvgCost()
        ));
    }
}
