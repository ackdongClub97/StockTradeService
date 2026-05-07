package stockOrder.stockTrade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StockTradeApplication {

	public static void main(String[] args) {

		SpringApplication.run(StockTradeApplication.class, args);

	}

}
