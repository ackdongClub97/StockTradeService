package stockOrder.stockTrade.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /* 대기 종목 코드 목록 */
    @Query("SELECT DISTINCT o.stockCode FROM Order o WHERE o.orderStatus IN :orderStatus")
    List<String> findPendingStockList(@Param("orderStatus") List<OrderStatus> orderStatus);

    /* 매수 대기 주문 - 가격 우선(지정가가 높을수록 더 공격적인 주문이므로 먼저), 동일 가격이면 시간 우선(선착순).
       어떤 호가 레벨이 조건에 맞는지는 Java(BookLevels)에서 판단하므로, 여기서는 "여러 경쟁 주문이 같은 호가를
       두고 어떤 순서로 소진할지"만 정렬한다. */
    @Query("SELECT o FROM Order o WHERE o.stockCode = :stockCode " +
            "AND o.orderType = stockOrder.stockTrade.order.OrderType.BUY " +
            "AND o.orderStatus IN :orderStatus " +
            "ORDER BY o.price DESC, o.createdAt ASC")
    List<Order> findPendingBuyOrders(
            @Param("stockCode") String stockCode,
            @Param("orderStatus") List<OrderStatus> orderStatuses
    );

    /* 매도 대기 주문 - 가격 우선(지정가가 낮을수록 더 공격적인 주문이므로 먼저), 동일 가격이면 시간 우선(선착순) */
    @Query("SELECT o FROM Order o WHERE o.stockCode = :stockCode " +
            "AND o.orderType = stockOrder.stockTrade.order.OrderType.SELL " +
            "AND o.orderStatus IN :orderStatus " +
            "ORDER BY o.price ASC, o.createdAt ASC")
    List<Order> findPendingSellOrders(
            @Param("stockCode") String stockCode,
            @Param("orderStatus") List<OrderStatus> orderStatuses
    );

    @Query("SELECT o FROM Order o WHERE o.memberId = :memberId " +
            "ORDER BY o.createdAt DESC" )
    List<Order> findByMemberOrderHistory(
            @Param("memberId") String memberId
    );

    /* 관리자 화면: 아직 안 풀린(대기/부분체결) 주문 전체 - 왜 안 풀렸는지(lastUnmatchedReason) 확인용 */
    @Query("SELECT o FROM Order o WHERE o.orderStatus IN :orderStatus ORDER BY o.lastAttemptAt DESC NULLS LAST, o.createdAt DESC")
    List<Order> findByOrderStatusIn(@Param("orderStatus") List<OrderStatus> orderStatus);

    /* 이미 대기 중인 매도주문들의 미체결(예약) 수량 합 - 매도 주문 생성/체결 시 실보유수량과 비교해 초과매도를 막는 데 씀 */
    @Query("SELECT COALESCE(SUM(o.quantity - o.matchedQuantity), 0) FROM Order o " +
            "WHERE o.memberId = :memberId AND o.stockCode = :stockCode " +
            "AND o.orderType = stockOrder.stockTrade.order.OrderType.SELL " +
            "AND o.orderStatus IN (stockOrder.stockTrade.order.OrderStatus.PENDING, stockOrder.stockTrade.order.OrderStatus.PARTIAL)")
    long sumReservedSellQuantity(@Param("memberId") String memberId, @Param("stockCode") String stockCode);
}
