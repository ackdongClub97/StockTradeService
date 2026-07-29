package stockOrder.stockTrade.kis;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/* 실시간 호가(H0STASP0) - 매도/매수 10단계 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class AskingPriceDTO {

    private String stockCode;

    private String[] askPrices = new String[10];   // 매도호가 1~10
    private String[] askVolumes = new String[10];  // 매도호가 잔량 1~10

    private String[] bidPrices = new String[10];   // 매수호가 1~10
    private String[] bidVolumes = new String[10];  // 매수호가 잔량 1~10

    private String totalAskVolume;  // 총 매도호가 잔량
    private String totalBidVolume;  // 총 매수호가 잔량
}
