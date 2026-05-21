package stockOrder.stockTrade.order.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stockOrder.stockTrade.order.domain.Order;
import stockOrder.stockTrade.order.domain.OrderStatus;
import stockOrder.stockTrade.order.service.OrderService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final AtomicInteger orderSequence = new AtomicInteger(0);

    @PostMapping("/create")
    public ResponseEntity<Map<String, String>> submitOrder(@RequestBody Order order){

        String orderId = "ORD" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + String.format("#04d", orderSequence.incrementAndGet());
        order.setOrderId(orderId);
        order.setOrderStatus(OrderStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now());

        orderService.sendOrder(order);

        log.info("데이터 확인 : {}", order);

        return ResponseEntity.ok(Map.of("orderId",orderId));
    }

}
