package stockOrder.stockTrade.fds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import stockOrder.stockTrade.admin.AdminAlertService;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.kis.ResponseOutputDTO;
import stockOrder.stockTrade.order.Order;
import stockOrder.stockTrade.order.OrderType;
import stockOrder.stockTrade.order.PriceMode;
import stockOrder.stockTrade.order.Trade;
import stockOrder.stockTrade.order.TradeRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/* FDS 1차 버전(규칙 기반) 재현 테스트 - "가설 검증" 단계에 해당.
   실제 DB/KIS 연동 없이 Mockito로 의존성을 대체한 순수 유닛 테스트. */
@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    private static final String MEMBER_ID = "member1";
    private static final String STOCK_CODE = "005930";

    @Mock private TradeRepository tradeRepository;
    @Mock private KisService kisService;
    @Mock private AdminAlertService adminAlertService;

    private FraudDetectionService fraudDetectionService;

    @BeforeEach
    void setUp() {
        fraudDetectionService = new FraudDetectionService(tradeRepository, kisService, adminAlertService);
        ReflectionTestUtils.setField(fraudDetectionService, "enabled", true);
        ReflectionTestUtils.setField(fraudDetectionService, "frequencyWindowSeconds", 60);
        ReflectionTestUtils.setField(fraudDetectionService, "frequencyThreshold", 10);
        ReflectionTestUtils.setField(fraudDetectionService, "roundTripWindowSeconds", 60);
        ReflectionTestUtils.setField(fraudDetectionService, "priceOutlierThresholdPercent", 20.0);
    }

    private Order newOrder(OrderType type, PriceMode mode, int price) {
        Order order = new Order();
        order.setOrderId(1L);
        order.setMemberId(MEMBER_ID);
        order.setStockCode(STOCK_CODE);
        order.setStockName("삼성전자");
        order.setOrderType(type);
        order.setPriceMode(mode);
        order.setPrice(price);
        return order;
    }

    @Test
    @DisplayName("빈도 임계값(10건) 미만이면 알림이 발생하지 않는다")
    void noAlertWhenFrequencyBelowThreshold() {
        Order order = newOrder(OrderType.BUY, PriceMode.MARKET, 70000);

        for (int i = 0; i < 9; i++) {
            fraudDetectionService.checkOnSubmit(order);
        }

        verify(adminAlertService, never()).publish(any());
    }

    @Test
    @DisplayName("60초 내 10건째 주문이 들어오면 주문 빈도 이상 알림이 발생한다")
    void detectsOrderFrequencyAnomaly() {
        Order order = newOrder(OrderType.BUY, PriceMode.MARKET, 70000);

        for (int i = 0; i < 9; i++) {
            fraudDetectionService.checkOnSubmit(order);
        }
        verify(adminAlertService, never()).publish(any());

        fraudDetectionService.checkOnSubmit(order); // 10번째 주문
        verify(adminAlertService, times(1)).publish(any());
    }

    @Test
    @DisplayName("지정가가 현재가 대비 20% 이상 벗어나면 가격 이상치 알림이 발생한다")
    void detectsPriceOutlier() {
        Order order = newOrder(OrderType.BUY, PriceMode.LIMIT, 100000);
        ResponseOutputDTO dto = new ResponseOutputDTO();
        dto.setStckPrpr("70000"); // |100000-70000|/70000*100 ≈ 42.9% > 20%
        when(kisService.getCachedStockData(STOCK_CODE)).thenReturn(dto);

        fraudDetectionService.checkOnSubmit(order);

        verify(adminAlertService, times(1)).publish(any());
    }

    @Test
    @DisplayName("지정가가 현재가와 비슷하면(20% 미만) 가격 이상치 알림이 발생하지 않는다")
    void noAlertWhenPriceWithinRange() {
        Order order = newOrder(OrderType.BUY, PriceMode.LIMIT, 71000);
        ResponseOutputDTO dto = new ResponseOutputDTO();
        dto.setStckPrpr("70000"); // ≈ 1.4%
        when(kisService.getCachedStockData(STOCK_CODE)).thenReturn(dto);

        fraudDetectionService.checkOnSubmit(order);

        verify(adminAlertService, never()).publish(any());
    }

    @Test
    @DisplayName("시장가 주문은 가격 이상치 규칙 대상이 아니다")
    void marketOrderSkipsPriceOutlierCheck() {
        Order order = newOrder(OrderType.BUY, PriceMode.MARKET, 1000000); // 현재가와 무관하게 시장가라 대상 제외

        fraudDetectionService.checkOnSubmit(order);

        verify(kisService, never()).getCachedStockData(any());
        verify(adminAlertService, never()).publish(any());
    }

    @Test
    @DisplayName("60초 내 반대 방향 체결 이력이 있으면 왕복매매 알림이 발생한다")
    void detectsRoundTrip() {
        Order sellOrder = newOrder(OrderType.SELL, PriceMode.MARKET, 70000);
        Trade recentBuyTrade = new Trade();
        recentBuyTrade.setOrderType(OrderType.BUY);

        when(tradeRepository.findByMemberIdAndStockCodeAndOrderTypeAndMatchedAtAfter(
                eq(MEMBER_ID), eq(STOCK_CODE), eq(OrderType.BUY), any(LocalDateTime.class)))
                .thenReturn(List.of(recentBuyTrade));

        fraudDetectionService.checkOnExecute(sellOrder);

        verify(adminAlertService, times(1)).publish(any());
    }

    @Test
    @DisplayName("반대 방향 체결 이력이 없으면 왕복매매 알림이 발생하지 않는다")
    void noAlertWhenNoOppositeTrade() {
        Order sellOrder = newOrder(OrderType.SELL, PriceMode.MARKET, 70000);

        when(tradeRepository.findByMemberIdAndStockCodeAndOrderTypeAndMatchedAtAfter(
                eq(MEMBER_ID), eq(STOCK_CODE), eq(OrderType.BUY), any(LocalDateTime.class)))
                .thenReturn(List.of());

        fraudDetectionService.checkOnExecute(sellOrder);

        verify(adminAlertService, never()).publish(any());
    }

    @Test
    @DisplayName("fds.enabled=false면 모든 규칙을 건너뛴다 (롤백 스위치)")
    void disabledFlagSkipsAllRules() {
        ReflectionTestUtils.setField(fraudDetectionService, "enabled", false);
        Order order = newOrder(OrderType.BUY, PriceMode.LIMIT, 1000000);

        for (int i = 0; i < 20; i++) {
            fraudDetectionService.checkOnSubmit(order);
        }
        fraudDetectionService.checkOnExecute(order);

        verify(adminAlertService, never()).publish(any());
    }
}
