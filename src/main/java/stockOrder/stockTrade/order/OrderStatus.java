package stockOrder.stockTrade.order;

public enum OrderStatus {
    PENDING,        // 대기
    MATCHED,        // 체결
    PARTIAL,        // 부분 체결
    CANCELLED,      // 주문 취소
    FAILED          // 주문 실패
}
