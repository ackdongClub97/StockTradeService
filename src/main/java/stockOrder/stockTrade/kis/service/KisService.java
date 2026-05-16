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
import stockOrder.stockTrade.token.TokenService;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final AtomicInteger memberCount = new AtomicInteger(0);

    // 실시간 랭킹 캐시
    private final List<ResponseOutputDTO> cachedData = new CopyOnWriteArrayList<>();
    // 실시간 주식 캐시
    private final Map<String, ResponseOutputDTO> cachedStockData = new ConcurrentHashMap<>();


    // 모든 user가 최신 랭킹 데이터 받을 수 있음
    private final Sinks.Many<List<ResponseOutputDTO>> rankSink = Sinks.many().replay().latest();
    // 모든 user가 최신 주식 데이터 받을 수 있음
    private final Map<String,Sinks.Many<ResponseOutputDTO>> detailSink = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws IOException, InterruptedException {
        this.token = tokenService.fetchToken();
        this.webClient = WebClient.builder().baseUrl(apiUrl).build();
    }

    /* 24시간 만료라 12시간마다 갱신 */
    @Scheduled(fixedRate = 12 * 60 * 60 * 1000, initialDelay = 12 * 60 * 60 * 1000)
    public void refreshToken() {
        try {
            this.token = tokenService.fetchToken();
            log.info("[Token] 토큰 갱신 완료");
        } catch (Exception e) {
            log.error("[Token] 토큰 갱신 실패: {}", e.getMessage());
        }
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
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

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
                        .queryParam("FID_INPUT_DATE_1", today)
                        .build())
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(response -> parseFVolumeRank(response));

    }

    @Scheduled(fixedDelayString = "${kis.rank.refresh-interval-ms:30000}", initialDelay = 0)
    public void refreshRank() {
        if(memberCount.get() == 0) {
            log.info(" 랭킹 페이지 조회 없음 스킵");
            return;
        }

        if(!isMarketOpen()) {
            log.info("랭킹 장마감 시간 스킵");
            return;
        }

        log.info("ranking refresh");

        getVolumeRank().subscribe(
                list -> {
                    cachedData.clear();
                    cachedData.addAll(list);
                    rankSink.tryEmitNext(List.copyOf(cachedData));
                }, err -> log.error(err.getMessage(), err)
        );

    }

    /* ranking data 즉시 반환 */
    public List<ResponseOutputDTO> getCachedRanking() {
        return List.copyOf(cachedData);
    }

    /* SSE 스트림 */
    public Flux<List<ResponseOutputDTO>> getRankingStream(){
        return rankSink.asFlux()
                .doOnSubscribe(s -> {
                            memberCount.incrementAndGet();
                            log.info("ranking member {}", memberCount.get());
                            // 즉시 1회 조회
                            getVolumeRank().subscribe(
                                data -> rankSink.tryEmitNext(data),
                                err -> log.error("랭킹 조회 실패: {}",  err.getMessage())
                            );
                })
                .doOnCancel(() -> {
                    memberCount.decrementAndGet();
                    log.info("랭킹 페이지 이탈 : {} ", memberCount.get());
                })
                .doOnTerminate(() -> {
                   memberCount.decrementAndGet();
                });
    }

    /* stock 상세 조회 */
    private Mono<ResponseOutputDTO> parseStockDetail(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode output = root.get("output");
            ResponseOutputDTO stockData = new ResponseOutputDTO();

            stockData.setStckPrpr(output.get("stck_prpr").asText());
            stockData.setPrdyCtrt(output.get("prdy_ctrt").asText());
            stockData.setPrdyVrss(output.get("prdy_vrss").asText());
            stockData.setAcmlVol(output.get("acml_vol").asText());

            return Mono.just(stockData);
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    public Mono<ResponseOutputDTO> getStockDetail(String code) {
        return webClient.get().uri(
                uriBuilder -> uriBuilder.path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", code)
                        .build())
                .headers(h -> {
                    h.setBearerAuth(token);
                    h.set("appkey", appKey);
                    h.set("appsecret", appSecret);
                    h.set("tr_id", "FHKST01010100");
                    h.set("custtype", "P");
                }).retrieve().bodyToMono(String.class).flatMap(this::parseStockDetail);
    }

    public Flux<ResponseOutputDTO> getStockDetailStream(String code) {
        Sinks.Many<ResponseOutputDTO> sink =  detailSink.computeIfAbsent(code, k ->
                Sinks.many().replay().latest());

        return sink.asFlux()
                .doOnSubscribe(s -> {
                    log.info("{} 종목 조회", code);
                    getStockDetail(code).subscribe(
                        data -> sink.tryEmitNext(data),
                        err -> log.error("{} 종목 조회 실패 / {}", code, err.getMessage())
                    );
                })
                .doOnCancel(() -> {
                    detailSink.remove(code);
                    log.info("{} 종목 조회 종료", code);
                })
                .doOnTerminate(() -> {
                    detailSink.remove(code);
                })
                .onErrorResume(e -> {           // ← 에러 시 새 Sink로 교체
                    detailSink.remove(code);
                    return getStockDetailStream(code);
                });
    }

    @Scheduled(fixedDelay = 10000)
    public void refreshStockDetail() {
        if(detailSink.isEmpty()) return;

        if(!isMarketOpen()) {
            detailSink.forEach((code, sink) -> {
                log.info("장 마감 시간 detail 조회 스킵");
                return;
            });
        }
        detailSink.keySet().forEach(code ->
                getStockDetail(code).subscribe(
                        data -> {
                            cachedStockData.put(code, data);
                            detailSink.get(code).tryEmitNext(data);

                        }, err -> log.error("stock detail error: {} / {}", code ,err.getMessage())
                ));
    }

    private boolean isMarketOpen() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Seoul"));
        LocalTime open = LocalTime.of(9, 0);
        LocalTime close = LocalTime.of(15, 30);

        DayOfWeek day = LocalDate.now(ZoneId.of("Asia/Seoul")).getDayOfWeek();
        boolean isWeekday  = day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;

        return isWeekday && now.isAfter(open) && now.isBefore(close);
    }
}
