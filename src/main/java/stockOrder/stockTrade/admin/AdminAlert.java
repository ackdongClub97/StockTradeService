package stockOrder.stockTrade.admin;

import java.time.LocalDateTime;

/* 매칭 중 SYSTEM_ERROR(DB 커넥션 등)가 발생했을 때 관리자 화면에 실시간으로 띄우는 알림 */
public record AdminAlert(String orderId, String stockCode, String message, LocalDateTime occurredAt) {
}
