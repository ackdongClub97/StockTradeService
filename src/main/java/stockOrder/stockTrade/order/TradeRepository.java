package stockOrder.stockTrade.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {
    List<Trade> findByMemberIdOrderByMatchedAtAsc(String memberId);
}
