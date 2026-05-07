package stockOrder.stockTrade.stock.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import stockOrder.stockTrade.stock.domain.Stock;

@Controller
@Slf4j
public class StockContoller {

    public void stockDataSave(String id) {
        Stock stock = new Stock();
    }

}
