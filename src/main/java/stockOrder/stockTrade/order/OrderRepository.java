package stockOrder.stockTrade.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    List<Order> findByMemberId(String memberId);

    List<Order> findByOrderStatus(String memberId, OrderStatus orderStatus);

    List<Order> findByStockCode(String stockCode);
}
