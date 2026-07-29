package stockOrder.stockTrade.order;

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
    private String orderId; // ORD20260519001

    private String memberId;

    private String stockCode;

    private String stockName;

    @Enumerated(EnumType.STRING)
    private OrderType orderType;
    @Enumerated(EnumType.STRING)
    private OrderStatus orderStatus;

    private int price;

    private int matchedPrice;        // 가장 최근 체결가

    private int quantity;               // 주문 수량

    private int matchedQuantity;        // 누적 체결 수량 (Trade row들의 합과 일치해야 함)

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
