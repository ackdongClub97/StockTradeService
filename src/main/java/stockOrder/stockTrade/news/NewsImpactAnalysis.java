package stockOrder.stockTrade.news;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

public record NewsImpactAnalysis(
        @JsonPropertyDescription("오늘 주가 변동을 설명할 만큼 유의미한 뉴스가 있는지 여부")
        boolean significant,

        @JsonPropertyDescription("주가 변동에 대한 예상 이유를 한국어 한 문단으로 서술. significant가 false이면 빈 문자열")
        String reason,

        @JsonPropertyDescription("이유의 근거로 사용된 기사 제목 목록. significant가 false이면 빈 리스트")
        List<String> keyArticleTitles
) {
}
