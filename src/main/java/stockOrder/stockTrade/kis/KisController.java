package stockOrder.stockTrade.kis;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
public class KisController {

    private KisService kisService;

    @Autowired
    public KisController(KisService kisService) {
        this.kisService = kisService;
    }

    @PostMapping("/fetch-stock-data")
    public String fetchStockData(
            @RequestParam String marketCode,
            @RequestParam String stockCode, // SK하이닉스 000660, 삼성전자: 005930
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam String periodCode) {
        //kisService.saveStockData(marketCode, stockCode, startDate, endDate, periodCode);
        return stockCode+" Stock data fetched and saved successfully";
    }

    @GetMapping("/volume-rank")
    public Mono<List<ResponseOutputDTO>> getVolumeRank() {
        return kisService.getVolumeRank();
    }


}