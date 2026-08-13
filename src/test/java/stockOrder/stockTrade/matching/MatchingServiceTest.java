package stockOrder.stockTrade.matching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import stockOrder.stockTrade.admin.AdminAlertService;
import stockOrder.stockTrade.fds.FraudDetectionService;
import stockOrder.stockTrade.kis.AskingPriceDTO;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.kis.KisWebSocketService;
import stockOrder.stockTrade.member.Member;
import stockOrder.stockTrade.member.MemberRepository;
import stockOrder.stockTrade.order.Order;
import stockOrder.stockTrade.order.OrderRepository;
import stockOrder.stockTrade.order.OrderStatus;
import stockOrder.stockTrade.order.OrderType;
import stockOrder.stockTrade.order.PriceMode;
import stockOrder.stockTrade.order.RealizedPnlService;
import stockOrder.stockTrade.order.Trade;
import stockOrder.stockTrade.order.TradeRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/* 호가 기반 부분체결 매칭 로직(MatchingService.tryMatch → execute) 검증.
   실제 DB/KIS 연동 없이 Mockito로 의존성을 대체한 순수 유닛 테스트. */
@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

    private static final Long ORDER_ID = 1L;
    private static final String MEMBER_ID = "member1";
    private static final String STOCK_CODE = "005930";

    @Mock private OrderRepository orderRepository;
    @Mock private TradeRepository tradeRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private KisService kisService;
    @Mock private KisWebSocketService kisWebSocketService;
    @Mock private RealizedPnlService realizedPnlService;
    @Mock private AdminAlertService adminAlertService;
    @Mock private FraudDetectionService fraudDetectionService;

    @InjectMocks
    private MatchingService matchingService;

    @BeforeEach
    void setUp() {
        when(kisService.isMarketOpen()).thenReturn(true);
    }

    private Order newBuyOrder(int quantity, int price) {
        Order order = new Order();
        order.setOrderId(ORDER_ID);
        order.setMemberId(MEMBER_ID);
        order.setStockCode(STOCK_CODE);
        order.setStockName("삼성전자");
        order.setOrderType(OrderType.BUY);
        order.setOrderStatus(OrderStatus.PENDING);
        order.setPriceMode(PriceMode.LIMIT); // 이 테스트들은 모두 지정가 매수 시나리오
        order.setPrice(price);
        order.setQuantity(quantity);
        order.setMatchedQuantity(0);
        order.setCreatedAt(LocalDateTime.now());
        return order;
    }

    private Member newMember() {
        Member member = new Member();
        member.setMemberId(MEMBER_ID);
        member.setSeed(10_000_000);
        return member;
    }

    /* 매도호가 1단계(index 0)만 값을 채우고 나머지 9단계는 빈 호가(0)인 10단계 스냅샷 생성 */
    private AskingPriceDTO askingPriceWith(String askPrice, String askVolume) {
        AskingPriceDTO dto = new AskingPriceDTO();
        dto.setStockCode(STOCK_CODE);

        String[] askPrices = new String[10];
        String[] askVolumes = new String[10];
        String[] bidPrices = new String[10];
        String[] bidVolumes = new String[10];
        for (int i = 0; i < 10; i++) {
            askPrices[i] = "0";
            askVolumes[i] = "0";
            bidPrices[i] = "0";
            bidVolumes[i] = "0";
        }
        askPrices[0] = askPrice;
        askVolumes[0] = askVolume;

        dto.setAskPrices(askPrices);
        dto.setAskVolumes(askVolumes);
        dto.setBidPrices(bidPrices);
        dto.setBidVolumes(bidVolumes);
        dto.setTotalAskVolume(askVolume);
        dto.setTotalBidVolume("0");
        return dto;
    }

    @Test
    @DisplayName("1. 부분체결 성공 - 두 번에 나눠 체결되어도 결국 전량 체결(MATCHED)된다")
    void 부분체결_후_잔량이_모두_체결되면_MATCHED_상태가_된다() {
        Order order = newBuyOrder(10, 50_000);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(memberRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(newMember()));

        // 1차 시도: 지정가(50,000) 이하 매도호가 잔량이 4주뿐 -> 4주만 체결, 6주는 미체결로 남음
        when(kisWebSocketService.getCachedAskingPrice(STOCK_CODE))
                .thenReturn(askingPriceWith("49000", "4"));

        matchingService.tryMatch(order);

        assertEquals(4, order.getMatchedQuantity(), "1차 체결 후 누적 체결수량은 4주여야 한다");
        assertEquals(OrderStatus.PARTIAL, order.getOrderStatus(), "전량 체결 전이므로 PARTIAL 상태여야 한다");

        // 2차 시도(다음 배치 주기): 남은 6주만큼 호가 물량이 들어와서 잔량이 전부 체결됨
        when(kisWebSocketService.getCachedAskingPrice(STOCK_CODE))
                .thenReturn(askingPriceWith("49500", "6"));

        matchingService.tryMatch(order);

        assertEquals(10, order.getMatchedQuantity(), "2차 체결 후 누적 체결수량은 주문수량 전체(10주)여야 한다");
        assertEquals(OrderStatus.MATCHED, order.getOrderStatus(), "전량 체결됐으므로 MATCHED 상태여야 한다");

        ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepository, times(2)).save(tradeCaptor.capture());

        List<Trade> trades = tradeCaptor.getAllValues();
        assertEquals(2, trades.size(), "체결이 두 번 나뉘어 일어났으니 Trade도 2건 insert돼야 한다");
        assertEquals(4, trades.get(0).getMatchedQuantity());
        assertEquals(49000, trades.get(0).getMatchedPrice());
        assertEquals(6, trades.get(1).getMatchedQuantity());
        assertEquals(49500, trades.get(1).getMatchedPrice());

        int totalFilled = trades.stream().mapToInt(Trade::getMatchedQuantity).sum();
        assertEquals(order.getQuantity(), totalFilled, "Trade 수량 합은 원래 주문수량과 같아야 한다");
    }

    @Test
    @DisplayName("2. 부분체결 일부 실패 - 호가 잔량 부족 시 그만큼만 체결되고 나머지는 미체결(PARTIAL)로 남는다")
    void 호가_잔량이_부족하면_일부만_체결되고_나머지는_미체결로_남는다() {
        Order order = newBuyOrder(10, 50_000);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(memberRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(newMember()));

        // 지정가(50,000) 이하 매도호가 잔량이 3주뿐 -> 10주 중 3주만 체결 가능
        when(kisWebSocketService.getCachedAskingPrice(STOCK_CODE))
                .thenReturn(askingPriceWith("48000", "3"));

        matchingService.tryMatch(order);

        assertEquals(3, order.getMatchedQuantity(), "호가 잔량(3주)만큼만 체결돼야 한다");
        assertEquals(7, order.getQuantity() - order.getMatchedQuantity(), "나머지 7주는 미체결 상태로 남아야 한다");
        assertEquals(OrderStatus.PARTIAL, order.getOrderStatus());
        assertNotEquals(OrderStatus.MATCHED, order.getOrderStatus(), "전량 체결이 아니므로 MATCHED이면 안 된다");
        assertNotEquals(OrderStatus.FAILED, order.getOrderStatus(), "일부라도 체결됐으니 FAILED로 처리되면 안 된다");

        verify(tradeRepository, times(1)).save(any(Trade.class));

        // 더 이상 체결 가능한 호가가 없는 상황(잔량 0) -> 재시도해도 추가 체결 없이 PARTIAL 그대로 유지
        when(kisWebSocketService.getCachedAskingPrice(STOCK_CODE))
                .thenReturn(askingPriceWith("0", "0"));

        matchingService.tryMatch(order);

        assertEquals(3, order.getMatchedQuantity(), "체결 가능한 호가가 없으면 누적 체결수량이 그대로여야 한다");
        assertEquals(OrderStatus.PARTIAL, order.getOrderStatus());
        verify(tradeRepository, times(1)).save(any(Trade.class)); // 추가 Trade 없이 여전히 1번만 호출됨
    }

    /* 매도호가 두 단계를 서로 다른 가격으로 채운 10단계 스냅샷 생성 */
    private AskingPriceDTO askingPriceWithTwoLevels(String price0, String vol0, String price1, String vol1) {
        AskingPriceDTO dto = askingPriceWith(price0, vol0);
        dto.getAskPrices()[1] = price1;
        dto.getAskVolumes()[1] = vol1;
        return dto;
    }

    @Test
    @DisplayName("3. 매수 지정가 자동 가격개선 - 지정가 대비 5% 이내로 낮은 호가는 자동 체결되지만, 5%를 초과해서 낮은 호가는 체결 대상에서 제외된다")
    void 지정가_매수는_지정가_대비_5퍼센트_이내의_저렴한_호가로만_자동_체결된다() {
        // 지정가 100,000원 매수 10주. 매도호가 1단계는 96,000원(4% 저렴, 밴드 내) 5주, 2단계는 90,000원(10% 저렴, 밴드 밖) 5주.
        Order order = newBuyOrder(10, 100_000);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(memberRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(newMember()));

        when(kisWebSocketService.getCachedAskingPrice(STOCK_CODE))
                .thenReturn(askingPriceWithTwoLevels("96000", "5", "90000", "5"));

        matchingService.tryMatch(order);

        assertEquals(5, order.getMatchedQuantity(), "밴드(지정가의 95~100%) 안에 있는 96,000원 호가 물량(5주)만 체결돼야 한다");
        assertEquals(OrderStatus.PARTIAL, order.getOrderStatus(), "밴드 밖의 90,000원 호가 5주는 체결되지 않아 PARTIAL로 남아야 한다");

        ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepository, times(1)).save(tradeCaptor.capture());
        assertEquals(96000, tradeCaptor.getValue().getMatchedPrice(), "체결가는 밴드 안의 유일한 호가인 96,000원이어야 한다");
    }

    @Test
    @DisplayName("4. 현재가(시장가) 매수는 5% 밴드 제한 없이 지정가 이하 호가면 전부 체결 대상이다")
    void 현재가_매수는_5퍼센트_밴드_제한을_받지_않는다() {
        // 현재가 매수 100,000원 10주. 매도호가 1단계는 96,000원(밴드 내) 5주, 2단계는 90,000원(밴드 밖) 5주 - 지정가 매수라면 90,000원은 체결 안 되지만, 현재가 매수는 밴드가 없으므로 둘 다 체결돼야 한다.
        Order order = newBuyOrder(10, 100_000);
        order.setPriceMode(PriceMode.MARKET);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(memberRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(newMember()));

        when(kisWebSocketService.getCachedAskingPrice(STOCK_CODE))
                .thenReturn(askingPriceWithTwoLevels("96000", "5", "90000", "5"));

        matchingService.tryMatch(order);

        assertEquals(10, order.getMatchedQuantity(), "현재가 매수는 밴드 제한이 없으므로 두 호가 물량(5+5주) 모두 체결돼야 한다");
        assertEquals(OrderStatus.MATCHED, order.getOrderStatus());
    }

    @Test
    @DisplayName("5. OrderBook 가격 우선순위 - 매수는 지정가가 높을수록(더 공격적일수록) 먼저 체결된다, 늦게 낸 주문이라도")
    void OrderBook은_지정가가_높은_매수주문을_시간과_무관하게_먼저_체결시킨다() {
        // 먼저 생성됐지만 지정가가 낮은(덜 공격적인) 주문
        Order lowPriceOrder = newBuyOrder(10, 50_000);
        lowPriceOrder.setOrderId(1L);
        // 나중에 생성됐지만 지정가가 더 높은(더 공격적인) 주문 - price-time priority상 이쪽이 먼저 체결돼야 함
        Order highPriceOrder = newBuyOrder(10, 51_000);
        highPriceOrder.setOrderId(2L);
        highPriceOrder.setCreatedAt(lowPriceOrder.getCreatedAt().plusSeconds(1));

        // registerOrder 호출 순서도 낮은 가격 먼저 - "먼저 큐에 들어갔다"는 사실 자체가 우선순위에 영향을 주면 안 됨을 같이 검증
        matchingService.registerOrder(lowPriceOrder);
        matchingService.registerOrder(highPriceOrder);

        when(orderRepository.findPendingStockList(any())).thenReturn(List.of(STOCK_CODE));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(lowPriceOrder));
        when(orderRepository.findById(2L)).thenReturn(Optional.of(highPriceOrder));
        when(memberRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(newMember()));
        // 매도호가 물량이 5주뿐이라 두 주문(각 10주)이 경쟁하는 상황 - 둘 다 이 가격대엔 체결 자격이 있음(5% 밴드 안)
        when(kisWebSocketService.getCachedAskingPrice(STOCK_CODE))
                .thenReturn(askingPriceWith("50000", "5"));

        matchingService.matching();

        assertEquals(5, highPriceOrder.getMatchedQuantity(), "지정가가 더 높은(더 공격적인) 주문이 먼저 물량 5주를 가져가야 한다");
        assertEquals(0, lowPriceOrder.getMatchedQuantity(), "지정가가 낮은 주문은 이번 회차 물량이 이미 소진돼서 체결되면 안 된다");
        assertEquals(OrderStatus.PENDING, lowPriceOrder.getOrderStatus(), "체결 못 한 주문은 그대로 PENDING이어야 한다");
    }
}
