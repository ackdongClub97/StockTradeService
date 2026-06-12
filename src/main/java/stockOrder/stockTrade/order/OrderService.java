package stockOrder.stockTrade.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import stockOrder.stockTrade.matching.MatchingService;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final MatchingService matchingService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "stock-order", groupId = "order-group")
    public void processOrder(String message) {
        try {
            Order order =  objectMapper.readValue(message, Order.class);
            log.info("[Kafka] 주문 수신: {}", order.getOrderId());

            matchingService.tryMatch(order);
        } catch (Exception e) {
            log.info("[Kafka error] 주문 처리 실패: {}", e.getMessage());
        }
    }


    public void sendOrder(Order order) {
        try {
            String message = objectMapper.writeValueAsString(order);
            kafkaTemplate.send("stock-order", order.getOrderId(), message);
            log.info("주문 실행: {}", order.getOrderId());
        } catch (Exception e) {
            log.error("주문 실행 실패: {}", order.getOrderId());
        }
    }

    public void sendResult(Order order) {
        try {
            String message = objectMapper.writeValueAsString(order);
            kafkaTemplate.send("stock-order-result", order.getOrderId(), message);
            log.info("주문 실행 결과: {} / {}", order.getOrderId(), order.getOrderStatus());
        } catch (Exception e) {
            log.error("주문 실행 결과 발송 실패: {} / {}", order.getOrderId(), e.getMessage());
        }
    }
}
