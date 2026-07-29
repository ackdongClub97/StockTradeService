package stockOrder.stockTrade.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    /* 대기 종목 코드 목록 */
    @Query("SELECT DISTINCT o.stockCode FROM Order o WHERE o.orderStatus IN :orderStatus")
    List<String> findPendingStockList(@Param("orderStatus") List<OrderStatus> orderStatus);

    /* 대기 주문 - 가격 조건은 호가 레벨별로 Java에서 판단하므로 여기서는 종목/구분/상태만 거른다. 시간 오름차순(선착순 우선) */
    @Query("SELECT o FROM Order o WHERE o.stockCode = :stockCode " +
            "AND o.orderType = :orderType " +
            "AND o.orderStatus IN :orderStatus " +
            "ORDER BY o.createdAt ASC")
    List<Order> findPendingByStockCodeAndType(
            @Param("stockCode") String stockCode,
            @Param("orderType") OrderType orderType,
            @Param("orderStatus") List<OrderStatus> orderStatuses
    );

    @Query("SELECT o FROM Order o WHERE o.memberId = :memberId " +
            "ORDER BY o.createdAt DESC" )
    List<Order> findByMemberOrderHistory(
            @Param("memberId") String memberId
    );

    @Query("SELECT MAX(o.orderId) FROM Order o WHERE o.orderId LIKE :prefix%")
    String findMaxOrderIdByPrefix(@Param("prefix") String prefix);

    /* 이미 대기 중인 매도주문들의 미체결(예약) 수량 합 - 매도 주문 생성/체결 시 실보유수량과 비교해 초과매도를 막는 데 씀 */
    @Query("SELECT COALESCE(SUM(o.quantity - o.matchedQuantity), 0) FROM Order o " +
            "WHERE o.memberId = :memberId AND o.stockCode = :stockCode " +
            "AND o.orderType = stockOrder.stockTrade.order.OrderType.SELL " +
            "AND o.orderStatus IN (stockOrder.stockTrade.order.OrderStatus.PENDING, stockOrder.stockTrade.order.OrderStatus.PARTIAL)")
    long sumReservedSellQuantity(@Param("memberId") String memberId, @Param("stockCode") String stockCode);
}
