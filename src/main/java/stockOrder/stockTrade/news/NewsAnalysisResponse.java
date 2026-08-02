package stockOrder.stockTrade.news;

import java.util.List;

public record NewsAnalysisResponse(
        String status,       // "ANALYZING" | "COMPLETED"
        String direction,    // "UP" | "DOWN" | "FLAT" | null (status=ANALYZING면 null)
        String reason,       // null이면 ANALYZING
        List<String> articleTitles
) {
    public static NewsAnalysisResponse analyzing() {
        return new NewsAnalysisResponse("ANALYZING", null, null, List.of());
    }
}
