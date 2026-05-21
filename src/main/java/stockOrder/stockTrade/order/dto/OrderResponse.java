package stockOrder.stockTrade.order.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import stockOrder.stockTrade.order.domain.Order;
import stockOrder.stockTrade.order.domain.OrderStatus;
import stockOrder.stockTrade.order.domain.OrderType;

import java.time.LocalDateTime;

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
