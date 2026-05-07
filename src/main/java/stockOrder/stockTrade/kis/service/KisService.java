package stockOrder.stockTrade.kis.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Sinks;
import stockOrder.stockTrade.kis.dto.ResponseOutputDTO;
import stockOrder.stockTrade.stock.repository.StockRepository;
import stockOrder.stockTrade.token.TokenService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class KisService {

    @Value("${hantu-openapi.appkey}")
    private String appKey;

    @Value("${hantu-openapi.appsecret}")
    private String appSecret;

    @Value("${hantu-openapi.appUrl}")
    private String apiUrl;


    private String token;
    private WebClient webClient;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    // 실시간 랭킹 캐시
    private final List<ResponseOutputDTO> cachedRanking = new CopyOnWriteArrayList<>();

    // 모든 user가 최신 데이터 받을 수 있음
    private final Sinks.Many<List<ResponseOutputDTO>> rankSink = Sinks.many().replay().latest();

    @PostConstruct
    public void init() throws IOException, InterruptedException {
        this.token = tokenService.fetchToken();
        this.webClient = WebClient.builder().baseUrl(apiUrl).build();
    }

    @Autowired
    public KisService(TokenService tokenService, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) throws IOException, InterruptedException {
        this.tokenService = tokenService;
        this.objectMapper =  objectMapper;
    }

    private HttpHeaders createVolumeRankHttpHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("appkey", appKey);
        headers.set("appSecret", appSecret);
        headers.set("tr_id", "FHPST01710000");
        headers.set("custtype", "P");

        return headers;
    }

    private Mono<List<ResponseOutputDTO>> parseFVolumeRank(String response) {
        try {
            List<ResponseOutputDTO> responseDataList = new ArrayList<>();
            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode outputNode = rootNode.get("output");
            if (outputNode != null) {
                for (JsonNode node : outputNode) {
                    ResponseOutputDTO responseData = new ResponseOutputDTO();
                    responseData.setHtsKorIsnm(node.get("hts_kor_isnm").asText());
                    responseData.setMkscShrnIscd(node.get("mksc_shrn_iscd").asText());
                    responseData.setDataRank(node.get("data_rank").asText());
                    responseData.setStckPrpr(node.get("stck_prpr").asText());
                    responseData.setPrdyVrssSign(node.get("prdy_vrss_sign").asText());
                    responseData.setPrdyVrss(node.get("prdy_vrss").asText());
                    responseData.setPrdyCtrt(node.get("prdy_ctrt").asText());
                    responseData.setAcmlVol(node.get("acml_vol").asText());
                    responseData.setPrdyVol(node.get("prdy_vol").asText());
                    responseData.setLstnStcn(node.get("lstn_stcn").asText());
                    responseData.setAvrgVol(node.get("avrg_vol").asText());
                    responseData.setNBefrClprVrssPrprRate(node.get("n_befr_clpr_vrss_prpr_rate").asText());
                    responseData.setVolInrt(node.get("vol_inrt").asText());
                    responseData.setVolTnrt(node.get("vol_tnrt").asText());
                    responseData.setNdayVolTnrt(node.get("nday_vol_tnrt").asText());
                    responseData.setAvrgTrPbmn(node.get("avrg_tr_pbmn").asText());
                    responseData.setTrPbmnTnrt(node.get("tr_pbmn_tnrt").asText());
                    responseData.setNdayTrPbmnTnrt(node.get("nday_tr_pbmn_tnrt").asText());
                    responseData.setAcmlTrPbmn(node.get("acml_tr_pbmn").asText());
                    responseDataList.add(responseData);
                }
            }
            return Mono.just(responseDataList);
        } catch (Exception e) {
            return Mono.error(e);
        }
    }
    public Mono<List<ResponseOutputDTO>> getVolumeRank() {
        HttpHeaders headers = createVolumeRankHttpHeaders();

        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/uapi/domestic-stock/v1/quotations/volume-rank")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_COND_SCR_DIV_CODE", "20171")
                        .queryParam("FID_INPUT_ISCD", "0002")
                        .queryParam("FID_DIV_CLS_CODE", "0")
                        .queryParam("FID_BLNG_CLS_CODE", "0")
                        .queryParam("FID_TRGT_CLS_CODE", "111111111")
                        .queryParam("FID_TRGT_EXLS_CLS_CODE", "000000")
                        .queryParam("FID_INPUT_PRICE_1", "0")
                        .queryParam("FID_INPUT_PRICE_2", "0")
                        .queryParam("FID_VOL_CNT", "0")
                        .queryParam("FID_INPUT_DATE_1", "0")
                        .build())
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(response -> parseFVolumeRank(response));

    }

    @Scheduled(fixedDelayString = "${kis.rank.refresh-interval-ms:30000}", initialDelay = 0)
    public void refreshRank() {
        log.info("KisService refresh");

        getVolumeRank().subscribe(
          list -> {
              cachedRanking.clear();
              cachedRanking.addAll(list);

              rankSink.tryEmitNext(List.copyOf(cachedRanking));
              list.stream().limit(5).forEach(dto ->
                      log.debug("  {}위 {} | {}원 | {}%",
                              dto.getDataRank(), dto.getHtsKorIsnm(),
                              dto.getStckPrpr(), dto.getPrdyCtrt()
                      )
              );
          }, err -> log.error(err.getMessage(), err)
        );

    }

    /* ranking data 즉시 반환 */
    public List<ResponseOutputDTO> getCachedRanking() {
        return List.copyOf(cachedRanking);
    }

    /* SSE 스트림 */
    public Flux<List<ResponseOutputDTO>> getRankingStream(){
        return rankSink.asFlux();
    }

}
