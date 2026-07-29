package stockOrder.stockTrade.token;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenService {
    @Value("${hantu-openapi.appkey}")
    private String appKey;

    @Value("${hantu-openapi.appsecret}")
    private String appSecret;

    @Value("${hantu-openapi.appUrl}")
    private String apiUrl;


    public String fetchToken() throws IOException, InterruptedException {
        String url = apiUrl + "/oauth2/tokenP";
        String json = "{\"grant_type\":\"client_credentials\",\"appkey\":\""
                + appKey + "\",\"appsecret\":\"" + appSecret + "\"}";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        String body = response.body();
        log.info("[TokenService] {}", body);

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode node = objectMapper.readTree(body);
        String token = node.get("access_token").asText();

        if(token == null || token.isEmpty()) {
            throw new RuntimeException("token is empty: " + body);
        }

        return token;
    }

    /* 웹소켓 접속키 발급 - REST 접근토큰과 별개로 발급받아야 함 */
    public String fetchApprovalKey() throws IOException, InterruptedException {
        String url = apiUrl + "/oauth2/Approval";
        String json = "{\"grant_type\":\"client_credentials\",\"appkey\":\""
                + appKey + "\",\"secretkey\":\"" + appSecret + "\"}";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        String body = response.body();
        log.info("[TokenService] {}", body);

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode node = objectMapper.readTree(body);
        String approvalKey = node.get("approval_key").asText();

        if(approvalKey == null || approvalKey.isEmpty()) {
            throw new RuntimeException("approval_key is empty: " + body);
        }

        return approvalKey;
    }

}
