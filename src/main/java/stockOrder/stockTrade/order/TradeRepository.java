package stockOrder.stockTrade.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {
    List<Trade> findByMemberIdOrderByMatchedAtAsc(String memberId);

    /* FDS 초단타 왕복매매 탐지용 - 특정 회원/종목에서 반대 방향 체결이 최근 몇 초 내에 있었는지 확인 */
    List<Trade> findByMemberIdAndStockCodeAndOrderTypeAndMatchedAtAfter(
            String memberId, String stockCode, OrderType orderType, LocalDateTime after
    );
}
