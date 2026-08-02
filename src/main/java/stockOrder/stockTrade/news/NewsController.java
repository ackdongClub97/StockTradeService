package stockOrder.stockTrade.news;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;

@RestController
@RequestMapping("/api")
public class NewsController {

    @Autowired
    private NewsService newsService;

    @Autowired
    private NewsAnalysisService newsAnalysisService;

    @GetMapping("/news/{name}")
    public Mono<List<HashMap<String, String>>> getStockDetailNews(@PathVariable String name) {
        return newsService.getStockDetailNews(name);
    }

    @GetMapping("/news/{code}/analysis")
    public NewsAnalysisResponse getNewsAnalysis(@PathVariable String code, @RequestParam String name) {
        return newsAnalysisService.analyze(code, name);
    }

}
