package stockOrder.stockTrade.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/* 통합 테스트용 MySQL 컨테이너 - @Import하면 @ServiceConnection이 datasource 설정을 이 컨테이너로 바꿔준다.
   예전엔 @DataJpaTest가 H2 인메모리로, @SpringBootTest는 운영 파일 DB(~/stockTrade)로 돌았음.
   운영 전환 대상인 MySQL(InnoDB)의 실제 락/격리수준 동작으로 동시성 테스트를 돌리기 위해 컨테이너로 통일.
   같은 설정을 쓰는 테스트 클래스끼리는 Spring 테스트 컨텍스트 캐시 덕에 컨테이너 하나를 같이 쓴다. Docker 필요. */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerConfig {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer("mysql:8.4");
    }
}
