package stockOrder.stockTrade;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.kis.KisWebSocketService;
import stockOrder.stockTrade.support.MySqlContainerConfig;

/* 전체 컨텍스트 기동 확인. 예전엔 운영 파일 DB(~/stockTrade)를 그대로 열고, 기동 시 KisService가 실제 KIS 토큰 API를
   호출해서 연속 실행하면 KIS 토큰 발급 제한에 걸려 실패했음(2026-10-30). 이제 외부 연동은 전부 끊고 기동만 확인한다.
   - DB: MySQL 컨테이너(MySqlContainerConfig)
   - KIS REST/웹소켓: Mock (기동 시 토큰 발급·웹소켓 연결·시세 스케줄러 호출 안 함)
   - Kafka: 리스너 자동 시작 끔 (로컬 브로커 없이도 기동)
   - 카카오/네이버/KIS/Anthropic 키: 더미 값 (application-secret.yaml 없이도 기동) */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.config.import=",
        "spring.security.oauth2.client.registration.kakao.client-id=test",
        "spring.security.oauth2.client.registration.kakao.client-secret=test",
        "hantu-openapi.appkey=test",
        "hantu-openapi.appsecret=test",
        "naver-openapi.clientId=test",
        "naver-openapi.clientSecret=test",
})
@Import(MySqlContainerConfig.class)
class StockTradeApplicationTests {

	@MockitoBean
	private KisService kisService;

	@MockitoBean
	private KisWebSocketService kisWebSocketService;

	@Test
	void contextLoads() {
	}

}
