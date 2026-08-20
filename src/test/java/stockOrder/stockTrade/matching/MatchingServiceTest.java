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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    @DisplayName("6. 같은 호가 틱 공유 - 즉시체결(tryMatch) 두 건이 같은 호가 스냅샷을 연달아 보면, 두 번째 건은 첫 번째가 소진한 잔량을 다시 못 먹는다")
    void 같은_호가_스냅샷을_보는_두_번의_즉시체결은_잔량을_나눠쓴다() {
        // 매도호가 잔량 5주뿐인 "한 장의 스냅샷"(같은 AskingPriceDTO 객체)을 두 주문이 순서대로 조회
        AskingPriceDTO sameTick = askingPriceWith("49000", "5");
        when(kisWebSocketService.getCachedAskingPrice(STOCK_CODE)).thenReturn(sameTick);
        when(memberRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(newMember()));

        Order first = newBuyOrder(10, 50_000);
        first.setOrderId(1L);
        Order second = newBuyOrder(10, 50_000);
        second.setOrderId(2L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(first));
        when(orderRepository.findById(2L)).thenReturn(Optional.of(second));

        matchingService.tryMatch(first);
        assertEquals(5, first.getMatchedQuantity(), "먼저 온 주문이 스냅샷에 표시된 물량(5주)을 전부 가져간다");

        matchingService.tryMatch(second);
        assertEquals(0, second.getMatchedQuantity(),
                "같은 틱(같은 스냅샷)에는 더 이상 잔량이 없으므로, 화면에 보였던 5주를 두 번째 주문이 중복으로 체결시키면 안 된다");
        assertEquals(OrderStatus.PENDING, second.getOrderStatus());

        // 새 웹소켓 틱이 도착(값은 같아도 KisWebSocketService가 매번 새 객체를 만들어 캐시에 넣음)하면 잔량이 리셋된다
        AskingPriceDTO nextTick = askingPriceWith("49000", "5");
        when(kisWebSocketService.getCachedAskingPrice(STOCK_CODE)).thenReturn(nextTick);

        matchingService.tryMatch(second);
        assertEquals(5, second.getMatchedQuantity(), "새 틱이 오면 그 틱 기준으로 다시 5주만큼 체결 가능해야 한다");
    }

    @Test
    @DisplayName("7. 동시성 - 같은 종목에 여러 사용자가 동시에 즉시체결을 시도해도, 그 순간 호가 틱의 잔량을 넘어서 체결되지 않는다")
    void 여러_사용자가_동시에_같은_종목을_체결_시도해도_한_틱의_호가_잔량을_넘지_않는다() throws InterruptedException {
        int userCount = 5;
        int askVolume = 5; // 5명이 각 10주씩 원해도, 이 틱에 실제로 나갈 수 있는 물량은 5주뿐
        AskingPriceDTO sharedTick = askingPriceWith("49000", String.valueOf(askVolume)); // 5명 모두 같은 스냅샷(같은 객체)을 봄

        when(kisWebSocketService.getCachedAskingPrice(STOCK_CODE)).thenReturn(sharedTick);
        when(memberRepository.findByMemberId(any())).thenAnswer(inv -> Optional.of(newMember()));

        List<Order> orders = new ArrayList<>();
        for (int i = 1; i <= userCount; i++) {
            Order order = newBuyOrder(10, 50_000);
            order.setOrderId((long) i);
            order.setMemberId("member" + i);
            orders.add(order);
            when(orderRepository.findById((long) i)).thenReturn(Optional.of(order));
        }

        ExecutorService pool = Executors.newFixedThreadPool(userCount);
        CountDownLatch ready = new CountDownLatch(userCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(userCount);

        for (Order order : orders) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    matchingService.tryMatch(order);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown(); // 5개 스레드가 최대한 같은 순간에 tryMatch를 호출하도록 동시에 풀어줌
        assertTrue(done.await(10, TimeUnit.SECONDS), "5개 체결 시도가 제한시간 안에 끝나야 한다");
        pool.shutdown();

        int totalFilled = orders.stream().mapToInt(Order::getMatchedQuantity).sum();
        assertEquals(askVolume, totalFilled,
                "5명이 동시에 주문해도, 그 순간 호가 스냅샷에 표시된 물량(5주)을 넘어서 체결되면 안 된다");

        long winners = orders.stream().filter(o -> o.getMatchedQuantity() > 0).count();
        assertEquals(1, winners, "잔량 5주는 한 번에 소진되므로, 동시에 시도한 5명 중 정확히 한 명만 체결됐어야 한다");
    }

    @Test
    @DisplayName("8. 취소 반영 - OrderBook에 등록된 후 배치가 실제로 꺼내 처리하기 직전에 취소된 주문은 체결되지 않고 건너뛴다")
    void 배치가_큐에서_꺼내기_직전에_취소된_주문은_체결되지_않는다() {
        // 지정가 50,000원(우선순위 높음, 먼저 poll됨) - 큐에 등록된 뒤 배치가 처리하기 직전에 취소됨
        Order cancelledOrder = newBuyOrder(10, 50_000);
        cancelledOrder.setOrderId(1L);
        // 지정가 49,000원(우선순위 낮음, 나중에 poll됨) - 끝까지 대기 상태 유지, 정상 체결돼야 함
        Order stillPendingOrder = newBuyOrder(10, 49_000);
        stillPendingOrder.setOrderId(2L);

        matchingService.registerOrder(cancelledOrder);
        matchingService.registerOrder(stillPendingOrder);

        // OrderBook에는 "PENDING이었던 시점의 스냅샷(가격/시각)"만 남아있고, 실제 체결 시도 시점엔
        // orderId로 DB 최신 상태를 다시 조회한다(drainAndExecute) - 그 사이에 사용자가 취소했다고 가정.
        cancelledOrder.setOrderStatus(OrderStatus.CANCELLED);

        when(orderRepository.findPendingStockList(any())).thenReturn(List.of(STOCK_CODE));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(cancelledOrder));
        when(orderRepository.findById(2L)).thenReturn(Optional.of(stillPendingOrder));
        when(memberRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(newMember()));
        // 두 주문 다 체결 자격이 있는 호가(물량도 충분) - 취소되지 않았다면 cancelledOrder도 체결됐을 상황
        when(kisWebSocketService.getCachedAskingPrice(STOCK_CODE))
                .thenReturn(askingPriceWith("49000", "20"));

        matchingService.matching();

        assertEquals(0, cancelledOrder.getMatchedQuantity(), "이미 취소된 주문은 체결 시도 자체가 안 되어 체결수량이 0으로 남아야 한다");
        assertEquals(OrderStatus.CANCELLED, cancelledOrder.getOrderStatus(), "취소 상태가 그대로 유지돼야 한다(되살아나면 안 됨)");

        assertEquals(10, stillPendingOrder.getMatchedQuantity(), "취소된 주문(더 높은 우선순위)을 건너뛰어도, 대기 중인 다음 주문은 정상 체결돼야 한다");
        assertEquals(OrderStatus.MATCHED, stillPendingOrder.getOrderStatus());

        verify(tradeRepository, times(1)).save(any(Trade.class)); // 체결은 취소되지 않은 주문 1건만 발생
    }
}
