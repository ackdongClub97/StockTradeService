package stockOrder.stockTrade.outbox;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/* 재시도 끝에 처리 못 한 메시지(DLT로 간 메시지)의 기록 - 원인 확인 후 어드민에서 원래 토픽으로 재발행할 수 있게 DB에 남긴다 */
@Entity
@Getter
@Setter
@Table(name = "dead_letters")
public class DeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String originalTopic;
    private String messageKey;

    @Column(length = 4000)
    private String payload;

    @Column(length = 1000)
    private String errorMessage;

    private LocalDateTime createdAt;
    private LocalDateTime replayedAt;   // 재발행한 시각 - null이면 아직 처리 안 됨
}
