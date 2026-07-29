package stockOrder.stockTrade.order;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/* 체결 원장(Trade)을 시간순으로 훑으면서 종목별 가중평균 매입단가/잔여수량을 추적한다.
   그 과정에서 매도가 나올 때마다 (매도가 - 그 시점 평균매입단가) * 매도수량 만큼을 실현손익(매매차익)으로 기록하고,
   최종적으로 남은 잔여수량/평단가는 보유종목 계산에 그대로 재사용한다. */
@Service
@RequiredArgsConstructor
public class RealizedPnlService {

    private final TradeRepository tradeRepository;

    @Getter
    public static class Position {
        private final String stockCode;
        private String stockName;
        private double avgCost;
        private long quantity;

        Position(String stockCode) {
            this.stockCode = stockCode;
        }
    }

    private static class WalkResult {
        Map<String, Position> positions = new LinkedHashMap<>();
        Map<LocalDate, Long> dailyRealizedPnl = new TreeMap<>();
    }

    /* 보유주식 SSE(holding/stream)가 가격 틱마다(초당) 이 서비스를 호출하는데, 매번 DB를 새로 훑으면
       커넥션 풀을 금방 소모한다. 회원 단위로 짧게 캐싱하고, 실제 체결(Trade)이 새로 생길 때만 무효화한다. */
    private static final long CACHE_TTL_MS = 1500;
    private final Map<String, CachedWalk> walkCache = new ConcurrentHashMap<>();

    private record CachedWalk(WalkResult result, long cachedAt) {}

    public void invalidate(String memberId) {
        walkCache.remove(memberId);
    }

    private WalkResult walk(String memberId) {
        CachedWalk cached = walkCache.get(memberId);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.cachedAt()) < CACHE_TTL_MS) {
            return cached.result();
        }

        List<Trade> trades = tradeRepository.findByMemberIdOrderByMatchedAtAsc(memberId);
        WalkResult result = new WalkResult();

        for (Trade t : trades) {
            Position pos = result.positions.computeIfAbsent(t.getStockCode(), Position::new);
            pos.stockName = t.getStockName();

            long qty = t.getMatchedQuantity();
            int price = t.getMatchedPrice();
            LocalDate date = t.getMatchedAt().toLocalDate();

            if (t.getOrderType() == OrderType.BUY) {
                long newQty = pos.quantity + qty;
                pos.avgCost = newQty == 0 ? 0 : (pos.avgCost * pos.quantity + (double) price * qty) / newQty;
                pos.quantity = newQty;
            } else {
                long sellQty = Math.min(qty, pos.quantity);
                long realized = Math.round((price - pos.avgCost) * sellQty);

                result.dailyRealizedPnl.merge(date, realized, Long::sum);
                pos.quantity -= sellQty;
            }
        }

        walkCache.put(memberId, new CachedWalk(result, now));
        return result;
    }

    /* 종목별 잔여수량(현재 보유수량)/평단가 - 보유종목 계산에 재사용 */
    public Map<String, Position> getPositions(String memberId) {
        return walk(memberId).positions;
    }

    public long getRemainingQuantity(String memberId, String stockCode) {
        Position pos = walk(memberId).positions.get(stockCode);
        return pos != null ? pos.quantity : 0;
    }

    /* 특정 종목의 잔여수량/평단가 - 없으면 0짜리 빈 포지션 */
    public Position getPosition(String memberId, String stockCode) {
        Position pos = walk(memberId).positions.get(stockCode);
        return pos != null ? pos : new Position(stockCode);
    }

    public Map<String, Object> getRealizedPnl(String memberId, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> daily = walk(memberId).dailyRealizedPnl;

        List<Map<String, Object>> series = new ArrayList<>();
        long total = 0;

        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long pnl = daily.getOrDefault(d, 0L);
            total += pnl;
            series.add(Map.of("date", d.toString(), "pnl", pnl));
        }

        return Map.of("total", total, "series", series);
    }
}
