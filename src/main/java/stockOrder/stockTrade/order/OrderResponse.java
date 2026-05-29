package stockOrder.stockTrade.order;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrderResponse {

    private String ordNo;
    private int matchedQuantity;
    private int matchedPrice;
    private String ordTime;
    private String rtCd;
    private String msg;

}
