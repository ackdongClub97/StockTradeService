package stockOrder.stockTrade.ViewController;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;

@Controller
public class PageController {

    // main page
    @GetMapping("/stockHome")
    public Mono<String> rankingPage() {
        return Mono.just("stockHome");
    }

    // stock Detail page
    @GetMapping("/stock/{code}")
    public String stockDetail(@PathVariable String code, @RequestParam String name, Model model) {
        model.addAttribute("code", code);
        model.addAttribute("name", name);

        return "stockDetail";
    }




}
