package stockOrder.stockTrade.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/* Outbox 테이블의 미발행 이벤트를 Kafka로 발행한다. "최소 한 번(at-least-once)" 보장 - 발행은 성공했는데
   publishedAt 저장 전에 죽으면 다시 발행되므로, 컨슈머 쪽이 중복 메시지에 안전해야 한다
   (주문 메시지는 tryMatch의 상태 재확인 + Order @Version + OrderBook 멱등 등록으로 중복에 안전).

   발행 경로 두 개:
   1) 즉시 발행 - 주문 접수 트랜잭션이 커밋된 직후 요청 스레드가 publishForAggregate 호출 (지연 최소화)
   2) 주기 발행 - 1초마다 1초 이상 밀린 미발행 이벤트를 재시도 (즉시 발행이 실패했거나, 그 사이 서버가 죽은 경우) */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    // publish의 @Transactional이 같은 클래스 안 호출에서도 걸리도록 프록시 자기주입 (MatchingService와 같은 패턴)
    @Autowired
    @Lazy
    private OutboxRelay self;

    /* 이벤트 하나를 발행. 행 락을 잡은 채로 Kafka 전송 완료(ack)까지 기다린 뒤 publishedAt을 기록한다.
       락을 잡은 채 네트워크 호출을 하는 대신, 같은 이벤트를 두 군데서 동시에 보내는 일을 DB 락 하나로 막는 쪽을 택함 -
       이벤트 하나당 락 범위가 행 하나뿐이고 전송 타임아웃(5초)으로 상한이 있음. */
    @Transactional
    public void publish(Long eventId) {
        OutboxEvent event = outboxEventRepository.findUnpublishedForUpdate(eventId).orElse(null);
        if (event == null) return; // 이미 다른 경로가 발행함

        try {
            kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            event.setPublishedAt(LocalDateTime.now());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            event.setAttempts(event.getAttempts() + 1);
            String message = String.valueOf(e.getMessage());
            event.setLastError(message.length() > 500 ? message.substring(0, 500) : message);
            log.warn("[Outbox] 발행 실패 eventId={} topic={} attempts={} : {}", event.getId(), event.getTopic(), event.getAttempts(), message);
        }
    }

    /* 주문 접수 커밋 직후 호출 - 실패해도 주기 발행이 다시 보내므로 예외를 요청까지 올리지 않는다 */
    public void publishForAggregate(Long aggregateId) {
        try {
            outboxEventRepository.findUnpublishedIdsByAggregateId(aggregateId).forEach(self::publish);
        } catch (Exception e) {
            log.warn("[Outbox] 즉시 발행 실패 aggregateId={} - 주기 발행에서 재시도: {}", aggregateId, e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void publishPending() {
        List<Long> ids = outboxEventRepository.findUnpublishedIds(LocalDateTime.now().minusSeconds(1), PageRequest.of(0, 100));
        if (ids.isEmpty()) return;
        log.info("[Outbox] 밀린 이벤트 {}건 재발행 시도", ids.size());
        ids.forEach(self::publish);
    }
}
