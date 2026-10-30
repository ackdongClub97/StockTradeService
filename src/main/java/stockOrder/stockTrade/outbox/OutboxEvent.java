package stockOrder.stockTrade.outbox;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/* Transactional Outbox - Kafka로 보낼 메시지를 업무 데이터(주문)와 같은 DB 트랜잭션 안에서 이 테이블에 먼저 저장하고,
   커밋된 뒤에 OutboxRelay가 꺼내서 발행한다. 주문은 저장됐는데 메시지는 안 나가는(또는 그 반대) 상황을 없애기 위함.
   publishedAt이 null이면 아직 발행 안 된 이벤트. */
@Entity
@Getter
@Setter
@Table(name = "outbox_events", indexes = @Index(name = "idx_outbox_unpublished", columnList = "publishedAt, createdAt"))
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long aggregateId;      // 이벤트 대상 주문ID - 접수 직후 즉시 발행할 때 이 값으로 찾는다
    private String topic;
    private String messageKey;

    @Column(length = 4000)
    private String payload;

    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;

    private int attempts;          // 발행 실패 횟수
    @Column(length = 500)
    private String lastError;

    public static OutboxEvent of(String topic, String messageKey, String payload, Long aggregateId) {
        OutboxEvent event = new OutboxEvent();
        event.setTopic(topic);
        event.setMessageKey(messageKey);
        event.setPayload(payload);
        event.setAggregateId(aggregateId);
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }
}
