package stockOrder.stockTrade.kis;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.WebClient;
import stockOrder.stockTrade.stock.Stock;
import stockOrder.stockTrade.stock.StockRepository;
import stockOrder.stockTrade.token.TokenService;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class KisService {

    @Value("${hantu-openapi.appkey}")
    private String appKey;

    @Value("${hantu-openapi.appsecret}")
    private String appSecret;

    @Value("${hantu-openapi.appUrl}")
    private String apiUrl;

    private final TokenService tokenService;
    private String token;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private StockRepository stockRepository;

    @PostConstruct
    public void init() throws IOException, InterruptedException {
        this.token = tokenService.fetchToken();
    }

    @Autowired
    public KisService(TokenService tokenService, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) throws IOException, InterruptedException {
        this.tokenService = tokenService;
        this.webClient = webClientBuilder.baseUrl(apiUrl).build();
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

    public String dayStockData(String startDate, String endDate) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = createVolumeRankHttpHeaders();

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString( apiUrl + "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                        .queryParam("FID_INPUT_ISCD", "005930")
                                        .queryParam("FID_INPUT_DATE_1", startDate)
                                        .queryParam("FID_INPUT_DATE_2", endDate)
                                        .queryParam("FID_PERIOD_DIV_CODE", "D")
                                        .queryParam("FID_ORG_ADJ_PRC", "0");

        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                builder.toUriString(),
                HttpMethod.GET,
                entity,
                String.class);
        return response.getBody();
    }

    public void saveStockData(String responseBody, String stockCode) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode1 = mapper.readTree(responseBody);
            JsonNode jsonNode2 = jsonNode1.get("output2");

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

            for(JsonNode node : jsonNode2){
                Stock stock =  new Stock();
                stock.setStockCode(stockCode);
                stock.setDate(LocalDate.parse(node.get("stck_bsop_date").asText(), formatter));
                stock.setMaxPrice(node.get("stck_hgpr").asText());
                stock.setMinPrice(node.get("stck_lwpr").asText());
                stock.setAccumTrans(node.get("acml_vol").asText());
                stock.setOpenPrice(node.get("stck_oprc").asText());
                stock.setClosePrice(node.get("stck_clpr").asText());

                stockRepository.save(stock);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
