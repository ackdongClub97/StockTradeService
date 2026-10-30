package stockOrder.stockTrade.kis;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/* 서버 여러 대 구성에서 시세를 나눠 갖는 통로.
   KIS와 직접 통신(토큰·웹소켓·REST 폴링·장마감 랭킹 저장)하는 건 수집기(market-data.collector=true) 한 대뿐이고,
   수집기는 받은 시세를 Kafka에 발행만 한다. 모든 서버(수집기 포함)는 Kafka에서 시세를 받아 자기 캐시·SSE에 반영한다.
   - 서버마다 KIS에 따로 붙으면 KIS 호출 한도·토큰 발급 제한(분당 1회)을 서버 수만큼 나눠 쓰게 되고, 장마감 랭킹 저장도 겹친다.
   - 시세 토픽은 compacted(키 = 종목코드)라 종목별 마지막 값이 계속 남는다. 서버가 장 마감 후에 새로 떠도 처음부터 읽으면
     종목별 마지막 시세(= 종가)와 마지막 랭킹을 바로 받아서 화면에 보여줄 수 있다.
   - 종목 구독 요청도 토픽(compacted)으로 수집기에 전달 - 수집기가 재시작해도 처음부터 다시 읽어 구독을 복구한다. */
@Component
@Slf4j
public class MarketDataBus {

    public static final String TOPIC_ASKING_PRICE = "market-asking-price";
    public static final String TOPIC_STOCK_DETAIL = "market-stock-detail";
    public static final String TOPIC_RANKING = "market-ranking";
    public static final String TOPIC_SUBSCRIBE = "market-subscribe";
    private static final String RANKING_KEY = "volume-rank";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper json;
    private final boolean collector;

    // KisService/KisWebSocketService가 이 클래스에 의존하므로 순환 방지용 지연 주입
    @Autowired @Lazy private KisService kisService;
    @Autowired @Lazy private KisWebSocketService kisWebSocketService;

    private final Set<String> requestedCodes = ConcurrentHashMap.newKeySet();

    public MarketDataBus(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper json,
                         @Value("${market-data.collector:true}") boolean collector) {
        this.kafkaTemplate = kafkaTemplate;
        this.json = json;
        this.collector = collector;
    }

    public boolean isCollector() {
        return collector;
    }

    /* ===== 수집기 -> Kafka ===== */

    public void publishAskingPrice(AskingPriceDTO dto) {
        send(TOPIC_ASKING_PRICE, dto.getStockCode(), dto);
    }

    public void publishStockDetail(String code, ResponseOutputDTO dto) {
        send(TOPIC_STOCK_DETAIL, code, dto);
    }

    public void publishRanking(List<ResponseOutputDTO> ranking) {
        send(TOPIC_RANKING, RANKING_KEY, ranking);
    }

    /* 어느 서버에서든 "이 종목 시세 필요"를 수집기에 알림 - 서버마다 같은 종목은 한 번만 보냄 */
    public void requestSubscribe(String code) {
        if (requestedCodes.add(code)) {
            kafkaTemplate.send(TOPIC_SUBSCRIBE, code, code);
        }
    }

    private void send(String topic, String key, Object value) {
        kafkaTemplate.send(topic, key, json.writeValueAsString(value))
                .whenComplete((r, e) -> {
                    if (e != null) log.warn("[MarketData] 발행 실패 {} {} : {}", topic, key, e.getMessage());
                });
    }

    /* ===== Kafka -> 모든 서버 =====
       groupId를 서버(기동)마다 다르게 해서 모든 파티션을 다 받는다(브로드캐스트). earliest라 기동할 때 compacted 토픽을
       처음부터 읽어 종목별 마지막 값으로 캐시를 채운다 - 장 마감 후 기동해도 종가가 바로 보이는 이유. */

    @KafkaListener(topics = TOPIC_ASKING_PRICE, groupId = "market-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=earliest")
    void onAskingPrice(String message) {
        kisWebSocketService.applyAskingPrice(json.readValue(message, AskingPriceDTO.class));
    }

    @KafkaListener(topics = TOPIC_STOCK_DETAIL, groupId = "market-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=earliest")
    void onStockDetail(ConsumerRecord<String, String> record) {
        kisService.pushStockDetail(record.key(), json.readValue(record.value(), ResponseOutputDTO.class));
    }

    @KafkaListener(topics = TOPIC_RANKING, groupId = "market-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=earliest")
    void onRanking(String message) {
        kisService.applyRanking(json.readValue(message, new TypeReference<List<ResponseOutputDTO>>() {}));
    }

    /* 수집기만 - 구독 요청을 받아 실제 KIS 웹소켓에 등록. 재시작 시 처음부터 다시 읽어 기존 구독을 복구한다 */
    @KafkaListener(topics = TOPIC_SUBSCRIBE, groupId = "market-collector-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=earliest", autoStartup = "${market-data.collector:true}")
    void onSubscribeRequest(String code) {
        kisWebSocketService.subscribeOnKis(code);
    }

    @Configuration
    static class Topics {
        /* compacted: 키(종목코드)별 마지막 값만 남기고 정리. 세그먼트를 10분마다 닫아야 정리 대상이 되므로 segment.ms를 짧게 */
        private static final Map<String, String> COMPACT = Map.of(
                "cleanup.policy", "compact",
                "segment.ms", "600000",
                "min.cleanable.dirty.ratio", "0.1");

        @Bean NewTopic marketAskingPriceTopic() { return TopicBuilder.name(TOPIC_ASKING_PRICE).partitions(3).replicas(1).configs(COMPACT).build(); }
        @Bean NewTopic marketStockDetailTopic() { return TopicBuilder.name(TOPIC_STOCK_DETAIL).partitions(3).replicas(1).configs(COMPACT).build(); }
        @Bean NewTopic marketRankingTopic() { return TopicBuilder.name(TOPIC_RANKING).partitions(1).replicas(1).configs(COMPACT).build(); }
        @Bean NewTopic marketSubscribeTopic() { return TopicBuilder.name(TOPIC_SUBSCRIBE).partitions(1).replicas(1).configs(COMPACT).build(); }
    }
}
