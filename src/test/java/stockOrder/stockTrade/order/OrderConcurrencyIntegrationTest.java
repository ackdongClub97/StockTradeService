package stockOrder.stockTrade.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import stockOrder.stockTrade.support.MySqlContainerConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import stockOrder.stockTrade.admin.AdminAlertService;
import stockOrder.stockTrade.fds.FraudDetectionService;
import stockOrder.stockTrade.kis.AskingPriceDTO;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.kis.KisWebSocketService;
import stockOrder.stockTrade.matching.MatchingService;
import stockOrder.stockTrade.matching.OrderResultBroadcaster;
import stockOrder.stockTrade.matching.StockOwnership;
import stockOrder.stockTrade.member.Member;
import stockOrder.stockTrade.member.MemberRepository;
import stockOrder.stockTrade.outbox.OutboxEventRepository;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/* 주문 접수/취소(OrderCommandService)와 체결(MatchingService)이 같은 회원 잔고·같은 주문을 동시에 건드릴 때의
   정합성을 실제 JPA + MySQL(Testcontainers) DB로 검증한다.

   - 잔고/보유수량: 회원 행 비관적 락(SELECT ... FOR UPDATE) + 잔고 증감 원자적 UPDATE(seed = seed + delta)
   - 취소 vs 체결: Order의 @Version(낙관적 락) - 나중에 커밋하는 쪽이 실패하고 롤백

   OrderCommandService는 직접 생성하고 ProxyFactory로 트랜잭션 프록시를 씌운다(@Transactional·setRollbackOnly가
   운영과 똑같이 동작하게). 경쟁 순서를 고정해야 하는 테스트는 실제 리포지토리에 위임하는 Mockito 래퍼로
   특정 조회 직후에 다른 스레드의 작업을 끝까지 실행시킨다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // 내장 H2로 바꾸지 말고 아래 MySQL 컨테이너 사용
@Import(MySqlContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderConcurrencyIntegrationTest {

    private static final String MEMBER_ID = "it_member";
    private static final String STOCK_CODE = "005930";
    private static final int ORDER_PRICE = 50_000;
    private static final int ORDER_QTY = 10;
    private static final int ASK_PRICE = 49_000;
    private static final int ORDER_COST = ORDER_PRICE * ORDER_QTY; // 500,000원

    @Autowired private OrderRepository orderRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private RealizedPnlService realizedPnlService;
    private OrderCommandService commandService;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        tradeRepository.deleteAll();
        orderRepository.deleteAll();
        memberRepository.deleteAll();

        realizedPnlService = mock(RealizedPnlService.class);
        commandService = transactional(new OrderCommandService(orderRepository, memberRepository, realizedPnlService, outboxEventRepository, JsonMapper.builder().build()));
    }

    private OrderCommandService transactional(OrderCommandService target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor((TransactionManager) transactionManager, new AnnotationTransactionAttributeSource()));
        return (OrderCommandService) factory.getProxy();
    }

    private MatchingService matchingService(OrderRepository repository) {
        KisService kisService = mock(KisService.class);
        when(kisService.isMarketOpen()).thenReturn(true);
        KisWebSocketService kisWebSocketService = mock(KisWebSocketService.class);
        when(kisWebSocketService.getCachedAskingPrice(STOCK_CODE)).thenAnswer(inv -> askingPrice());
        return new MatchingService(repository, tradeRepository, memberRepository,
                kisService, kisWebSocketService, mock(RealizedPnlService.class),
                mock(AdminAlertService.class), mock(FraudDetectionService.class),
                new TransactionTemplate(transactionManager), StockOwnership.ownsAll(), mock(OrderResultBroadcaster.class));
    }

    private void saveMember(int seed) {
        Member member = new Member();
        member.setMemberId(MEMBER_ID);
        member.setSeed(seed);
        memberRepository.save(member);
    }

    private Order newOrder(OrderType type, int price, int quantity) {
        Order order = new Order();
        order.setStockCode(STOCK_CODE);
        order.setStockName("삼성전자");
        order.setOrderType(type);
        order.setPriceMode(PriceMode.LIMIT);
        order.setAutoPriceImprovement(true);
        order.setPrice(price);
        order.setQuantity(quantity);
        return order;
    }

    /* 매도호가 1단계에 49,000원 100주 */
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

    private int seed() {
        return memberRepository.findByMemberId(MEMBER_ID).orElseThrow().getSeed();
    }

    private List<Trade> tradesOf(Long orderId) {
        return tradeRepository.findAll().stream().filter(t -> t.getOrderId().equals(orderId)).toList();
    }

    /* 작업들을 최대한 같은 순간에 시작시키고 결과를 모은다 */
    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return task.call();
            }));
        }
        ready.await();
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    /* 다른 스레드(= 다른 트랜잭션)에서 작업을 끝까지 실행하고 기다린다 */
    private void runInOtherThread(Runnable task) throws InterruptedException {
        Thread t = new Thread(task);
        t.start();
        t.join(10_000);
    }

    @Test
    @DisplayName("1. 동시 매수 - 같은 회원이 잔고(100만원)로 50만원짜리 매수를 5건 동시에 내도 2건만 접수되고 잔고는 음수가 되지 않는다")
    void 동시_매수_주문이_잔고를_넘어서_접수되지_않는다() throws Exception {
        saveMember(2 * ORDER_COST);

        List<Callable<OrderCommandService.SubmitResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> commandService.submit(newOrder(OrderType.BUY, ORDER_PRICE, ORDER_QTY), MEMBER_ID));
        }
        List<OrderCommandService.SubmitResult> results = runConcurrently(tasks);

        long accepted = results.stream().filter(r -> r == OrderCommandService.SubmitResult.OK).count();
        assertEquals(2, accepted, "잔고로 감당 가능한 2건만 접수돼야 한다");
        assertEquals(0, seed(), "예약 후 잔고는 정확히 0원이어야 한다(음수 불가)");
        assertEquals(2, orderRepository.findAll().size());
    }

    @Test
    @DisplayName("2. 동시 매도 - 보유 10주로 5주짜리 매도를 5건 동시에 내도 2건만 접수된다")
    void 동시_매도_주문이_보유수량을_넘어서_접수되지_않는다() throws Exception {
        saveMember(0);
        when(realizedPnlService.getRemainingQuantity(anyString(), anyString())).thenReturn(10L);

        List<Callable<OrderCommandService.SubmitResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> commandService.submit(newOrder(OrderType.SELL, ORDER_PRICE, 5), MEMBER_ID));
        }
        List<OrderCommandService.SubmitResult> results = runConcurrently(tasks);

        long accepted = results.stream().filter(r -> r == OrderCommandService.SubmitResult.OK).count();
        assertEquals(2, accepted, "보유 10주 안에서 5주짜리 2건만 접수돼야 한다");
        assertEquals(10, orderRepository.sumReservedSellQuantity(MEMBER_ID, STOCK_CODE), "대기 매도 수량 합이 보유수량을 넘으면 안 된다");
    }

    @Test
    @DisplayName("3. 잔고 증감 동시 실행 - 취소 환급 5건과 신규 매수 차감 5건이 동시에 일어나도 금액이 사라지지 않는다")
    void 환급과_차감이_동시에_일어나도_잔고가_정확하다() throws Exception {
        saveMember(1_000_000 + 5 * ORDER_COST);
        List<Long> pendingIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Order order = newOrder(OrderType.BUY, ORDER_PRICE, ORDER_QTY);
            commandService.submit(order, MEMBER_ID);
            pendingIds.add(order.getOrderId());
        }
        assertEquals(1_000_000, seed(), "5건 예약 후 잔고");

        List<Callable<Object>> tasks = new ArrayList<>();
        for (Long id : pendingIds) {
            tasks.add(() -> commandService.cancel(MEMBER_ID, id));                                         // +500,000
            tasks.add(() -> commandService.submit(newOrder(OrderType.BUY, ORDER_PRICE, 2), MEMBER_ID));    // -100,000
        }
        List<Object> results = runConcurrently(tasks);

        assertTrue(results.stream().allMatch(r -> r == OrderCommandService.CancelResult.OK || r == OrderCommandService.SubmitResult.OK),
                "모든 취소/접수가 성공해야 한다: " + results);
        assertEquals(1_000_000 + 5 * ORDER_COST - 5 * ORDER_PRICE * 2, seed(), "환급과 차감이 하나도 빠짐없이 반영돼야 한다");
    }

    @Test
    @DisplayName("4. 취소 vs 체결 (체결이 먼저 커밋) - 취소가 주문을 읽은 뒤 체결이 먼저 커밋되면, 취소는 충돌로 롤백되고 환급도 안 된다")
    void 취소가_읽은_뒤_체결이_먼저_커밋되면_취소가_롤백된다() throws Exception {
        saveMember(ORDER_COST);
        Order order = newOrder(OrderType.BUY, ORDER_PRICE, ORDER_QTY);
        commandService.submit(order, MEMBER_ID);
        Long orderId = order.getOrderId();
        MatchingService matching = matchingService(orderRepository);

        // 취소가 주문을 읽은 직후(아직 저장 전), 다른 트랜잭션에서 즉시체결을 끝까지 실행시킨다
        OrderRepository hooked = mock(OrderRepository.class, AdditionalAnswers.delegatesTo(orderRepository));
        AtomicBoolean fired = new AtomicBoolean(false);
        doAnswer(inv -> {
            Object result = orderRepository.findById(inv.getArgument(0));
            if (fired.compareAndSet(false, true)) {
                Order message = orderRepository.findById(orderId).orElseThrow();
                runInOtherThread(() -> matching.tryMatch(message));
            }
            return result;
        }).when(hooked).findById(any());
        OrderCommandService racingCancel = transactional(new OrderCommandService(hooked, memberRepository, realizedPnlService, outboxEventRepository, JsonMapper.builder().build()));

        OrderCommandService.CancelResult result = racingCancel.cancel(MEMBER_ID, orderId);

        assertEquals(OrderCommandService.CancelResult.CONFLICT, result);
        Order saved = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.MATCHED, saved.getOrderStatus(), "체결 결과가 취소로 덮어써지면 안 된다");
        assertEquals(1, tradesOf(orderId).size());
        assertEquals((ORDER_PRICE - ASK_PRICE) * ORDER_QTY, seed(), "체결 차액 환급만 있고, 취소 환급(50만원)은 없어야 한다");
    }

    @Test
    @DisplayName("5. 취소 vs 체결 (취소가 먼저 커밋) - 배치가 주문을 읽은 뒤 취소가 먼저 커밋되면, 체결은 충돌로 롤백되고 Trade가 생기지 않는다")
    void 배치가_읽은_뒤_취소가_먼저_커밋되면_체결이_롤백된다() throws Exception {
        saveMember(ORDER_COST);
        Order order = newOrder(OrderType.BUY, ORDER_PRICE, ORDER_QTY);
        commandService.submit(order, MEMBER_ID);
        Long orderId = order.getOrderId();

        // 배치가 대기 주문을 읽은 직후(체결 전), 다른 트랜잭션에서 사용자 취소를 끝까지 실행시킨다
        OrderRepository hooked = mock(OrderRepository.class, AdditionalAnswers.delegatesTo(orderRepository));
        AtomicReference<OrderCommandService.CancelResult> cancelResult = new AtomicReference<>();
        doAnswer(inv -> {
            Object result = orderRepository.findAllById(inv.getArgument(0));
            runInOtherThread(() -> cancelResult.set(commandService.cancel(MEMBER_ID, orderId)));
            return result;
        }).when(hooked).findAllById(any());
        MatchingService matching = matchingService(hooked);
        matching.registerOrder(order);

        matching.matching();

        assertEquals(OrderCommandService.CancelResult.OK, cancelResult.get());
        Order saved = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, saved.getOrderStatus(), "취소된 주문이 체결로 되살아나면 안 된다");
        assertEquals(0, saved.getMatchedQuantity());
        assertTrue(tradesOf(orderId).isEmpty(), "롤백된 체결의 Trade가 남으면 안 된다");
        assertEquals(ORDER_COST, seed(), "취소 환급만 반영돼 처음 잔고로 돌아와야 한다");
    }
}
