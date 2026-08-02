package stockOrder.stockTrade.fds;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import stockOrder.stockTrade.admin.AdminAlert;
import stockOrder.stockTrade.admin.AdminAlertService;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.kis.ResponseOutputDTO;
import stockOrder.stockTrade.order.Order;
import stockOrder.stockTrade.order.OrderType;
import stockOrder.stockTrade.order.PriceMode;
import stockOrder.stockTrade.order.Trade;
import stockOrder.stockTrade.order.TradeRepository;

import java.time.LocalDateTime;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/* 규칙 기반 이상거래탐지(FDS) 1차 버전.
   차단은 하지 않고 로그(Loki) + 관리자 실시간 알림(AdminAlertService)만 한다 - 정상 주문 흐름을 건드리지 않는 게 최우선.
   fds.enabled로 즉시 끌 수 있게 해서, 오탐이 몰리면 코드 롤백 없이도 기능만 내릴 수 있는 구조로 둔다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class FraudDetectionService {

    private final TradeRepository tradeRepository;
    private final KisService kisService;
    private final AdminAlertService adminAlertService;

    @Value("${fds.enabled:true}")
    private boolean enabled;

    @Value("${fds.order-frequency.window-seconds:60}")
    private int frequencyWindowSeconds;

    @Value("${fds.order-frequency.threshold:10}")
    private int frequencyThreshold;

    @Value("${fds.round-trip.window-seconds:60}")
    private int roundTripWindowSeconds;

    @Value("${fds.price-outlier.threshold-percent:20}")
    private double priceOutlierThresholdPercent;

    /* 회원별 최근 주문 시각 슬라이딩 윈도우 - KisService의 ConcurrentHashMap 캐시 스타일을 그대로 차용 */
    private final Map<String, Deque<LocalDateTime>> recentOrderTimestamps = new ConcurrentHashMap<>();

    /* 주문 생성 시점(OrderController.submitOrder)에서 호출 - 빈도 이상, 가격 이상치 체크 */
    public void checkOnSubmit(Order order) {
        if (!enabled) return;
        try {
            checkOrderFrequency(order);
            checkPriceOutlier(order);
        } catch (Exception e) {
            // FDS 자체 오류가 주문 생성 흐름을 막으면 안 됨 - 탐지 실패는 조용히 로그만 남기고 넘어간다
            log.warn("[FDS] 주문 생성 시점 이상거래탐지 중 오류: {}", e.getMessage());
        }
    }

    /* 체결 시점(MatchingService.execute)에서 호출 - 초단타 왕복매매 체크 */
    public void checkOnExecute(Order order) {
        if (!enabled) return;
        try {
            checkRoundTrip(order);
        } catch (Exception e) {
            log.warn("[FDS] 체결 시점 이상거래탐지 중 오류: {}", e.getMessage());
        }
    }

    /* 규칙 1: 주문 빈도 이상 - 짧은 시간(window-seconds) 내 과도한 주문(threshold건 이상) */
    private void checkOrderFrequency(Order order) {
        LocalDateTime now = LocalDateTime.now();
        Deque<LocalDateTime> timestamps = recentOrderTimestamps.computeIfAbsent(
                order.getMemberId(), k -> new ConcurrentLinkedDeque<>());
        timestamps.addLast(now);

        LocalDateTime cutoff = now.minusSeconds(frequencyWindowSeconds);
        while (true) {
            LocalDateTime head = timestamps.peekFirst();
            if (head == null || !head.isBefore(cutoff)) break;
            timestamps.pollFirst();
        }

        int count = timestamps.size();
        if (count >= frequencyThreshold) {
            String message = String.format(
                    "주문 빈도 이상 - %s님이 %d초 동안 주문 %d건 (임계값 %d건)",
                    order.getMemberId(), frequencyWindowSeconds, count, frequencyThreshold);
            alert(order, message);
        }
    }

    /* 규칙 3: 가격 이상치 - 지정가 주문가가 현재가 대비 threshold-percent 이상 벗어남 (5% 자동 가격개선 밴드 악용 시도 등) */
    private void checkPriceOutlier(Order order) {
        if (order.getPriceMode() != PriceMode.LIMIT) return; // 시장가 주문은 대상 아님

        ResponseOutputDTO cached = kisService.getCachedStockData(order.getStockCode());
        if (cached == null || cached.getStckPrpr() == null) return; // 비교할 현재가가 없으면 판단 보류

        double currentPrice;
        try {
            currentPrice = Double.parseDouble(cached.getStckPrpr());
        } catch (NumberFormatException e) {
            return;
        }
        if (currentPrice <= 0) return;

        double deviationPercent = Math.abs(order.getPrice() - currentPrice) / currentPrice * 100;
        if (deviationPercent >= priceOutlierThresholdPercent) {
            String message = String.format(
                    "가격 이상치 - %s님의 %s 주문가 %d원이 현재가 %.0f원 대비 %.1f%% 이탈 (임계값 %.1f%%)",
                    order.getMemberId(), order.getStockName(), order.getPrice(), currentPrice,
                    deviationPercent, priceOutlierThresholdPercent);
            alert(order, message);
        }
    }

    /* 규칙 2: 초단타 왕복매매 - 같은 종목에서 반대 방향 체결이 window-seconds 내에 이미 있었는데 또 체결됨 */
    private void checkRoundTrip(Order order) {
        OrderType oppositeType = (order.getOrderType() == OrderType.BUY) ? OrderType.SELL : OrderType.BUY;
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(roundTripWindowSeconds);

        List<Trade> opposite = tradeRepository.findByMemberIdAndStockCodeAndOrderTypeAndMatchedAtAfter(
                order.getMemberId(), order.getStockCode(), oppositeType, cutoff);

        if (!opposite.isEmpty()) {
            String message = String.format(
                    "초단타 왕복매매 - %s님이 %s에서 %d초 내 %s <-> %s 반복 (최근 반대매매 %d건)",
                    order.getMemberId(), order.getStockName(), roundTripWindowSeconds,
                    oppositeType, order.getOrderType(), opposite.size());
            alert(order, message);
        }
    }

    private void alert(Order order, String message) {
        log.warn("[FDS] {}", message);
        adminAlertService.publish(new AdminAlert(order.getOrderId(), order.getStockCode(), message, LocalDateTime.now()));
    }
}
