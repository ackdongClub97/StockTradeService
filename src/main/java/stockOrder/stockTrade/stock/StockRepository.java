package stockOrder.stockTrade.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface StockRepository extends JpaRepository<Stock, Long> {

    List<Stock> findByDate(LocalDate date);

    /* 오늘 데이터가 아직 없을 때 "가장 최근 하루치" 스냅샷만 가져오기 위함.
       기존 findToByOrderByDate()는 날짜 필터가 없어 DB에 쌓인 모든 날짜의 행을 다 반환하는 버그가 있었음
       (며칠치 EOD 스냅샷이 누적될수록 랭킹 캐시가 계속 불어나는 원인이었음 - version-update.md 참고). */
    @Query("SELECT s FROM Stock s WHERE s.date = (SELECT MAX(s2.date) FROM Stock s2)")
    List<Stock> findLatestDateSnapshot();
}
