package stockOrder.stockTrade.matching;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import stockOrder.stockTrade.order.Order;
import tools.jackson.databind.ObjectMapper;

/* 체결/장마감취소 결과를 "그 회원이 SSE로 붙어 있는 서버"까지 전달한다.
   서버가 여러 대면 체결은 그 종목을 맡은 서버에서 일어나지만, 회원의 SSE 연결(/api/order/stream)은 nginx 라운드로빈으로
   아무 서버에나 붙어 있을 수 있다. 그래서 결과를 Kafka(order-result)로 내보내고 모든 서버가 받아 자기 SSE 연결에 흘려준다.
   (예전엔 MatchingService 안의 메모리 Sink로만 보내서 체결이 일어난 서버에 붙은 회원만 알림을 받을 수 있었음) */
@Component
@Slf4j
public class OrderResultBroadcaster {

    public static final String TOPIC = "order-result";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper json;
    private final Sinks.Many<Order> sink = Sinks.many().multicast().onBackpressureBuffer();

    public OrderResultBroadcaster(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper json) {
        this.kafkaTemplate = kafkaTemplate;
        this.json = json;
    }

    public void publish(Order order) {
        kafkaTemplate.send(TOPIC, order.getMemberId(), json.writeValueAsString(order))
                .whenComplete((r, e) -> {
                    if (e != null) {
                        // 알림은 실시간 화면 갱신용 보조 채널(진실의 원천은 DB) - Kafka가 안 되면 최소한 이 서버에 붙은 회원에게라도 보낸다
                        log.warn("[OrderResult] 브로드캐스트 실패 - 이 서버에만 전달 {} : {}", order.getOrderId(), e.getMessage());
                        sink.tryEmitNext(order);
                    }
                });
    }

    /* 서버마다 고유 그룹 = 모든 서버가 전부 받음. latest = 재기동 시 지난 알림은 다시 보내지 않음 */
    @KafkaListener(topics = TOPIC, groupId = "order-result-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=latest")
    void onOrderResult(String message) {
        sink.tryEmitNext(json.readValue(message, Order.class));
    }

    public Flux<Order> stream(String memberId) {
        return sink.asFlux().filter(order -> order.getMemberId().equals(memberId));
    }

    @Bean
    static NewTopic orderResultTopic() {
        // 실시간 알림용이라 오래 보관할 필요 없음
        return TopicBuilder.name(TOPIC).partitions(3).replicas(1).config("retention.ms", "3600000").build();
    }
}
