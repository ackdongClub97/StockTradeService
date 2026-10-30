package stockOrder.stockTrade.matching;

import org.apache.kafka.clients.producer.internals.BuiltInPartitioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import stockOrder.stockTrade.admin.AdminAlertService;
import stockOrder.stockTrade.fds.FraudDetectionService;
import stockOrder.stockTrade.kis.AskingPriceDTO;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.kis.KisWebSocketService;
import stockOrder.stockTrade.member.MemberRepository;
import stockOrder.stockTrade.order.Order;
import stockOrder.stockTrade.order.OrderRepository;
import stockOrder.stockTrade.order.OrderStatus;
import stockOrder.stockTrade.order.OrderType;
import stockOrder.stockTrade.order.PriceMode;
import stockOrder.stockTrade.order.RealizedPnlService;
import stockOrder.stockTrade.order.Trade;
import stockOrder.stockTrade.order.TradeRepository;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/* 다중 서버 체결 소유권 - 서버마다 stock-order 파티션 몫의 종목만 체결하고, 리밸런스로 넘길 땐 진행 중인 체결을 끝낸 뒤 넘기는지 검증 */
class MatchingServiceOwnershipTest {

    private static final int PARTITIONS = 6;

    private OrderRepository orderRepository;
    private TradeRepository tradeRepository;
    private KisWebSocketService kisWebSocketService;
    private StockOwnership ownership;
    private MatchingService matchingService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        tradeRepository = mock(TradeRepository.class);
        kisWebSocketService = mock(KisWebSocketService.class);
        KisService kisService = mock(KisService.class);
        when(kisService.isMarketOpen()).thenReturn(true);
        ownership = new StockOwnership();
        matchingService = new MatchingService(orderRepository, tradeRepository, mock(MemberRepository.class),
                kisService, kisWebSocketService, mock(RealizedPnlService.class), mock(AdminAlertService.class),
                mock(FraudDetectionService.class), new TransactionTemplate(mock(PlatformTransactionManager.class)), ownership,
                mock(OrderResultBroadcaster.class));
    }

    private Order pendingBuy(long id, String stockCode) {
        Order order = new Order();
        order.setOrderId(id);
        order.setMemberId("m" + id);
        order.setStockCode(stockCode);
        order.setStockName(stockCode);
        order.setOrderType(OrderType.BUY);
        order.setOrderStatus(OrderStatus.PENDING);
        order.setPriceMode(PriceMode.MARKET);
        order.setPrice(50_000);
        order.setQuantity(10);
        order.setCreatedAt(LocalDateTime.now());
        return order;
    }

    private AskingPriceDTO askingPrice(String stockCode) {
        AskingPriceDTO dto = new AskingPriceDTO();
        dto.setStockCode(stockCode);
        String[] prices = new String[10];
        String[] volumes = new String[10];
        String[] zeros = new String[10];
        for (int i = 0; i < 10; i++) {
            prices[i] = "0";
            volumes[i] = "0";
            zeros[i] = "0";
        }
        prices[0] = "49000";
        volumes[0] = "100";
        dto.setAskPrices(prices);
        dto.setAskVolumes(volumes);
        dto.setBidPrices(zeros);
        dto.setBidVolumes(zeros.clone());
        return dto;
    }

    /* partition 번 파티션에 들어가는 종목코드 하나 찾기 */
    private String stockIn(int partition) {
        ownership.assign(List.of(), PARTITIONS);
        return IntStream.range(0, 100_000).mapToObj(i -> String.format("%06d", i))
                .filter(code -> ownership.partitionOf(code) == partition)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("종목 -> 파티션 계산이 Kafka 프로듀서 기본 파티셔너와 같다 (어긋나면 다른 서버의 종목을 체결하게 됨)")
    void 파티션_계산이_카프카와_같다() {
        ownership.assign(List.of(), PARTITIONS);
        for (String code : List.of("005930", "000660", "035420", "373220", "068270", "105560")) {
            int kafka = BuiltInPartitioner.partitionForKey(code.getBytes(StandardCharsets.UTF_8), PARTITIONS);
            assertEquals(kafka, ownership.partitionOf(code), code);
        }
    }

    @Test
    @DisplayName("파티션을 받으면 그 파티션 종목의 대기 주문만 호가창에 올리고, 30초 배치도 자기 종목만 체결한다")
    void 맡은_종목만_호가창에_올리고_체결한다() {
        String mine = stockIn(1);
        String others = stockIn(2);
        when(orderRepository.findByOrderStatusIn(any())).thenReturn(List.of(pendingBuy(1, mine), pendingBuy(2, others)));
        when(orderRepository.findPendingStockList(any())).thenReturn(List.of(mine, others));
        when(orderRepository.findAllById(any())).thenReturn(List.of(pendingBuy(1, mine)));
        when(orderRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kisWebSocketService.getCachedAskingPrice(any())).thenAnswer(inv -> askingPrice(inv.getArgument(0)));

        matchingService.onPartitionsAssigned(List.of(1), PARTITIONS);
        assertTrue(ownership.owns(mine));
        assertFalse(ownership.owns(others));

        matchingService.matching();

        verify(kisWebSocketService, never()).getCachedAskingPrice(others); // 남의 종목은 호가 조회조차 안 함
        verify(tradeRepository, times(1)).save(any(Trade.class));         // 내 종목 주문 1건만 체결
    }

    @Test
    @DisplayName("파티션 반납은 진행 중인 체결이 끝날 때까지 기다리고, 반납 후엔 그 종목을 체결하지 않는다")
    void 반납은_진행중인_체결을_기다린다() throws Exception {
        String code = stockIn(3);
        Order order = pendingBuy(1, code);
        when(orderRepository.findByOrderStatusIn(any())).thenReturn(List.of(order));
        when(orderRepository.findPendingStockList(any())).thenReturn(List.of(code));
        when(orderRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kisWebSocketService.getCachedAskingPrice(any())).thenAnswer(inv -> askingPrice(code));

        // 배치가 종목 락을 잡고 DB를 읽는 도중에 멈춰 있게 만든다
        CountDownLatch batchInside = new CountDownLatch(1);
        CountDownLatch releaseBatch = new CountDownLatch(1);
        when(orderRepository.findAllById(any())).thenAnswer(inv -> {
            batchInside.countDown();
            releaseBatch.await(5, TimeUnit.SECONDS);
            return List.of(order);
        });

        matchingService.onPartitionsAssigned(List.of(3), PARTITIONS);
        Thread batch = new Thread(matchingService::matching);
        batch.start();
        assertTrue(batchInside.await(5, TimeUnit.SECONDS));

        Thread revoke = new Thread(() -> matchingService.onPartitionsRevoked(List.of(3)));
        revoke.start();
        revoke.join(500);
        assertTrue(revoke.isAlive(), "진행 중인 체결이 끝나기 전에 반납이 끝나면 안 된다(새 담당 서버와 겹침)");
        assertFalse(ownership.owns(code), "반납을 시작하자마자 소유권은 내려놔야 한다(새 체결 시작 금지)");

        releaseBatch.countDown();
        batch.join(5_000);
        revoke.join(5_000);
        assertFalse(revoke.isAlive());
        verify(tradeRepository, times(1)).save(any(Trade.class)); // 반납 전에 시작한 체결은 마무리됨

        // 반납 후엔 즉시체결 메시지가 와도 체결하지 않는다
        when(orderRepository.findById(1L)).thenReturn(Optional.of(pendingBuy(1, code)));
        matchingService.tryMatch(pendingBuy(1, code));
        matchingService.matching();
        verify(tradeRepository, times(1)).save(any(Trade.class));
    }
}
