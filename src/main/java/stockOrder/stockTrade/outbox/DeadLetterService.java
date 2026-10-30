package stockOrder.stockTrade.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stockOrder.stockTrade.admin.AdminAlert;
import stockOrder.stockTrade.admin.AdminAlertService;
import stockOrder.stockTrade.order.OrderKafkaConfig;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/* DLT(stock-order.DLT)로 간 메시지를 DB에 적재하고 관리자에게 알린 뒤, 관리자가 원인을 해결하면 원래 토픽으로 재발행한다 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeadLetterService {

    private final DeadLetterRepository deadLetterRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AdminAlertService adminAlertService;

    @KafkaListener(topics = OrderKafkaConfig.ORDER_DLT, groupId = "order-dlt")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        DeadLetter deadLetter = new DeadLetter();
        String originalTopic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        deadLetter.setOriginalTopic(originalTopic != null ? originalTopic : OrderKafkaConfig.ORDER_TOPIC);
        deadLetter.setMessageKey(record.key());
        deadLetter.setPayload(record.value());
        String error = String.valueOf(header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
        deadLetter.setErrorMessage(error.length() > 1000 ? error.substring(0, 1000) : error);
        deadLetter.setCreatedAt(LocalDateTime.now());
        deadLetterRepository.save(deadLetter);

        log.error("[DLQ] 처리 실패 메시지 적재 id={} topic={} key={} : {}", deadLetter.getId(), deadLetter.getOriginalTopic(), record.key(), error);
        adminAlertService.publish(new AdminAlert(null, record.key(), // 주문 메시지 키 = 종목코드
                "DLQ 적재(재시도 후에도 실패): " + error, LocalDateTime.now()));
    }

    public List<DeadLetter> findPending() {
        return deadLetterRepository.findByReplayedAtIsNullOrderByIdDesc();
    }

    /* 원래 토픽으로 재발행. 이미 재발행한 건은 다시 보내지 않는다(관리자 버튼 중복 클릭 방지) */
    @Transactional
    public boolean replay(Long id) throws Exception {
        DeadLetter deadLetter = deadLetterRepository.findById(id).orElse(null);
        if (deadLetter == null || deadLetter.getReplayedAt() != null) return false;

        kafkaTemplate.send(deadLetter.getOriginalTopic(), deadLetter.getMessageKey(), deadLetter.getPayload())
                .get(5, TimeUnit.SECONDS);
        deadLetter.setReplayedAt(LocalDateTime.now());
        log.info("[DLQ] 재발행 id={} topic={} key={}", id, deadLetter.getOriginalTopic(), deadLetter.getMessageKey());
        return true;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
