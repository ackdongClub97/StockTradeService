package stockOrder.stockTrade.admin;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

/* 관리자 실시간 알림(SYSTEM_ERROR, FDS, DLQ). 서버가 여러 대면 알림은 아무 서버에서나 생기고 관리자 화면 SSE는 한 서버에만
   붙어 있으므로, Kafka(admin-alert)로 내보내 모든 서버가 받아 자기 SSE에 흘려준다(OrderResultBroadcaster와 같은 방식) */
@Service
@Slf4j
public class AdminAlertService {

    public static final String TOPIC = "admin-alert";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper json;
    private final Sinks.Many<AdminAlert> sink = Sinks.many().multicast().onBackpressureBuffer();

    public AdminAlertService(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper json) {
        this.kafkaTemplate = kafkaTemplate;
        this.json = json;
    }

    public void publish(AdminAlert alert) {
        kafkaTemplate.send(TOPIC, json.writeValueAsString(alert))
                .whenComplete((r, e) -> {
                    if (e != null) {
                        log.warn("[AdminAlert] 브로드캐스트 실패 - 이 서버에만 전달: {}", e.getMessage());
                        sink.tryEmitNext(alert);
                    }
                });
    }

    @KafkaListener(topics = TOPIC, groupId = "admin-alert-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=latest")
    void onAlert(String message) {
        sink.tryEmitNext(json.readValue(message, AdminAlert.class));
    }

    public Flux<AdminAlert> stream() {
        return sink.asFlux();
    }

    @Bean
    static NewTopic adminAlertTopic() {
        return TopicBuilder.name(TOPIC).partitions(1).replicas(1).config("retention.ms", "3600000").build();
    }
}
