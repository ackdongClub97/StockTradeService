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

        // 응답 본문을 그대로 로그로 남기지 않는다 - 예전엔 log.info("[TokenService] {}", body)로 접근토큰 전체가
        // 로그(그리고 Loki)에 남았음. 토큰은 24시간 동안 KIS API를 호출할 수 있는 자격증명이라 로그에 있으면 안 됨.
        JsonNode node = new ObjectMapper().readTree(response.body());
        JsonNode token = node.get("access_token");
        if (token == null || token.asText().isEmpty()) {
            throw new IllegalStateException("접근토큰 발급 실패 HTTP " + response.statusCode() + " " + errorOf(node));
        }
        return token.asText();
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

        JsonNode node = new ObjectMapper().readTree(response.body());
        JsonNode approvalKey = node.get("approval_key");
        if (approvalKey == null || approvalKey.asText().isEmpty()) {
            throw new IllegalStateException("웹소켓 승인키 발급 실패 HTTP " + response.statusCode() + " " + errorOf(node));
        }
        return approvalKey.asText();
    }

    /* 실패 응답에서 원인만 추린다(성공 응답이 섞여도 토큰이 새지 않게 본문 전체는 안 씀) - KIS는 error_code/error_description 또는 msg_cd/msg1 */
    private static String errorOf(JsonNode node) {
        for (String[] pair : new String[][]{{"error_code", "error_description"}, {"msg_cd", "msg1"}}) {
            if (node.hasNonNull(pair[0]) || node.hasNonNull(pair[1])) {
                return node.path(pair[0]).asText() + " " + node.path(pair[1]).asText();
            }
        }
        return "(응답에 오류 내용 없음)";
    }

}
