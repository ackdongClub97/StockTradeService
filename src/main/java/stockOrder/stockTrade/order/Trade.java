package stockOrder.stockTrade.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/* 체결 원장 - 체결이 일어날 때마다 INSERT만 함(UPDATE 없음). 한 주문(Order)이 여러 번 나눠 체결되면 여러 row로 쌓인다. */
@Entity
@Getter
@Setter
@Table(name = "trades")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long tradeId;

    private String orderId;

    private String memberId;

    private String stockCode;

    private String stockName;

    @Enumerated(EnumType.STRING)
    private OrderType orderType;

    private int matchedPrice;

    private int matchedQuantity;

    private LocalDateTime matchedAt;
}
