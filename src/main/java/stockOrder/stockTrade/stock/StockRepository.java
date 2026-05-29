package stockOrder.stockTrade.stock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface StockRepository extends JpaRepository<Stock, Long> {

    List<Stock> findByDate(LocalDate date);

    List<Stock> findToByOrderByDate();
}
