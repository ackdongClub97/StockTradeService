package stockOrder.stockTrade.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Getter
@Setter
public class Stock {

    @Id
    @GeneratedValue
    @Column(name="stock_id")
    private Long id;
    // HTS 한글 종목명
    private String htsKorIsnm;

    // 유가증권 단축 종목코드
    private String mkscShrnIscd;

    // 데이터 순위
    private String dataRank;

    // 주식 현재가
    private String stckPrpr;

    // 전일 대비
    private String prdyVrss;

    // 전일 대비율
    private String prdyCtrt;

    // 누적 거래량
    private String acmlVol;

    // 전일 거래량
    private String prdyVol;

    private LocalDate date;
}
