package stockOrder.stockTrade.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
public class Stock {

    @Id
    @GeneratedValue
    @Column(name="stock_id")
    private Long id;
    private String stockCode;
    private String maxPrice;
    private String minPrice;
    private String accumTrans;
    private String openPrice;
    private String closePrice;
    private LocalDate date;

}
