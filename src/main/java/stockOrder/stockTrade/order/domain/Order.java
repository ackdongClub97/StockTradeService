package stockOrder.stockTrade.order.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue
    private String orderId; // ORD20260519001

    private String memberId;

    private String stockCode;

    private String stockName;

    private OrderType orderType;

    private OrderStatus orderStatus;

    private int price;

    private int quantity;               // 주문 수량

    private int matchedQuantity;        // 체결 수량

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
