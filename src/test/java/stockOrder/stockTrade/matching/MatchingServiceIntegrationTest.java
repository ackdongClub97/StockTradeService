package stockOrder.stockTrade.matching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import stockOrder.stockTrade.support.MySqlContainerConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/* MatchingService + 실제 JPA 리포지토리 + 실제 DB(MySQL 8.4 Testcontainers 컨테이너) 통합 테스트.
   외부 연동(KIS 시세/웹소켓, FDS, 어드민 알림, 매매차익 캐시)만 Mock으로 대체한다.

   유닛 테스트(MatchingServiceTest)는 "DB에서 읽을 때마다 별도 엔티티가 나온다"를 가짜 DB로 흉내냈는데,
   여기선 실제 Hibernate가 조회마다 새 엔티티를 만들고 save(merge)가 실제로 행을 덮어쓰는지까지 확인한다.

   Propagation.NOT_SUPPORTED: 테스트 메서드를 트랜잭션으로 감싸지 않음 - 운영처럼 리포지토리 호출마다
   각자 커밋되게 해서, 다른 스레드(즉시체결)가 배치가 커밋한 결과를 실제로 볼 수 있게 하기 위함.
   그래서 롤백이 안 되므로 @BeforeEach에서 직접 비운다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // 내장 H2로 바꾸지 말고 아래 MySQL 컨테이너 사용
@Import(MySqlContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MatchingServiceIntegrationTest {

    private static final String MEMBER_ID = "it_member";
    private static final String STOCK_CODE = "005930";
    private static final int ORDER_PRICE = 50_000;
    private static final int ORDER_QTY = 10;
    private static final int ASK_PRICE = 49_000;
    // 주문 시점에 지정가 x 수량(500,000원)이 이미 예약(차감)된 상태의 잔고
    private static final int SEED_AFTER_RESERVATION = 10_000_000 - ORDER_PRICE * ORDER_QTY;

    @Autowired private OrderRepository realOrderRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private OrderRepository orderRepository; // 실제 리포지토리에 위임하되, 특정 시점에 끼어들 수 있게 감싼 것
    private KisWebSocketService kisWebSocketService;
    private MatchingService matchingService;

    @BeforeEach
    void setUp() {
        tradeRepository.deleteAll();
        realOrderRepository.deleteAll();
        memberRepository.deleteAll();

        orderRepository = mock(OrderRepository.class, AdditionalAnswers.delegatesTo(realOrderRepository));
        KisService kisService = mock(KisService.class);
        when(kisService.isMarketOpen()).thenReturn(true);
        kisWebSocketService = mock(KisWebSocketService.class);
        // 매 호출마다 새 틱(새 객체) - 같은 틱 잔량 공유가 아니라 주문 상태 재확인만 검증하기 위함
        when(kisWebSocketService.getCachedAskingPrice(STOCK_CODE)).thenAnswer(inv -> askingPrice());

        matchingService = new MatchingService(orderRepository, tradeRepository, memberRepository,
                kisService, kisWebSocketService, mock(RealizedPnlService.class),
                mock(AdminAlertService.class), mock(FraudDetectionService.class),
                new TransactionTemplate(transactionManager), StockOwnership.ownsAll(), mock(OrderResultBroadcaster.class));

        Member member = new Member();
        member.setMemberId(MEMBER_ID);
        member.setSeed(SEED_AFTER_RESERVATION);
        memberRepository.save(member);
    }

    /* OrderController.submitOrder가 하는 것과 같은 상태로 주문을 DB에 저장 */
    private Order savePendingBuyOrder() {
        Order order = new Order();
        order.setMemberId(MEMBER_ID);
        order.setStockCode(STOCK_CODE);
        order.setStockName("삼성전자");
        order.setOrderType(OrderType.BUY);
        order.setOrderStatus(OrderStatus.PENDING);
        order.setPriceMode(PriceMode.LIMIT);
        order.setAutoPriceImprovement(true);
        order.setPrice(ORDER_PRICE);
        order.setQuantity(ORDER_QTY);
        order.setCreatedAt(LocalDateTime.now());
        return realOrderRepository.save(order);
    }

    /* 매도호가 1단계에 49,000원 100주 - 지정가 50,000원 매수 10주가 전량 체결 가능한 호가 */
    private AskingPriceDTO askingPrice() {
        AskingPriceDTO dto = new AskingPriceDTO();
        dto.setStockCode(STOCK_CODE);
        String[] askPrices = new String[10];
        String[] askVolumes = new String[10];
        String[] zeros = new String[10];
        for (int i = 0; i < 10; i++) {
            askPrices[i] = "0";
            askVolumes[i] = "0";
            zeros[i] = "0";
        }
        askPrices[0] = String.valueOf(ASK_PRICE);
        askVolumes[0] = "100";
        dto.setAskPrices(askPrices);
        dto.setAskVolumes(askVolumes);
        dto.setBidPrices(zeros);
        dto.setBidVolumes(zeros.clone());
        dto.setTotalAskVolume("100");
        dto.setTotalBidVolume("0");
        return dto;
    }

    private List<Trade> tradesOf(Long orderId) {
        return tradeRepository.findAll().stream().filter(t -> t.getOrderId().equals(orderId)).toList();
    }

    private int seed() {
        return memberRepository.findByMemberId(MEMBER_ID).orElseThrow().getSeed();
    }

    @Test
    @DisplayName("1. 정상 경로 - 즉시체결이 체결 원장(Trade)·주문 상태·잔고 정산을 실제 DB에 반영한다")
    void 즉시체결_결과가_DB에_반영된다() {
        Order saved = savePendingBuyOrder();

        matchingService.tryMatch(saved);

        Order order = realOrderRepository.findById(saved.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.MATCHED, order.getOrderStatus());
        assertEquals(ORDER_QTY, order.getMatchedQuantity());
        assertEquals(1, tradesOf(saved.getOrderId()).size());
        // 지정가 50,000원으로 예약했는데 49,000원에 체결 -> 차액 1,000원 x 10주 환급
        assertEquals(SEED_AFTER_RESERVATION + (ORDER_PRICE - ASK_PRICE) * ORDER_QTY, seed());
    }

    @Test
    @DisplayName("2. 취소 반영 - 제출 후 Kafka 소비 전에 취소(환급 완료)된 주문은 즉시체결되지 않고, 잔고도 다시 움직이지 않는다")
    void Kafka_소비_전에_취소된_주문은_체결되지_않는다() {
        Order message = savePendingBuyOrder(); // Kafka 메시지에 실린 건 제출 시점(PENDING) 상태

        // OrderController.cancelOrder와 같은 결과: 예약분 환급 + CANCELLED
        Member member = memberRepository.findByMemberId(MEMBER_ID).orElseThrow();
        member.setSeed(member.getSeed() + ORDER_PRICE * ORDER_QTY);
        memberRepository.save(member);
        Order cancelled = realOrderRepository.findById(message.getOrderId()).orElseThrow();
        cancelled.setOrderStatus(OrderStatus.CANCELLED);
        realOrderRepository.save(cancelled);
        int seedAfterCancel = seed();

        matchingService.tryMatch(message);

        Order order = realOrderRepository.findById(message.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, order.getOrderStatus(), "취소된 주문이 체결로 되살아나면 안 된다");
        assertEquals(0, order.getMatchedQuantity());
        assertTrue(tradesOf(message.getOrderId()).isEmpty());
        assertEquals(seedAfterCancel, seed(), "환급이 끝난 주문이라 잔고가 더 움직이면 안 된다");
    }

    @Test
    @DisplayName("3. 락 대기 중 상태 변경 - 즉시체결이 종목 락을 기다리는 동안 배치가 같은 주문을 전량 체결하면, 즉시체결은 다시 체결하지 않는다")
    void 락을_기다리는_동안_배치가_체결한_주문은_중복_체결되지_않는다() throws Exception {
        Order message = savePendingBuyOrder();
        matchingService.registerOrder(message); // submitOrder처럼 배치용 호가창(OrderBook)에도 등록

        Thread tryMatchThread = new Thread(() -> matchingService.tryMatch(message));

        // 배치가 종목 락을 잡은 채 DB를 읽기 직전에 즉시체결 스레드를 시작시키고, 그 스레드가 같은 락에서
        // BLOCKED 될 때까지 기다렸다가 실제 DB 조회로 넘어간다 - 경쟁 순서를 매번 같게 고정하기 위함
        doAnswer(inv -> {
            tryMatchThread.start();
            long deadline = System.currentTimeMillis() + 5_000;
            while (tryMatchThread.getState() != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            return realOrderRepository.findAllById(inv.getArgument(0));
        }).when(orderRepository).findAllById(any());

        matchingService.matching();
        tryMatchThread.join(5_000);

        Order order = realOrderRepository.findById(message.getOrderId()).orElseThrow();
        List<Trade> trades = tradesOf(message.getOrderId());
        assertEquals(1, trades.size(), "배치 1건만 체결돼야 한다 - 즉시체결이 한 번 더 체결하면 2건");
        assertEquals(ORDER_QTY, trades.stream().mapToInt(Trade::getMatchedQuantity).sum(), "체결 합계가 주문수량을 넘으면 안 된다");
        assertEquals(ORDER_QTY, order.getMatchedQuantity());
        assertEquals(OrderStatus.MATCHED, order.getOrderStatus());
        assertEquals(SEED_AFTER_RESERVATION + (ORDER_PRICE - ASK_PRICE) * ORDER_QTY, seed(), "차액 환급은 한 번만 일어나야 한다");
    }
}
