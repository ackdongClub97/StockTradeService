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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long orderId;

    private String memberId;

    private String stockCode;

    private String stockName;

    @Enumerated(EnumType.STRING)
    private OrderType orderType;
    @Enumerated(EnumType.STRING)
    private OrderStatus orderStatus;
    @Enumerated(EnumType.STRING)
    private PriceMode priceMode; // 현재가(MARKET)/지정가(LIMIT)

    // 지정가 매수의 5% 자동 가격개선 밴드 사용 여부 - 화면 체크박스 상태를 그대로 저장.
    // LIMIT 매수여도 이 값이 false면 밴드 없이 지정가 이하 최우선호가로만 체결한다(MARKET과 동일).
    @Column(columnDefinition = "boolean default false")
    private boolean autoPriceImprovement;

    private int price;

    private int matchedPrice;        // 가장 최근 체결가

    private int quantity;               // 주문 수량

    private int matchedQuantity;        // 누적 체결 수량 (Trade row들의 합과 일치해야 함)

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    private UnmatchReason lastUnmatchedReason; // 가장 최근 매칭 시도에서 (더 이상) 체결되지 않은 이유 - 관리자 화면용
    private LocalDateTime lastAttemptAt;       // 가장 최근 매칭 시도 시각

}
