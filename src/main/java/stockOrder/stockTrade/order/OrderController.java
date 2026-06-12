package stockOrder.stockTrade.order;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.kis.ResponseOutputDTO;
import stockOrder.stockTrade.matching.MatchingService;
import stockOrder.stockTrade.member.CustomerDetails;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final AtomicInteger orderSequence = new AtomicInteger(0);
    private final MatchingService matchingService;
    private final KisService kisService;

    @PostMapping("/create")
    public ResponseEntity<Map<String, String>> submitOrder(@RequestBody Order order, @AuthenticationPrincipal CustomerDetails customerDetails) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "ORD" + today;

        String maxId = orderRepository.findMaxOrderIdByPrefix(prefix);
        int seq = 1;
        if (maxId != null) {
            seq = Integer.parseInt(maxId.substring(maxId.length() - 4)) + 1;
        }

        String orderId = prefix + String.format("%04d", seq);

        order.setOrderId(orderId);
        order.setMemberId(customerDetails.getMember().getMemberId());
        order.setOrderStatus(OrderStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now());

        log.info("order status {} / order Type {}", order.getOrderStatus(), order.getOrderType());

        orderRepository.save(order);
        orderService.sendOrder(order);

        return ResponseEntity.ok(Map.of("orderId",orderId));
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
                );
    }

    @GetMapping("/holding")
    public ResponseEntity<List<Map<String, Object>>> getHolding(@AuthenticationPrincipal CustomerDetails customerDetails) {
        String memberId = customerDetails.getMember().getMemberId();
        List<HoldingOrderDTO> orders = orderRepository.findByMemberOrderList(memberId);
        List<Map<String, Object>> result = orders.stream().map(o -> {
            Map<String, Object> map = new HashMap<>();
            int currentQuantity = Integer.parseInt(String.valueOf(o.getBuyQuantity() - o.getSellQuantity()));

            map.put("stockName", o.getStockName());
            map.put("stockCode", o.getStockCode());
            map.put("totalQuantity", currentQuantity);
            map.put("totalPrice", o.getBuyPrice() - o.getSellPrice());
            map.put("avgPrice", o.getAvgPrice());

            ResponseOutputDTO cached = kisService.getCachedStockData(o.getStockCode());
            int currentPrice = 0;

            if(cached != null && cached.getStckPrpr() != null) {
                currentPrice = Integer.parseInt(cached.getStckPrpr());
            } else {
                try{
                    ResponseOutputDTO stockDetail = kisService.getStockDetail(o.getStockCode()).block();
                    currentPrice = (stockDetail != null) ? Integer.parseInt(stockDetail.getStckPrpr()) : 0;

                    if(stockDetail != null) {
                        kisService.putCachedStockData(o.getStockCode(), stockDetail);
                    }
                } catch (Exception e){
                    log.info("현재가 조회 실패 {} : {}", o.getStockCode(), e.getMessage());
                }
            }
            map.put("currentPrice", currentPrice);
            log.info("currentPrice : {}", currentPrice);

            // 수익율
            double profitRate = (double) (currentPrice - o.getAvgPrice()) / o.getAvgPrice() * 100;
            map.put("profitRate", profitRate);
            // 평가 금액
            map.put("totalValue", (currentPrice * currentQuantity));
            // 매수 금액
            map.put("buyValue", (o.getAvgPrice() * currentQuantity));
            // 수익 금액
            map.put("profitValue", (currentPrice - o.getAvgPrice()) * currentQuantity);

            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    @GetMapping("/history")
    public ResponseEntity<List<Order>> getHistory(@AuthenticationPrincipal CustomerDetails customerDetails) {
        String memberId = customerDetails.getMember().getMemberId();
        List<Order> orderHistoryList = orderRepository.findByMemberOrderHistory(memberId);
        return ResponseEntity.ok(orderHistoryList);
    }

    @GetMapping("/memberStockInfo")
    public ResponseEntity<Map<String,Object>> getMemberStock(@AuthenticationPrincipal CustomerDetails customerDetails,
                                                             @RequestParam String stockCode) {
        if(customerDetails == null){
            return ResponseEntity.status(401).build();
        }

        String memberId = customerDetails.getMember().getMemberId();
        List<Map<String, Object>> orders = orderRepository.findByMemberStockInfo(memberId, stockCode);

        if(orders.isEmpty()) {
            return ResponseEntity.ok(Map.of("totalQuantity", 0, "avgPrice", 0));
        }

        Map<String,Object> map = orders.get(0);
        long buyQuantity = (long) map.get("buyQuantity");
        long sellQuantity = (long) map.get("sellQuantity");
        long totalQuantity = buyQuantity - sellQuantity;

        return ResponseEntity.ok(Map.of(
                "totalQuantity", totalQuantity
        ));
    }
}
