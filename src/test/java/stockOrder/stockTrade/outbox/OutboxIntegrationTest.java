package stockOrder.stockTrade.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import stockOrder.stockTrade.member.Member;
import stockOrder.stockTrade.member.MemberRepository;
import stockOrder.stockTrade.order.Order;
import stockOrder.stockTrade.order.OrderCommandService;
import stockOrder.stockTrade.order.OrderKafkaConfig;
import stockOrder.stockTrade.order.OrderRepository;
import stockOrder.stockTrade.order.OrderType;
import stockOrder.stockTrade.order.PriceMode;
import stockOrder.stockTrade.order.RealizedPnlService;
import stockOrder.stockTrade.support.MySqlContainerConfig;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/* Transactional Outbox 검증 (실제 JPA + MySQL 컨테이너, Kafka는 Mock).
   - 주문 저장과 Outbox 기록이 같은 트랜잭션으로 묶이는지
   - Kafka 발행이 실패하면 이벤트가 남아 있다가 재시도로 발행되는지(예전엔 로그만 남기고 메시지 유실)
   - 즉시 발행·주기 발행·서버 여러 대가 같은 이벤트를 동시에 발행하려 해도 한 번만 보내는지 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MySqlContainerConfig.class, OutboxRelay.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxIntegrationTest {

    private static final String MEMBER_ID = "it_member";

    @Autowired private OutboxRelay outboxRelay;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();
        memberRepository.deleteAll();

        Member member = new Member();
        member.setMemberId(MEMBER_ID);
        member.setSeed(1_000_000);
        memberRepository.save(member);
    }

    private OrderCommandService commandService(OutboxEventRepository outboxRepository) {
        OrderCommandService target = new OrderCommandService(orderRepository, memberRepository,
                mock(RealizedPnlService.class), outboxRepository, JsonMapper.builder().build());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor((TransactionManager) transactionManager, new AnnotationTransactionAttributeSource()));
        return (OrderCommandService) factory.getProxy();
    }

    private Order buyOrder(int price, int quantity) {
        Order order = new Order();
        order.setStockCode("005930");
        order.setStockName("삼성전자");
        order.setOrderType(OrderType.BUY);
        order.setPriceMode(PriceMode.LIMIT);
        order.setPrice(price);
        order.setQuantity(quantity);
        return order;
    }

    private Long saveEvent() {
        return outboxEventRepository.save(OutboxEvent.of(OrderKafkaConfig.ORDER_TOPIC, "1", "{}", 1L)).getId();
    }

    @Test
    @DisplayName("1. 주문 접수 - 주문과 Outbox 이벤트가 같이 저장되고, 잔고 부족으로 거절되면 둘 다 안 남는다")
    void 주문과_이벤트가_같이_저장된다() {
        OrderCommandService service = commandService(outboxEventRepository);

        Order accepted = buyOrder(50_000, 10);
        assertEquals(OrderCommandService.SubmitResult.OK, service.submit(accepted, MEMBER_ID));
        assertEquals(OrderCommandService.SubmitResult.INSUFFICIENT_SEED, service.submit(buyOrder(50_000, 100), MEMBER_ID));

        assertEquals(1, orderRepository.count());
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertEquals(1, events.size());
        OutboxEvent event = events.get(0);
        assertEquals(OrderKafkaConfig.ORDER_TOPIC, event.getTopic());
        assertEquals(accepted.getOrderId(), event.getAggregateId());
        assertTrue(event.getPayload().contains("\"orderId\":" + accepted.getOrderId()), "payload에 주문이 직렬화돼 있어야 한다");
        assertNull(event.getPublishedAt(), "커밋만 됐고 아직 발행 전");
    }

    @Test
    @DisplayName("2. 원자성 - Outbox 기록이 실패하면 주문과 잔고 예약도 같이 롤백된다(주문만 남고 메시지가 없는 상태 방지)")
    void 이벤트_저장이_실패하면_주문도_롤백된다() {
        OutboxEventRepository failing = mock(OutboxEventRepository.class, AdditionalAnswers.delegatesTo(outboxEventRepository));
        doThrow(new IllegalStateException("outbox insert 실패")).when(failing).save(any());
        OrderCommandService service = commandService(failing);

        assertThrows(IllegalStateException.class, () -> service.submit(buyOrder(50_000, 10), MEMBER_ID));

        assertEquals(0, orderRepository.count(), "주문도 롤백돼야 한다");
        assertEquals(1_000_000, memberRepository.findByMemberId(MEMBER_ID).orElseThrow().getSeed(), "잔고 예약도 롤백돼야 한다");
    }

    @Test
    @DisplayName("3. 발행 실패 후 재시도 - Kafka 전송이 실패하면 이벤트가 미발행으로 남고(실패 횟수 기록), 다음 시도에서 발행된다")
    void 발행이_실패하면_남아있다가_재시도로_발행된다() {
        Long eventId = saveEvent();
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(null));

        outboxRelay.publish(eventId);
        OutboxEvent afterFailure = outboxEventRepository.findById(eventId).orElseThrow();
        assertNull(afterFailure.getPublishedAt(), "실패했으니 미발행 상태로 남아야 한다");
        assertEquals(1, afterFailure.getAttempts());
        assertTrue(afterFailure.getLastError().contains("broker down"));

        outboxRelay.publish(eventId);
        assertNotNull(outboxEventRepository.findById(eventId).orElseThrow().getPublishedAt(), "재시도에서 발행돼야 한다");
        verify(kafkaTemplate, times(2)).send(eq(OrderKafkaConfig.ORDER_TOPIC), eq("1"), eq("{}"));

        // 이미 발행된 이벤트는 다시 호출해도 보내지 않는다
        outboxRelay.publish(eventId);
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("4. 동시 발행 - 같은 이벤트를 5곳(즉시 발행·주기 발행·다른 서버)에서 동시에 발행해도 Kafka 전송은 한 번뿐이다")
    void 같은_이벤트를_동시에_발행해도_한_번만_보낸다() throws Exception {
        Long eventId = saveEvent();
        // 전송에 시간이 걸리는 상황 - 그 사이 다른 스레드들이 같은 이벤트를 잡으려고 시도하게 함
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            Thread.sleep(300);
            return CompletableFuture.completedFuture(null);
        });

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                outboxRelay.publish(eventId);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
        assertNotNull(outboxEventRepository.findById(eventId).orElseThrow().getPublishedAt());
    }
}
