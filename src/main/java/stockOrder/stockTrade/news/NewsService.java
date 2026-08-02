package stockOrder.stockTrade.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;

@Service
public class NewsService {

    @Value("${naver-openapi.clientId}")
    private String clientId;

    @Value("${naver-openapi.clientSecret}")
    private String clientSecret;

    private final ObjectMapper objectMapper;

    public NewsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

     /* naver news */
     public Mono<List<HashMap<String, String>>> getStockDetailNews(String stockName) {
         return getStockDetailNews(stockName, 5);
     }

     public Mono<List<HashMap<String, String>>> getStockDetailNews(String stockName, int display) {
         return WebClient.create("https://openapi.naver.com")
                 .get()
                 .uri(uriBuilder -> uriBuilder.path("/v1/search/news.json")
                         .queryParam("query", stockName)
                         .queryParam("display", display)
                         .queryParam("start", 1)
                         .queryParam("sort", "date")
                         .build()
                 ).header("X-Naver-Client-Id", clientId)
                 .header("X-Naver-Client-Secret", clientSecret)
                 .retrieve()
                 .bodyToMono(String.class)  // ← String으로 받기
                 .map(response -> {
                     try {
                         JsonNode items = objectMapper.readTree(response).get("items");
                         return objectMapper.convertValue(items,
                                 new com.fasterxml.jackson.core.type.TypeReference<List<HashMap<String, String>>>(){});
                     } catch (Exception e) {
                         throw new RuntimeException(e);
                     }
                 });
     }

}
