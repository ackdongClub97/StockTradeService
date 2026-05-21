package stockOrder.stockTrade.order.dto;

import lombok.Getter;
import lombok.Setter;
import stockOrder.stockTrade.order.domain.OrderType;

@Getter
@Setter
public class OrderRequest {

    private String userId;
    private String stockCode;
    private OrderType orderType;
    private int price;
    private int quantity;
}
