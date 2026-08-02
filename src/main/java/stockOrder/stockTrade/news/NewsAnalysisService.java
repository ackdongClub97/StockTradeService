package stockOrder.stockTrade.news;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.kis.ResponseOutputDTO;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NewsAnalysisService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int NEWS_FETCH_COUNT = 30;

    private final NewsService newsService;
    private final KisService kisService;
    private final AnthropicClient anthropicClient;

    private final java.util.Map<String, NewsAnalysisResponse> cache = new ConcurrentHashMap<>();

    public NewsAnalysisService(NewsService newsService, KisService kisService, AnthropicClient anthropicClient) {
        this.newsService = newsService;
        this.kisService = kisService;
        this.anthropicClient = anthropicClient;
    }

    public NewsAnalysisResponse analyze(String code, String name) {
        String cacheKey = code + "_" + LocalDate.now(SEOUL);
        NewsAnalysisResponse cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<HashMap<String, String>> recentArticles = fetchRecentArticles(name);
        if (recentArticles.isEmpty()) {
            return NewsAnalysisResponse.analyzing();
        }

        String direction = resolveDirection(code);

        NewsImpactAnalysis analysis;
        try {
            analysis = requestAnalysis(name, direction, recentArticles);
        } catch (Exception e) {
            log.error("[NewsAnalysis] Claude 분석 실패 code={} : {}", code, e.getMessage());
            return NewsAnalysisResponse.analyzing();
        }

        if (analysis == null || !analysis.significant()) {
            return NewsAnalysisResponse.analyzing();
        }

        NewsAnalysisResponse response = new NewsAnalysisResponse(
                "COMPLETED", direction, analysis.reason(), analysis.keyArticleTitles());
        cache.put(cacheKey, response);
        return response;
    }

    private List<HashMap<String, String>> fetchRecentArticles(String name) {
        List<HashMap<String, String>> items;
        try {
            items = newsService.getStockDetailNews(name, NEWS_FETCH_COUNT).block();
        } catch (Exception e) {
            log.error("[NewsAnalysis] 뉴스 조회 실패 name={} : {}", name, e.getMessage());
            return List.of();
        }
        if (items == null) {
            return List.of();
        }

        ZonedDateTime from = LocalDate.now(SEOUL).minusDays(1).atStartOfDay(SEOUL);
        return items.stream()
                .filter(item -> isWithinRange(item.get("pubDate"), from))
                .collect(Collectors.toList());
    }

    private boolean isWithinRange(String pubDate, ZonedDateTime from) {
        if (pubDate == null) {
            return false;
        }
        try {
            ZonedDateTime published = ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME);
            return !published.isBefore(from);
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveDirection(String code) {
        ResponseOutputDTO data = kisService.getCachedStockData(code);
        if (data == null || data.getStckPrpr() == null) {
            try {
                data = kisService.getStockDetail(code).block();
                if (data != null) {
                    kisService.putCachedStockData(code, data);
                }
            } catch (Exception e) {
                log.error("[NewsAnalysis] 현재가 조회 실패 code={} : {}", code, e.getMessage());
            }
        }
        if (data == null || data.getPrdyCtrt() == null) {
            return "FLAT";
        }
        try {
            double rate = Double.parseDouble(data.getPrdyCtrt());
            if (rate > 0) return "UP";
            if (rate < 0) return "DOWN";
            return "FLAT";
        } catch (NumberFormatException e) {
            return "FLAT";
        }
    }

    private NewsImpactAnalysis requestAnalysis(String name, String direction, List<HashMap<String, String>> articles) {
        String directionKr = switch (direction) {
            case "UP" -> "상승";
            case "DOWN" -> "하락";
            default -> "보합";
        };

        StringBuilder articlesText = new StringBuilder();
        int idx = 1;
        for (HashMap<String, String> article : articles) {
            articlesText.append(idx++).append(". ")
                    .append("제목: ").append(stripHtml(article.get("title"))).append("\n")
                    .append("   요약: ").append(stripHtml(article.get("description"))).append("\n")
                    .append("   발행: ").append(article.get("pubDate")).append("\n");
        }

        String prompt = """
                다음은 한국 주식 종목 "%s"의 오늘 주가 등락 방향과, 어제~오늘 사이 발행된 관련 뉴스 기사 목록입니다.

                오늘 주가 방향: %s

                뉴스 기사 목록:
                %s

                위 기사들이 이 종목의 오늘 주가 방향(%s)을 설명할 만큼 유의미한 근거가 되는지 판단하세요.
                유의미하다면, 실제로 언급된 기사 내용에 기반해서 왜 주가가 %s했는지에 대한 예상 이유를 한국어 한 문단으로 작성하고, 근거로 사용한 기사 제목들을 keyArticleTitles에 담으세요.
                기사 내용이 막연하거나 주가 방향과 직접적인 관련이 없어 보이면 significant를 false로 하고 reason과 keyArticleTitles는 비워두세요.
                추측이나 과장 없이, 기사에 실제로 쓰인 내용에만 근거해서 답하세요.
                """.formatted(name, directionKr, articlesText, directionKr, directionKr);

        StructuredMessageCreateParams<NewsImpactAnalysis> params = MessageCreateParams.builder()
                .model("claude-opus-5")
                .maxTokens(4096L)
                .outputConfig(NewsImpactAnalysis.class)
                .addUserMessage(prompt)
                .build();

        var response = anthropicClient.messages().create(params);

        return response.content().stream()
                .flatMap(cb -> cb.text().stream())
                .findFirst()
                .map(typed -> typed.text())
                .orElse(null);
    }

    private String stripHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]*>", "").replace("&quot;", "\"").replace("&amp;", "&");
    }
}
