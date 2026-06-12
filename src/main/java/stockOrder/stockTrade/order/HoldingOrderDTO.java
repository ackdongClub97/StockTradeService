package stockOrder.stockTrade.order;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class HoldingOrderDTO {
    private String memberId;
    private String stockCode;
    private String stockName;
    private Long buyPrice;
    private Long sellPrice;
    private Long buyQuantity;
    private Long sellQuantity;
    private Long avgPrice;
}
