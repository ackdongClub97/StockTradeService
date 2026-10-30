package stockOrder.stockTrade.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /* 발행할 이벤트를 행 락(SELECT ... FOR UPDATE)으로 잡는다 - 즉시 발행(요청 스레드)과 주기 발행(스케줄러), 또는
       서버 여러 대의 릴레이가 같은 이벤트를 동시에 잡으면 뒤에 온 쪽은 앞쪽 커밋을 기다렸다가 publishedAt이 채워진 걸 보고 빠진다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM OutboxEvent e WHERE e.id = :id AND e.publishedAt IS NULL")
    Optional<OutboxEvent> findUnpublishedForUpdate(@Param("id") Long id);

    @Query("SELECT e.id FROM OutboxEvent e WHERE e.publishedAt IS NULL AND e.createdAt < :before ORDER BY e.id")
    List<Long> findUnpublishedIds(@Param("before") LocalDateTime before, Pageable pageable);

    @Query("SELECT e.id FROM OutboxEvent e WHERE e.aggregateId = :aggregateId AND e.publishedAt IS NULL")
    List<Long> findUnpublishedIdsByAggregateId(@Param("aggregateId") Long aggregateId);
}
