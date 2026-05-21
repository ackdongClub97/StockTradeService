package stockOrder.stockTrade.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import stockOrder.stockTrade.kis.service.KisService;
import stockOrder.stockTrade.order.domain.Order;
import stockOrder.stockTrade.order.domain.OrderStatus;
import stockOrder.stockTrade.order.dto.OrderResponse;
import stockOrder.stockTrade.order.repository.OrderRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderBookService {

   private final OrderRepository orderRepository;
   private final KisService kisService;
   private final OrderService orderService;
   private final ObjectMapper objectMapper;
   private final Map<String, Sinks.Many<Order>> orderSinks = new ConcurrentHashMap<>();

   @KafkaListener(topics = "stock-order", groupId = "order-group")
    public void orderBook(String message) {
       try{
           Order order = objectMapper.readValue(message, Order.class);
           log.info("주문 수신: {}", order.getOrderId());

           kisService.submitOrder(order)
                   .subscribe(
                           result -> handleResult(order, result),
                           err ->handleFailure(order, err)
                   );

       }catch (Exception e){
            log.error("주문 수신 실패: {}", e.getMessage());
       }
   }

   private void handleResult(Order order, OrderResponse response) {
       if(response.getMatchedQuantity() == order.getQuantity()) {
            order.setOrderStatus(OrderStatus.MATCHED); // 체결
       } else if (response.getMatchedQuantity() > 0) {
           order.setOrderStatus(OrderStatus.PARTIAL); // 부분 체결
           order.setMatchedQuantity(response.getMatchedQuantity());
       }

       order.setUpdatedAt(LocalDateTime.now());
       orderRepository.save(order);

       orderService.sendResult(order);
   }

   private void handleFailure(Order order, Throwable throwable) {
       order.setOrderStatus(OrderStatus.FAILED);
       orderRepository.save(order);
       orderService.sendResult(order);
       log.error("주문 실패 : {} / {}", order.getOrderId(), throwable.getMessage());
   }

   @KafkaListener(topics = "stock-order-result", groupId = "result-group")
   public void handleResult(String message) {
       Order order = objectMapper.readValue(message, Order.class);

       Sinks.Many<Order> sink  = orderSinks.get(order.getMemberId());
       if(sink != null) {
           sink.tryEmitNext(order);
       }
   }

   public Flux<Order> getOrderStream(String memberId) {
       return orderSinks.computeIfAbsent(memberId,
               k -> Sinks.many().replay().latest()).asFlux();
   }
}
