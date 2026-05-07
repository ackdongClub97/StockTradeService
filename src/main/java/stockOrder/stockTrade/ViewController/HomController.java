package stockOrder.stockTrade.ViewController;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import reactor.core.publisher.Mono;

@Controller
public class HomController {

    @GetMapping("/StockTrade/stockHome")
    public Mono<String> rankingPage() {
        return Mono.just("stockHome");
    }

}
