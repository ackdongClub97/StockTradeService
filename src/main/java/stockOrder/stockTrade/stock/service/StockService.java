package stockOrder.stockTrade.stock.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stockOrder.stockTrade.stock.domain.Stock;
import stockOrder.stockTrade.stock.repository.StockRepository;

@Service
@Transactional(readOnly = true)
public class StockService {

    @Autowired
    private StockRepository stockRepository;

    @Transactional
    public void saveStockData(Stock stock) {
        stockRepository.save(stock);
    }
}
