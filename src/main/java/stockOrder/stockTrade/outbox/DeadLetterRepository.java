package stockOrder.stockTrade.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {
    List<DeadLetter> findByReplayedAtIsNullOrderByIdDesc();
}
