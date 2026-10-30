package stockOrder.stockTrade.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import stockOrder.stockTrade.matching.MatchingService;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final MatchingService matchingService;
    private final ObjectMapper objectMapper;

    /* 주문 접수 메시지(Outbox로 발행됨) 소비 -> 즉시체결 시도.
       예외를 여기서 삼키지 않고 그대로 던진다 - OrderKafkaConfig의 에러 핸들러가 재시도(1초 간격 2회) 후
       stock-order.DLT로 보내고, 형식이 깨진 메시지(JSON 파싱 실패)는 재시도 없이 바로 DLT로 보낸다.
       예전엔 catch에서 log.info만 남기고 끝나서, 실패한 메시지가 어디에도 남지 않았음.
       같은 메시지가 두 번 와도 안전: tryMatch가 락 안에서 주문 상태를 다시 읽고(이미 체결/취소면 건너뜀),
       체결 저장은 Order @Version으로 한 번만 성공한다. */
    @KafkaListener(topics = OrderKafkaConfig.ORDER_TOPIC, groupId = "order-group")
    public void processOrder(String message) {
        Order order = objectMapper.readValue(message, Order.class);
        log.info("[Kafka] 주문 수신: {}", order.getOrderId());

        matchingService.tryMatch(order);
    }
}
