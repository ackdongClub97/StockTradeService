package stockOrder.stockTrade.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
/* 핫패스 인덱스 (근거와 측정: docs/db-design.md)
   - status_stock: 30초 배치의 대기 종목 조회(DISTINCT stock_code WHERE status IN), 파티션 할당 시 대기 주문 로드,
     매도 접수 시 예약 수량 합계(대기 주문이 전체의 소수라 status+stock으로 몇 행만 남음)
   - member_created: 회원 주문 내역(최신순) */
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_status_stock", columnList = "orderStatus, stockCode"),
        @Index(name = "idx_orders_member_created", columnList = "memberId, createdAt")
})
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

    // 낙관적 락 - 체결(MatchingService)과 사용자 취소(OrderCommandService.cancel)가 같은 주문을 동시에 바꾸면
    // 나중에 저장하는 쪽이 실패하고 롤백된다("체결도 되고 환급도 된" 상태 방지).
    // default 0: ddl-auto update로 컬럼이 추가될 때 기존 행이 null이면 Spring Data가 새 엔티티로 오인해서 INSERT를 시도함
    @Version
    @Column(columnDefinition = "bigint default 0")
    private Long version;

    @Enumerated(EnumType.STRING)
    private UnmatchReason lastUnmatchedReason; // 가장 최근 매칭 시도에서 (더 이상) 체결되지 않은 이유 - 관리자 화면용
    private LocalDateTime lastAttemptAt;       // 가장 최근 매칭 시도 시각

}
