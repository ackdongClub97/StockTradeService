package stockOrder.stockTrade.order;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    /* 대기 종목 코드 목록 */
    @Query("SELECT DISTINCT o.stockCode FROM Order o WHERE o.orderStatus IN :orderStatus")
    List<String> findPendingStockList(@Param("orderStatus") List<OrderStatus> orderStatus);

    /* 매수 대기 주문 - 지정가 >= 현재가, 시간 오름차순 (선착순 진행) */
    @Query("SELECT o FROM Order o WHERE o.stockCode = :stockCode " +
            "AND o.orderType = :orderType " +
            "AND o.orderStatus IN :orderStatus " +
            "AND o.price >= :currentPrice " +
            "ORDER BY o.createdAt ASC")
    List<Order> findPendingStockCodeBuyOrderStatus(
            @Param("stockCode") String stockCode,
            @Param("orderType") OrderType orderType,
            @Param("orderStatus") List<OrderStatus> orderStatuses,
            @Param("currentPrice") int currentPrice
    );

    /* 매수 대기 주문 - 지정가 <= 현재가, 시간 오름차순 (선착순 진행) */
    @Query("SELECT o FROM Order o WHERE o.stockCode = :stockCode " +
            "AND o.orderType = :orderType " +
            "AND o.orderStatus IN :orderStatus " +
            "AND o.price <= :currentPrice " +
            "ORDER BY o.createdAt ASC")
    List<Order> findPendingStockCodeSellOrderStatus(
            @Param("stockCode") String stockCode,
            @Param("orderType") OrderType orderType,
            @Param("orderStatus") List<OrderStatus> orderStatuses,
            @Param("currentPrice") int currentPrice
    );

    @Query(value = "SELECT A.MEMBER_ID, A.STOCK_CODE, A.STOCK_NAME, IFNULL(A.PRICE, 0) AS BUY_PIRCE, " +
                    "IFNULL(B.PRICE, 0) AS SELL_PRICE, IFNULL(A.QUANTITY, 0) AS BUY_QUANTITY, IFNULL(B.QUANTITY, 0) AS SELL_QUANTITY, " +
                    "CASE WHEN (IFNULL(B.QUANTITY, 0) - IFNULL(A.QUANTITY, 0)) = 0 THEN 0 " +
                    "ELSE (IFNULL(B.PRICE, 0) - IFNULL(A.PRICE, 0)) / (IFNULL(B.QUANTITY, 0) - IFNULL(A.QUANTITY, 0)) END AS AVG_PRICE " +
            "FROM ( SELECT O.MEMBER_ID, O.STOCK_CODE, O.STOCK_NAME, SUM(O.MATCHED_PRICE * O.MATCHED_QUANTITY) AS PRICE, SUM(O.MATCHED_QUANTITY) AS QUANTITY " +
                    "FROM ORDERS O WHERE O.ORDER_STATUS = 'MATCHED' AND O.ORDER_TYPE = 'BUY' " +
                    "GROUP BY O.STOCK_CODE) A " +
            "LEFT OUTER JOIN ( SELECT O.MEMBER_ID, O.STOCK_CODE, O.STOCK_NAME, SUM(O.MATCHED_PRICE * O.MATCHED_QUANTITY) AS PRICE, SUM(O.MATCHED_QUANTITY) AS QUANTITY " +
                               "FROM ORDERS O WHERE O.ORDER_STATUS = 'MATCHED' AND O.ORDER_TYPE = 'SELL' " +
                               "GROUP BY O.STOCK_CODE) B " +
                "ON A.STOCK_CODE = B.STOCK_CODE AND A.MEMBER_ID = B.MEMBER_ID " +
            "WHERE A.MEMBER_ID = :memberId",
    nativeQuery = true)
    List<HoldingOrderDTO> findByMemberOrderList(
            @Param("memberId") String memberId
    );


    @Query(value = "SELECT A.MEMBER_ID, A.STOCK_CODE, A.STOCK_NAME, IFNULL(A.PRICE, 0) AS BUY_PIRCE, IFNULL(A.QUANTITY, 0) AS BUY_QUANTITY, " +
            "IFNULL(B.PRICE, 0) AS SELL_PIRCE, IFNULL(B.QUANTITY, 0) AS SELL_QUANTITY " +
            "FROM ( SELECT O.MEMBER_ID, O.STOCK_CODE, O.STOCK_NAME, SUM(O.MATCHED_PRICE * O.MATCHED_QUANTITY) AS PRICE, " +
            "SUM(O.MATCHED_QUANTITY) AS QUANTITY " +
            "FROM ORDERS O WHERE O.ORDER_STATUS = 'MATCHED' AND O.ORDER_TYPE = 'BUY' " +
            "GROUP BY O.STOCK_CODE) A " +
            "LEFT OUTER JOIN ( SELECT O.MEMBER_ID, O.STOCK_CODE, O.STOCK_NAME, SUM(O.MATCHED_PRICE * O.MATCHED_QUANTITY) AS PRICE, " +
            "SUM(O.MATCHED_QUANTITY) AS QUANTITY " +
            "FROM ORDERS O WHERE O.ORDER_STATUS IN ('MATCHED', 'PENDING') AND O.ORDER_TYPE = 'SELL' " +
            "GROUP BY O.STOCK_CODE) B " +
            "ON A.STOCK_CODE = B.STOCK_CODE AND A.MEMBER_ID = B.MEMBER_ID " +
            "WHERE A.MEMBER_ID = :memberId AND A.STOCK_CODE = :stockCode",
            nativeQuery = true)
    List<Map<String, Object>> findByMemberStockInfo(
            @Param("memberId") String memberId,
            @Param("stockCode") String stockCode
    );

    @Query("SELECT o FROM Order o WHERE o.memberId = :memberId " +
            "ORDER BY o.createdAt DESC" )
    List<Order> findByMemberOrderHistory(
            @Param("memberId") String memberId
    );

    @Query("SELECT MAX(o.orderId) FROM Order o WHERE o.orderId LIKE :prefix%")
    String findMaxOrderIdByPrefix(@Param("prefix") String prefix);
}
