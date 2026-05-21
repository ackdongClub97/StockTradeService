package stockOrder.stockTrade.stock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stockOrder.stockTrade.stock.domain.Stock;

import java.time.LocalDate;
import java.util.List;

public interface StockRepository extends JpaRepository<Stock, Long> {

    List<Stock> findByDate(LocalDate date);

    List<Stock> findToByOrderByDate();
}
