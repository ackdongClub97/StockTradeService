package stockOrder.stockTrade.news.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import stockOrder.stockTrade.news.service.NewsService;

import java.util.HashMap;
import java.util.List;

@RestController
@RequestMapping("/api")
public class NewsController {

    @Autowired
    private NewsService newsService;

    @GetMapping("/news/{name}")
    public Mono<List<HashMap<String, String>>> getStockDetailNews(@PathVariable String name) {
        return newsService.getStockDetailNews(name);
    }

}
