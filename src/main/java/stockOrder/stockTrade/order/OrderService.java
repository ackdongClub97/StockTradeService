package stockOrder.stockTrade.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Service;
import stockOrder.stockTrade.matching.MatchingService;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService implements ConsumerSeekAware {

    private final MatchingService matchingService;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /* 주문 접수 메시지(Outbox로 발행, 키 = 종목코드) 소비 -> 이 서버 호가창에 등록 + 즉시체결 시도.
       키가 종목코드라 이 메시지는 그 종목을 맡은 서버(파티션 소유자)에게만 온다.
       예외를 여기서 삼키지 않고 그대로 던진다 - OrderKafkaConfig의 에러 핸들러가 재시도(1초 간격 2회) 후
       stock-order.DLT로 보내고, 형식이 깨진 메시지(JSON 파싱 실패)는 재시도 없이 바로 DLT로 보낸다.
       같은 메시지가 두 번 와도 안전: 호가창 등록은 주문ID로 멱등, tryMatch는 락 안에서 주문 상태를 다시 읽고
       (이미 체결/취소면 건너뜀), 체결 저장은 Order @Version으로 한 번만 성공한다. */
    @KafkaListener(topics = OrderKafkaConfig.ORDER_TOPIC, groupId = "order-group")
    public void processOrder(String message) {
        Order order = objectMapper.readValue(message, Order.class);
        log.info("[Kafka] 주문 수신: {} {}", order.getOrderId(), order.getStockCode());

        matchingService.registerOrder(order);
        matchingService.tryMatch(order);
    }

    /* 리밸런스 콜백 - stock-order 파티션을 받거나 넘길 때 그 종목들의 호가창을 올리거나 정리한다 */
    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        List<Integer> partitions = orderPartitions(assignments.keySet());
        if (partitions.isEmpty()) return;
        // 종목 -> 파티션 계산에 실제 토픽 파티션 수가 필요 - 설정값 대신 브로커 메타데이터에서 읽어 어긋날 일을 없앰
        int partitionCount = kafkaTemplate.partitionsFor(OrderKafkaConfig.ORDER_TOPIC).size();
        matchingService.onPartitionsAssigned(partitions, partitionCount);
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> revoked) {
        List<Integer> partitions = orderPartitions(revoked);
        if (!partitions.isEmpty()) matchingService.onPartitionsRevoked(partitions);
    }

    private static List<Integer> orderPartitions(Collection<TopicPartition> topicPartitions) {
        return topicPartitions.stream()
                .filter(tp -> tp.topic().equals(OrderKafkaConfig.ORDER_TOPIC))
                .map(TopicPartition::partition)
                .toList();
    }
}
