package stockOrder.stockTrade.order;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrderRequest {

    private String userId;
    private String stockCode;
    private OrderType orderType;
    private int price;
    private int quantity;
}
