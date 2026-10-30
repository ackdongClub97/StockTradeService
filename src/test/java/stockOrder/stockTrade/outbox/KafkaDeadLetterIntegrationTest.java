package stockOrder.stockTrade.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.kis.KisWebSocketService;
import stockOrder.stockTrade.matching.MatchingService;
import stockOrder.stockTrade.order.Order;
import stockOrder.stockTrade.order.OrderKafkaConfig;
import stockOrder.stockTrade.order.OrderStatus;
import stockOrder.stockTrade.order.OrderType;
import stockOrder.stockTrade.support.KafkaContainerConfig;
import stockOrder.stockTrade.support.MySqlContainerConfig;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/* 주문 컨슈머의 실패 처리(재시도 → DLT → DB 적재 → 재발행)를 실제 Kafka(Testcontainers)로 검증한다.
   예전 컨슈머는 예외를 catch해서 log.info만 남겼기 때문에, 처리 실패한 메시지는 어디에도 남지 않았음.
   매칭 엔진(MatchingService)은 Mock으로 바꿔서 "처리 중 예외"를 원하는 대로 일으킨다. */
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.security.oauth2.client.registration.kakao.client-id=test",
        "spring.security.oauth2.client.registration.kakao.client-secret=test",
        "hantu-openapi.appkey=test",
        "hantu-openapi.appsecret=test",
        "naver-openapi.clientId=test",
        "naver-openapi.clientSecret=test",
})
@Import({MySqlContainerConfig.class, KafkaContainerConfig.class})
class KafkaDeadLetterIntegrationTest {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private DeadLetterRepository deadLetterRepository;
    @Autowired private DeadLetterService deadLetterService;

    @MockitoBean private MatchingService matchingService;
    @MockitoBean private KisService kisService;
    @MockitoBean private KisWebSocketService kisWebSocketService;

    private static <T> T await(Supplier<Optional<T>> condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60);
        while (System.currentTimeMillis() < deadline) {
            Optional<T> value = condition.get();
            if (value.isPresent()) return value.get();
            Thread.sleep(200);
        }
        return fail("시간 안에 확인 못 함: " + what);
    }

    private Optional<DeadLetter> deadLetterWithKey(String key) {
        return deadLetterRepository.findAll().stream().filter(d -> key.equals(d.getMessageKey())).findFirst();
    }

    private long tryMatchCalls() {
        return mockingDetails(matchingService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("tryMatch")).count();
    }

    private String orderJson(long orderId) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setStockCode("005930");
        order.setOrderType(OrderType.BUY);
        order.setOrderStatus(OrderStatus.PENDING);
        return JsonMapper.builder().build().writeValueAsString(order);
    }

    @Test
    @DisplayName("1. 형식이 깨진 메시지(JSON 아님)는 재시도 없이 바로 DLT로 가서 DB에 적재된다")
    void 깨진_메시지는_재시도없이_DLT로_간다() throws Exception {
        kafkaTemplate.send(OrderKafkaConfig.ORDER_TOPIC, "broken-1", "not-json").get(10, TimeUnit.SECONDS);

        DeadLetter deadLetter = await(() -> deadLetterWithKey("broken-1"), "깨진 메시지 DLQ 적재");

        assertEquals(OrderKafkaConfig.ORDER_TOPIC, deadLetter.getOriginalTopic());
        assertEquals("not-json", deadLetter.getPayload());
        assertNotNull(deadLetter.getErrorMessage());
        verify(matchingService, never()).tryMatch(any()); // 파싱 단계에서 실패 - 매칭까지 가지 않음
    }

    @Test
    @DisplayName("2. 처리 중 예외가 계속 나면 총 3번(1번 + 재시도 2번) 시도한 뒤 DLT로 가고, 원인 해결 후 재발행하면 다시 처리된다")
    void 계속_실패하면_재시도후_DLT로_가고_재발행하면_다시_처리된다() throws Exception {
        doThrow(new IllegalStateException("DB 커넥션 획득 실패(테스트)")).when(matchingService).tryMatch(any());

        kafkaTemplate.send(OrderKafkaConfig.ORDER_TOPIC, "202", orderJson(202)).get(10, TimeUnit.SECONDS);

        DeadLetter deadLetter = await(() -> deadLetterWithKey("202"), "재시도 후 DLQ 적재");
        assertEquals(3, tryMatchCalls(), "최초 1번 + 재시도 2번 = 3번 시도한 뒤 DLT로 가야 한다");
        assertTrue(deadLetter.getErrorMessage().contains("DB 커넥션 획득 실패"), "실패 원인이 기록돼야 한다: " + deadLetter.getErrorMessage());

        // 원인 해결(이제 정상 처리됨) 후 관리자가 재발행
        org.mockito.Mockito.reset(matchingService);
        assertTrue(deadLetterService.replay(deadLetter.getId()));

        await(() -> tryMatchCalls() >= 1 ? Optional.of(true) : Optional.empty(), "재발행 메시지 재처리");
        assertNotNull(deadLetterRepository.findById(deadLetter.getId()).orElseThrow().getReplayedAt());
        assertTrue(!deadLetterService.replay(deadLetter.getId()), "이미 재발행한 건 다시 보내지 않는다");
    }
}
