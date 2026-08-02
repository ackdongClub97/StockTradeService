package stockOrder.stockTrade.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/* 관리자 화면에서 "지정 시점의 로그"를 보여주기 위해 Loki HTTP API(query_range)를 그대로 호출하는 얇은 클라이언트.
   Grafana 없이도 이 서비스가 직접 Loki에 물어봐서 로그 줄을 가져온다. */
@Service
@Slf4j
public class LokiClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LokiClient(@Value("${loki.url:http://localhost:3100}") String lokiUrl) {
        this.webClient = WebClient.builder().baseUrl(lokiUrl).build();
    }

    public record LogLine(String timestamp, String line) {}

    public List<LogLine> queryRange(String query, Instant start, Instant end, int limit) {
        try {
            // LogQL 쿼리 자체에 `{...}`가 들어있어서, queryParam에 값을 바로 넣으면 UriComponentsBuilder가
            // 그 중괄호를 URI 템플릿 변수로 오인해서 "Not enough variable values..." 에러를 냄.
            // 그래서 각 값을 {query}/{start}/{end}/{limit} 같은 진짜 템플릿 변수로 선언하고 build(...)에 실제 값을 넘겨서 치환한다.
            // Spring Boot 4의 WebClient 기본 JSON 디코더는 Jackson 3(tools.jackson.*) 기준이라
            // 여기서 쓰는 Jackson 2(com.fasterxml.jackson.databind) JsonNode로 바로 디코딩하면 타입 매칭에 실패한다.
            // 그래서 응답을 문자열로 받은 뒤 우리가 직접 갖고 있는 Jackson 2 ObjectMapper로 파싱한다.
            String rawJson = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/loki/api/v1/query_range")
                            .queryParam("query", "{query}")
                            .queryParam("start", "{start}")
                            .queryParam("end", "{end}")
                            .queryParam("limit", "{limit}")
                            .queryParam("direction", "forward")
                            .build(query, start.toString(), end.toString(), limit))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (rawJson == null) return List.of();
            JsonNode root = objectMapper.readTree(rawJson);

            List<LogLine> lines = new ArrayList<>();
            JsonNode results = root.path("data").path("result");
            for (JsonNode stream : results) {
                for (JsonNode value : stream.path("values")) {
                    String nanos = value.get(0).asText();
                    String line = value.get(1).asText();
                    lines.add(new LogLine(formatTimestamp(nanos), line));
                }
            }
            lines.sort(Comparator.comparing(LogLine::timestamp));
            return lines;
        } catch (Exception e) {
            log.warn("[LokiClient] 로그 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private static String formatTimestamp(String epochNanos) {
        long nanos = new BigDecimal(epochNanos).longValue();
        Instant instant = Instant.ofEpochSecond(0, nanos);
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(ZoneId.of("Asia/Seoul"))
                .format(instant);
    }
}
