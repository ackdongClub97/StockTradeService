package stockOrder.stockTrade.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import stockOrder.stockTrade.order.domain.Order;
import stockOrder.stockTrade.order.domain.OrderStatus;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    List<Order> findByMemberId(String memberId);

    List<Order> findByOrderStatus(String memberId, OrderStatus orderStatus);

    List<Order> findByStockCode(String stockCode);
}
