package stockOrder.stockTrade.order;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import stockOrder.stockTrade.fds.FraudDetectionService;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.kis.KisWebSocketService;
import stockOrder.stockTrade.kis.ResponseOutputDTO;
import stockOrder.stockTrade.matching.MatchingService;
import stockOrder.stockTrade.member.CustomerDetails;
import stockOrder.stockTrade.member.Member;
import stockOrder.stockTrade.member.MemberRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final MatchingService matchingService;
    private final KisService kisService;
    private final KisWebSocketService kisWebSocketService;
    private final RealizedPnlService realizedPnlService;
    private final MemberRepository memberRepository;
    private final FraudDetectionService fraudDetectionService;

    // 잔고 차감(BUY)/주문 저장이 하나로 안 묶여있으면, 중간에 DB 커넥션 예외 등이 나서 잔고만 빠지고
    // 주문은 안 생기는 상태가 남을 수 있었음 - 트랜잭션으로 묶어서 둘 다 성공하거나 둘 다 롤백되게 함.
    @Transactional
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> submitOrder(@RequestBody Order order, @AuthenticationPrincipal CustomerDetails customerDetails) {
        if (kisService.isBeforeOrderWindow()) {
            return ResponseEntity.badRequest().body(Map.of("message", "주문 접수는 오전 8시부터 가능합니다."));
        }

        String memberId = customerDetails.getMember().getMemberId();

        if (order.getOrderType() == OrderType.BUY) {
            Member member = memberRepository.findByMemberId(memberId).orElseThrow();
            long totalCost = (long) order.getPrice() * order.getQuantity();

            if (member.getSeed() < totalCost) {
                return ResponseEntity.badRequest().body(Map.of("message", "잔고가 부족합니다."));
            }

            // 대기 중인 주문 금액만큼 즉시 예약(차감) - 안 그러면 같은 잔고로 여러 건 주문이 가능해짐.
            // 실제 체결가가 지정가보다 유리했으면 그 차액만 체결 시점에 환급되고(MatchingService.updateSeed),
            // 취소하면 미체결분 예약이 그대로 환급된다(cancelOrder).
            member.setSeed((int) (member.getSeed() - totalCost));
            memberRepository.save(member);
        } else {
            long remaining = realizedPnlService.getRemainingQuantity(memberId, order.getStockCode());
            long reserved = orderRepository.sumReservedSellQuantity(memberId, order.getStockCode());
            long available = remaining - reserved;

            if (order.getQuantity() > available) {
                return ResponseEntity.badRequest().body(Map.of("message", "보유 수량이 부족합니다."));
            }
        }

        order.setMemberId(memberId);
        order.setOrderStatus(OrderStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now());

        log.info("order status {} / order Type {}", order.getOrderStatus(), order.getOrderType());

        orderRepository.save(order);
        matchingService.registerOrder(order); // 가격-시간 우선순위 호가창(OrderBook)에 등록 - O(log n)
        fraudDetectionService.checkOnSubmit(order);
        orderService.sendOrder(order);

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

    /* 대기 중(PENDING/PARTIAL)인 주문만 취소 가능. 이미 체결된 수량은 그대로 Trade에 남고, 남은 미체결분만 취소됨
       환급(memberRepository.save)과 주문 취소 상태 저장(orderRepository.save)을 트랜잭션으로 묶어서
       중간에 실패해도 "환급만 되고 주문은 그대로" 같은 불일치가 안 남게 함 */
    @Transactional
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<Map<String, String>> cancelOrder(@AuthenticationPrincipal CustomerDetails customerDetails,
                                                             @PathVariable Long orderId) {
        if(customerDetails == null){
            return ResponseEntity.status(401).build();
        }

        String memberId = customerDetails.getMember().getMemberId();
        Order order = orderRepository.findById(orderId).orElse(null);

        if(order == null || !order.getMemberId().equals(memberId)) {
            return ResponseEntity.status(404).body(Map.of("message", "주문을 찾을 수 없습니다."));
        }

        if(order.getOrderStatus() != OrderStatus.PENDING && order.getOrderStatus() != OrderStatus.PARTIAL) {
            return ResponseEntity.badRequest().body(Map.of("message", "이미 처리되어 취소할 수 없는 주문입니다."));
        }

        // 매수는 주문 생성 시점에 전체 금액을 예약(차감)해뒀으므로, 취소 시 미체결 잔량분만큼 환급
        if (order.getOrderType() == OrderType.BUY) {
            int remainingQty = order.getQuantity() - order.getMatchedQuantity();
            int refund = order.getPrice() * remainingQty;

            if (refund > 0) {
                Member member = memberRepository.findByMemberId(memberId).orElseThrow();
                member.setSeed(member.getSeed() + refund);
                memberRepository.save(member);
            }
        }

        order.setOrderStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        log.info("[주문취소] {} / {}", memberId, orderId);

        return ResponseEntity.ok(Map.of("message", "SUCCESS"));
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
