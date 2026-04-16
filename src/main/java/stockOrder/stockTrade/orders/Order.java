package stockOrder.stockTrade.orders;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;

@Entity
@Getter @Setter
@Table(name="ORDER")
public class Order {

    @Id @GeneratedValue
    private String orderId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String stockCode;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private BigDecimal quantity;

    private BigDecimal matchingQuantity;

    @Column(nullable = false)
    private OrderType orderType;

    @Column(nullable = false)
    private OrderStatus orderStatus;


    private LocalDateTime createDt;
    private LocalDateTime tradeDt;

    public Order orderCreate(String userId, String stockCode,  BigDecimal price, BigDecimal quantity, OrderType orderType) {
        Order order = new Order();
        order.userId = userId;
        order.stockCode = stockCode;
        order.price = price;
        order.quantity = quantity;
        order.orderType = orderType;
        order.createDt = LocalDateTime.now();
        order.orderStatus = OrderStatus.PENDING; // 대기

        return order;
    }
}
