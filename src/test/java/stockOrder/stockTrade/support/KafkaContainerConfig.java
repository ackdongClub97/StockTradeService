package stockOrder.stockTrade.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/* 통합 테스트용 Kafka 컨테이너 - @Import하면 @ServiceConnection이 spring.kafka.bootstrap-servers를 이 컨테이너로 바꿔준다. Docker 필요 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaContainerConfig {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer("apache/kafka:3.8.0");
    }
}
