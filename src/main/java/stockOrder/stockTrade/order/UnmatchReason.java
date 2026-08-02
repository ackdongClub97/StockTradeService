package stockOrder.stockTrade.order;

public enum UnmatchReason {
    NO_ELIGIBLE_PRICE,   // 호가가 지정가/조건에 안 맞아서 체결 안 됨 - 정상적인 시장 상황
    NO_ORDERBOOK_DATA,   // 그 시점에 호가 데이터 자체가 캐시에 없었음 - 웹소켓 연결/구독 문제
    SYSTEM_ERROR         // 매칭 시도 중 예외 발생 - DB 커넥션 등 시스템 문제(대량 접속/주문 시 주로 발생)
}
