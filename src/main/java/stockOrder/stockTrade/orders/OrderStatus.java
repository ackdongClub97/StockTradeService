package stockOrder.stockTrade.orders;

public enum OrderStatus {
    PENDING,    // 대기
    MATCHED,    // 체결 완료
    PARTIAL,    // 부분 체결
    CANCELLED,  // 취소
    FAILED      // 실패
}
