package stockOrder.stockTrade.kis;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import stockOrder.stockTrade.support.KafkaContainerConfig;
import stockOrder.stockTrade.support.MySqlContainerConfig;
import stockOrder.stockTrade.token.TokenService;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/* 시세 수집기가 아닌 서버(market-data.collector=false)가 KIS를 전혀 부르지 않고 Kafka(MarketDataBus)만으로
   시세·랭킹을 받는지, 그리고 시세 토픽이 compacted라 "나중에 뜬 서버"도 종목별 마지막 값(장 마감 후 = 종가)을 받는지 검증.
   KisService/KisWebSocketService는 실제 빈 그대로 쓰고, KIS 토큰 발급(TokenService)만 Mock으로 바꿔 호출 여부를 확인한다. */
@SpringBootTest(properties = {
        "market-data.collector=false",
        "spring.config.import=",
        "spring.security.oauth2.client.registration.kakao.client-id=test",
        "spring.security.oauth2.client.registration.kakao.client-secret=test",
        "hantu-openapi.appkey=test",
        "hantu-openapi.appsecret=test",
        "naver-openapi.clientId=test",
        "naver-openapi.clientSecret=test",
})
@Import({MySqlContainerConfig.class, KafkaContainerConfig.class})
class MarketDataBusIntegrationTest {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private KafkaProperties kafkaProperties;
    // 컨테이너 주소는 @ServiceConnection이 KafkaConnectionDetails로만 넘겨줌(KafkaProperties엔 yaml의 localhost:9092가 그대로 남아 있음)
    @Autowired private KafkaConnectionDetails kafkaConnectionDetails;
    @Autowired private KisService kisService;
    @Autowired private KisWebSocketService kisWebSocketService;

    @MockitoBean private TokenService tokenService;

    private final JsonMapper json = JsonMapper.builder().build();

    private static <T> T await(Supplier<Optional<T>> condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<T> value = condition.get();
            if (value.isPresent()) return value.get();
            Thread.sleep(200);
        }
        return fail("시간 안에 확인 못 함: " + what);
    }

    private ResponseOutputDTO detail(String price) {
        ResponseOutputDTO dto = new ResponseOutputDTO();
        dto.setStckPrpr(price);
        dto.setPrdyVrss("100");
        dto.setPrdyCtrt("0.20");
        dto.setAcmlVol("1000");
        return dto;
    }

    @Test
    @DisplayName("1. 수집기가 아닌 서버 - Kafka로 받은 종목 시세·호가·랭킹이 캐시/SSE에 반영되고, KIS 토큰 발급·REST 호출은 한 번도 없다")
    void 수집기가_아닌_서버는_Kafka로만_시세를_받는다() throws Exception {
        kafkaTemplate.send(MarketDataBus.TOPIC_STOCK_DETAIL, "005930", json.writeValueAsString(detail("71000"))).get();
        AskingPriceDTO book = new AskingPriceDTO();
        book.setStockCode("005930");
        book.setAskPrices(new String[]{"71100"});
        kafkaTemplate.send(MarketDataBus.TOPIC_ASKING_PRICE, "005930", json.writeValueAsString(book)).get();
        ResponseOutputDTO ranked = detail("71000");
        ranked.setMkscShrnIscd("005930");
        kafkaTemplate.send(MarketDataBus.TOPIC_RANKING, "volume-rank", json.writeValueAsString(List.of(ranked))).get();

        await(() -> Optional.ofNullable(kisService.getCachedStockData("005930")), "종목 시세 반영");
        await(() -> Optional.ofNullable(kisWebSocketService.getCachedAskingPrice("005930")), "호가 반영");
        await(() -> kisService.getCachedRanking().isEmpty() ? Optional.empty() : Optional.of(true), "랭킹 반영");

        assertEquals("71000", kisService.getCachedStockData("005930").getStckPrpr());
        assertEquals("71100", kisWebSocketService.getCachedAskingPrice("005930").getAskPrices()[0]);
        // 상세 SSE에 새로 접속해도 마지막 값을 바로 받는다(새 틱이 없는 장 마감 후 상황)
        assertEquals("71000", kisService.getStockDetailStream("005930").blockFirst(Duration.ofSeconds(5)).getStckPrpr());
        // KIS REST로 가던 조회도 수집기가 아니면 Kafka로 받은 값을 돌려준다
        assertEquals("71000", kisService.getStockDetail("005930").block(Duration.ofSeconds(5)).getStckPrpr());
        assertEquals(1, kisService.getVolumeRank("0").block().size());

        verify(tokenService, never()).fetchToken();
        verify(tokenService, never()).fetchApprovalKey();
    }

    @Test
    @DisplayName("2. 구독 요청은 KIS가 아니라 수집기용 토픽으로 나가고, 같은 종목은 서버당 한 번만 보낸다")
    void 구독_요청은_수집기_토픽으로_간다() throws Exception {
        kisWebSocketService.subscribe("000660");
        kisWebSocketService.subscribe("000660");
        kafkaTemplate.flush();

        List<ConsumerRecord<String, String>> records = readAll(MarketDataBus.TOPIC_SUBSCRIBE);
        long requests = records.stream().filter(r -> "000660".equals(r.key())).count();
        assertEquals(1, requests);
    }

    @Test
    @DisplayName("3. 시세 토픽은 compacted이고, 나중에 새로 뜬 서버(새 컨슈머 그룹, 처음부터 읽기)는 종목별 마지막 값(= 장 마감 후 종가)을 받는다")
    void 나중에_뜬_서버도_종목별_마지막_값을_받는다() throws Exception {
        for (String price : List.of("70000", "70500", "70900")) {
            kafkaTemplate.send(MarketDataBus.TOPIC_STOCK_DETAIL, "035420", json.writeValueAsString(detail(price))).get();
        }

        Map<String, String> lastByKey = new HashMap<>();
        readAll(MarketDataBus.TOPIC_STOCK_DETAIL).forEach(r -> lastByKey.put(r.key(), r.value()));
        assertTrue(lastByKey.get("035420").contains("70900"), "새 서버가 처음부터 읽으면 마지막 값이 최종 상태여야 한다");

        Map<String, Object> adminProps = new HashMap<>(kafkaProperties.buildAdminProperties());
        adminProps.put("bootstrap.servers", String.join(",", kafkaConnectionDetails.getBootstrapServers()));
        try (AdminClient admin = AdminClient.create(adminProps)) {
            for (String topic : List.of(MarketDataBus.TOPIC_ASKING_PRICE, MarketDataBus.TOPIC_STOCK_DETAIL,
                    MarketDataBus.TOPIC_RANKING, MarketDataBus.TOPIC_SUBSCRIBE)) {
                ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
                Config config = admin.describeConfigs(List.of(resource)).all().get().get(resource);
                assertEquals("compact", config.get("cleanup.policy").value(), topic);
            }
        }
    }

    /* 새 컨슈머 그룹으로 토픽을 처음부터 끝까지 읽기 = "방금 새로 뜬 서버"가 보는 것 */
    private List<ConsumerRecord<String, String>> readAll(String topic) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties());
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnectionDetails.getBootstrapServers()));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> all = new java.util.ArrayList<>();
            long deadline = System.currentTimeMillis() + 10_000;
            int emptyPolls = 0;
            while (System.currentTimeMillis() < deadline && emptyPolls < 3) {
                var polled = consumer.poll(Duration.ofMillis(1000));
                if (polled.isEmpty() && !all.isEmpty()) emptyPolls++;
                polled.forEach(all::add);
            }
            return all;
        }
    }
}
