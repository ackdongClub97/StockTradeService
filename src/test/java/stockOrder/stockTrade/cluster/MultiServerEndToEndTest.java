package stockOrder.stockTrade.cluster;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import stockOrder.stockTrade.StockTradeApplication;
import stockOrder.stockTrade.kis.AskingPriceDTO;
import stockOrder.stockTrade.kis.KisWebSocketService;
import stockOrder.stockTrade.kis.MarketDataBus;
import stockOrder.stockTrade.matching.MatchingService;
import stockOrder.stockTrade.matching.StockOwnership;
import stockOrder.stockTrade.member.Member;
import stockOrder.stockTrade.member.MemberRepository;
import stockOrder.stockTrade.order.Order;
import stockOrder.stockTrade.order.OrderCommandService;
import stockOrder.stockTrade.order.OrderRepository;
import stockOrder.stockTrade.order.OrderStatus;
import stockOrder.stockTrade.order.OrderType;
import stockOrder.stockTrade.order.PriceMode;
import stockOrder.stockTrade.order.TradeRepository;
import stockOrder.stockTrade.order.UnmatchReason;
import stockOrder.stockTrade.outbox.OutboxRelay;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/* 다중 서버 E2E - 실제 Kafka·MySQL 컨테이너 위에 앱(스프링 컨텍스트)을 2대 띄워서 검증한다.
   docker compose 클러스터로 손으로 확인했던 것(파티션 분배, 담당 서버만 체결, 담당 서버가 내려가면 인계)을
   반복 실행 가능한 테스트로 옮긴 것. 두 서버 모두 시세 수집기가 아니라서(KIS 호출 없음) 호가는 테스트가
   market-asking-price 토픽에 직접 발행한다 = 수집기가 발행하는 것과 같은 경로.
   장 시간 판정은 정규장 시각으로 고정한 시계("marketClock")를 끼워서, 장 마감 후에 돌려도 체결 경로를 탄다.

   흐름
   1) 서버 A·B 기동 → stock-order 파티션 6개가 겹치지 않게 나뉘는지
   2) A가 맡은 종목·B가 맡은 종목에 주문 1건씩(둘 다 A 서버로 접수 = 라운드로빈으로 A에 떨어진 상황)
      → 각 주문은 그 종목을 맡은 서버에서만 호가창에 올라가고, 정확히 1번 체결되며, 잔고 정산도 정확한지
   3) B 담당 종목에 체결 안 되는 주문(지정가 < 매도호가)을 걸어둔 채 B를 종료
      → A가 B의 파티션을 넘겨받으며 그 대기 주문을 DB에서 다시 올리고, 체결 가능한 호가가 오면 A가 체결하는지 */
class MultiServerEndToEndTest {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    // 2026-11-02(월) 10:00 KST - 정규장
    private static final Clock MARKET_OPEN = Clock.fixed(
            ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));

    private static final String MEMBER_ID = "e2e_member";
    private static final int SEED = 10_000_000;
    private static final int LIMIT_PRICE = 50_000;
    private static final int QTY = 10;

    private static ConfigurableApplicationContext serverA;
    private static ConfigurableApplicationContext serverB;

    @BeforeAll
    static void startServers() {
        MYSQL.start();
        KAFKA.start();
        serverA = startServer("A");
        serverB = startServer("B"); // 순서대로 기동 - 스키마 생성(ddl-auto)이 동시에 돌지 않게
    }

    @AfterAll
    static void stopServers() {
        if (serverB != null && serverB.isActive()) serverB.close();
        if (serverA != null && serverA.isActive()) serverA.close();
        KAFKA.stop();
        MYSQL.stop();
    }

    /* 설정은 커맨드라인 인자(--key=value)로 넘긴다 - 우선순위가 가장 높아서 application.yaml과 로컬 application-secret.yaml을
       확실히 덮어씀. (SpringApplicationBuilder.properties()는 가장 낮은 기본값이라 yaml에 덮여서, 처음엔 로컬 DB/Kafka를
       가리키고 실제 KIS 토큰 API까지 호출했었음) */
    private static ConfigurableApplicationContext startServer(String name) {
        return new SpringApplicationBuilder(StockTradeApplication.class)
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("marketClock", MARKET_OPEN))
                .run(
                        "--spring.application.name=stockTrade-" + name,
                        "--server.port=0",
                        "--spring.config.import=",
                        "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "--market-data.collector=false",
                        "--spring.security.oauth2.client.registration.kakao.client-id=test",
                        "--spring.security.oauth2.client.registration.kakao.client-secret=test",
                        "--hantu-openapi.appkey=test",
                        "--hantu-openapi.appsecret=test",
                        "--naver-openapi.clientId=test",
                        "--naver-openapi.clientSecret=test",
                        "--anthropic.apiKey=");
    }

    private static <T> T bean(ConfigurableApplicationContext server, Class<T> type) {
        return server.getBean(type);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        fail("60초 안에 확인 못 함: " + what);
    }

    /* 이 서버 메모리 호가창에 올라간 종목들(체결을 맡아 주문을 받은 종목) */
    @SuppressWarnings("unchecked")
    private static Set<String> orderBookStocks(ConfigurableApplicationContext server) {
        Object target = AopTestUtils.getUltimateTargetObject(bean(server, MatchingService.class));
        return ((Map<String, ?>) ReflectionTestUtils.getField(target, "orderBooks")).keySet();
    }

    /* 이 서버 호가창(해당 종목 큐)에 그 주문이 실제로 들어 있는지 - 종목 키만 있는 빈 호가창(배치가 만들기도 함)과 구분 */
    @SuppressWarnings("unchecked")
    private static boolean orderBookHas(ConfigurableApplicationContext server, String stockCode, Long orderId) {
        Object target = AopTestUtils.getUltimateTargetObject(bean(server, MatchingService.class));
        Object book = ((Map<String, ?>) ReflectionTestUtils.getField(target, "orderBooks")).get(stockCode);
        return book != null && ((Set<Long>) ReflectionTestUtils.getField(book, "queuedIds")).contains(orderId);
    }

    /* 매도 1호가 하나만 있는 호가 틱을 수집기처럼 Kafka에 발행하고, 지정한 서버들이 받을 때까지 기다린다 */
    private static void publishAsk(String stockCode, int askPrice, ConfigurableApplicationContext... receivers) throws Exception {
        AskingPriceDTO tick = new AskingPriceDTO();
        tick.setStockCode(stockCode);
        String[] prices = new String[10];
        String[] volumes = new String[10];
        String[] zeros = new String[10];
        for (int i = 0; i < 10; i++) {
            prices[i] = "0";
            volumes[i] = "0";
            zeros[i] = "0";
        }
        prices[0] = String.valueOf(askPrice);
        volumes[0] = "100";
        tick.setAskPrices(prices);
        tick.setAskVolumes(volumes);
        tick.setBidPrices(zeros);
        tick.setBidVolumes(zeros.clone());

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = bean(serverA, KafkaTemplate.class);
        kafka.send(MarketDataBus.TOPIC_ASKING_PRICE, stockCode, JsonMapper.builder().build().writeValueAsString(tick)).get();
        for (ConfigurableApplicationContext receiver : receivers) {
            await(() -> {
                AskingPriceDTO cached = bean(receiver, KisWebSocketService.class).getCachedAskingPrice(stockCode);
                return cached != null && cached.getAskPrices()[0].equals(String.valueOf(askPrice));
            }, stockCode + " 호가 " + askPrice + " 수신");
        }
    }

    /* OrderController.submitOrder와 같은 순서: 접수 트랜잭션(잔고 예약 + 주문 + Outbox) → 커밋 후 즉시 발행 */
    private static Long submitBuy(ConfigurableApplicationContext server, String stockCode) {
        Order order = new Order();
        order.setStockCode(stockCode);
        order.setStockName(stockCode);
        order.setOrderType(OrderType.BUY);
        order.setPriceMode(PriceMode.LIMIT);
        order.setAutoPriceImprovement(true);
        order.setPrice(LIMIT_PRICE);
        order.setQuantity(QTY);
        assertEquals(OrderCommandService.SubmitResult.OK, bean(server, OrderCommandService.class).submit(order, MEMBER_ID));
        bean(server, OutboxRelay.class).publishForAggregate(order.getOrderId());
        return order.getOrderId();
    }

    private static Order order(Long orderId) {
        return bean(serverA, OrderRepository.class).findById(orderId).orElseThrow();
    }

    private static long tradesOf(Long orderId) {
        return bean(serverA, TradeRepository.class).findAll().stream().filter(t -> t.getOrderId().equals(orderId)).count();
    }

    private static int seed() {
        return bean(serverA, MemberRepository.class).findByMemberId(MEMBER_ID).orElseThrow().getSeed();
    }

    /* partitionOf가 owner 쪽 파티션인 종목코드 하나 */
    private static String stockOwnedBy(StockOwnership owner) {
        return IntStream.range(1, 100_000).mapToObj(i -> String.format("%06d", i))
                .filter(owner::owns).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("서버 2대: 파티션을 나눠 갖고, 각 종목은 담당 서버만 정확히 1번 체결하며, 담당 서버가 내려가면 다른 서버가 대기 주문을 이어받아 체결한다")
    void 다중_서버_체결_분담과_인계() throws Exception {
        StockOwnership ownA = bean(serverA, StockOwnership.class);
        StockOwnership ownB = bean(serverB, StockOwnership.class);

        // 1) 파티션 분배 - 6개를 겹치지 않게 나눠 가짐
        await(() -> ownA.ownedPartitions().size() + ownB.ownedPartitions().size() == 6
                && !ownA.ownedPartitions().isEmpty() && !ownB.ownedPartitions().isEmpty(), "파티션 6개 분배");
        Set<Integer> overlap = new HashSet<>(ownA.ownedPartitions());
        overlap.retainAll(ownB.ownedPartitions());
        assertTrue(overlap.isEmpty(), "두 서버가 같은 파티션을 가지면 안 된다: " + overlap);

        Member member = new Member();
        member.setMemberId(MEMBER_ID);
        member.setSeed(SEED);
        bean(serverA, MemberRepository.class).save(member);

        // 2) A 담당 종목·B 담당 종목에 주문 1건씩 - 둘 다 A로 접수
        String stockA = stockOwnedBy(ownA);
        String stockB = stockOwnedBy(ownB);
        publishAsk(stockA, 49_000, serverA, serverB);
        publishAsk(stockB, 49_000, serverA, serverB);

        Long orderA = submitBuy(serverA, stockA);
        Long orderB = submitBuy(serverA, stockB);
        await(() -> order(orderA).getOrderStatus() == OrderStatus.MATCHED
                && order(orderB).getOrderStatus() == OrderStatus.MATCHED, "두 주문 체결");

        assertEquals(1, tradesOf(orderA), "한 번만 체결돼야 한다");
        assertEquals(1, tradesOf(orderB), "한 번만 체결돼야 한다");
        assertTrue(orderBookStocks(serverA).contains(stockA) && !orderBookStocks(serverA).contains(stockB),
                "A 호가창엔 A 담당 종목만: " + orderBookStocks(serverA));
        assertTrue(orderBookStocks(serverB).contains(stockB) && !orderBookStocks(serverB).contains(stockA),
                "B 호가창엔 B 담당 종목만(접수는 A였어도): " + orderBookStocks(serverB));
        // 매수 예약 50만 원 x 2, 체결가가 1,000원 싸서 1만 원씩 환급
        assertEquals(SEED - 2 * LIMIT_PRICE * QTY + 2 * (LIMIT_PRICE - 49_000) * QTY, seed());

        // 3) B 담당 종목에 체결 안 되는 주문(매도호가 60,000 > 지정가 50,000)을 걸어 둔다
        publishAsk(stockB, 60_000, serverA, serverB);
        Long pending = submitBuy(serverA, stockB);
        await(() -> order(pending).getLastUnmatchedReason() == UnmatchReason.NO_ELIGIBLE_PRICE, "B가 체결 시도 후 대기");
        assertEquals(OrderStatus.PENDING, order(pending).getOrderStatus());

        // B 종료(정상 종료 - 파티션 반납) → A가 6개 전부 받음
        serverB.close();
        await(() -> ownA.ownedPartitions().size() == 6, "A가 파티션 6개 인수");
        // 소유권 기록(assign) 직후에 DB에서 대기 주문을 올리므로 그 로드가 끝날 때까지 기다림
        await(() -> orderBookHas(serverA, stockB, pending), "A가 인수하면서 B 종목 대기 주문을 DB에서 다시 올림");

        // 체결 가능한 호가가 오면 A가 30초 배치로 체결 - 테스트에선 배치를 바로 한 번 돌림
        publishAsk(stockB, 49_000, serverA);
        bean(serverA, MatchingService.class).matching();
        await(() -> order(pending).getOrderStatus() == OrderStatus.MATCHED, "A가 인계받은 주문 체결");
        assertEquals(1, tradesOf(pending));
        assertFalse(serverB.isActive());
        assertEquals(SEED - 3 * LIMIT_PRICE * QTY + 3 * (LIMIT_PRICE - 49_000) * QTY, seed(), "잔고 정산은 주문마다 정확히 한 번");
    }
}
