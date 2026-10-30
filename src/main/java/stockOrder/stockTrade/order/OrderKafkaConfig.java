package stockOrder.stockTrade.order;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/* 주문 토픽의 실패 처리(재시도 + DLQ) 설정 */
@Configuration
public class OrderKafkaConfig {

    public static final String ORDER_TOPIC = "stock-order";
    public static final String ORDER_DLT = ORDER_TOPIC + ".DLT";

    // 브로커 자동 생성 설정에 기대지 않고 앱이 기동할 때 DLT 토픽을 만든다(KafkaAdmin) - 없으면 DLT 발행 자체가 실패함
    @Bean
    NewTopic orderDeadLetterTopic() {
        return TopicBuilder.name(ORDER_DLT).partitions(1).replicas(1).build();
    }

    /* 리스너 예외 처리: 1초 간격으로 2번 더 시도(총 3번) 후에도 실패하면 DLT로 보낸다.
       JSON 파싱 실패처럼 다시 해도 똑같이 실패할 예외는 재시도 없이 바로 DLT로.
       DLT는 파티션 1개라, 원본 파티션 번호를 그대로 쓰는 기본 동작 대신 항상 0번 파티션으로 보낸다.
       Spring Boot가 이 CommonErrorHandler 빈을 기본 리스너 컨테이너 팩토리에 자동으로 붙인다. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", 0));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
