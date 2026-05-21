package stockOrder.stockTrade.kis.service;

import io.netty.handler.codec.PrematureChannelClosureException;
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
import reactor.util.retry.Retry;
import stockOrder.stockTrade.kis.dto.ResponseOutputDTO;
import stockOrder.stockTrade.order.domain.Order;
import stockOrder.stockTrade.order.dto.OrderResponse;
import stockOrder.stockTrade.stock.domain.Stock;
import stockOrder.stockTrade.stock.repository.StockRepository;
import stockOrder.stockTrade.token.TokenService;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


@Service
@Slf4j
public class KisService {

    @Value("${hantu-openapi.appkey}")
    private String appkey;

    @Value("${hantu-openapi.appsecret}")
    private String appsecret;

    @Value("${hantu-openapi.appUrl}")
    private String apiUrl;

    @Autowired
    private StockRepository stockRepository;


    private String token;
    private boolean saveToday = false;
    private WebClient webClient;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;
   // private final AtomicInteger memberCount = new AtomicInteger(0);

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
        restartFromDb();
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
        headers.set("appkey", appkey);
        headers.set("appsecret", appsecret);
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
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> {
                                    log.error("RANK ERROR BODY = {}", body);
                                    return Mono.error(new RuntimeException(body));
                                })
                ).bodyToMono(String.class)
                .flatMap(this::parseFVolumeRank)
                .retryWhen(
                        Retry.backoff(3, Duration.ofSeconds(1))
                                .filter(e -> e instanceof PrematureChannelClosureException)
                                .doBeforeRetry(s -> log.debug("랭킹 연결 재시도 중...."))
                );

    }

    @Scheduled(fixedDelayString = "${kis.rank.refresh-interval-ms:15000}", initialDelay = 0)
    public void refreshRank() {
        if(!isMarketOpen()) {
            if(!saveToday && !cachedData.isEmpty()) {
                saveStockEndData(List.copyOf(cachedData));
                saveToday = true;
                log.info("장마감 최종 랭킹 저장");
            }

            if(!cachedData.isEmpty()) {
                rankSink.tryEmitNext(List.copyOf(cachedData));
            }
            return;
        }

        // 장열리면 초기화
        saveToday = false;

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
                            // 장마감 전
                            if(cachedData.isEmpty() || isMarketOpen()) {
                                getVolumeRank().subscribe(
                                        data -> rankSink.tryEmitNext(data),
                                        err -> log.error("랭킹 조회 실패: {}",  err.getMessage())
                                );
                            } else {
                                // 장마감 후
                                if(!cachedData.isEmpty()) {
                                    rankSink.tryEmitNext(List.copyOf(cachedData));
                                    log.info("장마감 - 캐시 데이터 전송: {}", List.copyOf(cachedData));
                                }
                            }
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
                    h.set("appkey", appkey);
                    h.set("appsecret", appsecret);
                    h.set("tr_id", "FHKST01010100");
                    h.set("custtype", "P");
                }).retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> {
                                    log.error("KIS ERROR BODY = {}", body);
                                    return Mono.error(new RuntimeException(body));
                                })
                ).bodyToMono(String.class)
                .flatMap(this::parseStockDetail)
                .retryWhen(
                        Retry.backoff(3, Duration.ofSeconds(1))
                                .filter(e -> e instanceof PrematureChannelClosureException)
                                .doBeforeRetry(s -> log.debug("detail 연결 재시도 중...."))
                );
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

    @Scheduled(fixedDelay = 15000, initialDelay = 9000)
    public void refreshStockDetail() {
        if(detailSink.isEmpty()) return;

        if(!isMarketOpen()) {
            log.info("장 마감 시간 detail 조회 스킵");
            return;
        }
        log.info("refresh detail");
        detailSink.keySet().forEach(code ->
                getStockDetail(code)
                        .delaySubscription(Duration.ofSeconds(1))
                        .subscribe(
                        data -> {
                            cachedStockData.put(code, data);
                            Sinks.Many<ResponseOutputDTO> sink = detailSink.get(code);
                            if(sink != null) {
                                sink.tryEmitNext(data);
                            }
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

    /*  장마감 데이터 저장  */
    private void saveStockEndData(List<ResponseOutputDTO> list) {
        LocalDate today = LocalDate.now();

        List<Stock> stockList = list.stream().map(dto -> {
            Stock stock = new Stock();
            stock.setDate(today);
            stock.setHtsKorIsnm(dto.getHtsKorIsnm());
            stock.setMkscShrnIscd(dto.getMkscShrnIscd());
            stock.setDataRank(dto.getDataRank());
            stock.setStckPrpr(dto.getStckPrpr());
            stock.setPrdyVrss(dto.getPrdyVrss());
            stock.setPrdyCtrt(dto.getPrdyCtrt());
            stock.setAcmlVol(dto.getAcmlVol());
            stock.setPrdyVol(dto.getPrdyVol());

            return stock;
        }).toList();

        stockRepository.saveAll(stockList);
        log.info("장마감 데이터 db 저장 : {} 건",  stockList.size());
    }

    private void restartFromDb() {
        LocalDate today = LocalDate.now();
        List<Stock> saved = stockRepository.findByDate(today);

        if(saved.isEmpty()) {
            // 오늘 데이터가 없으면 가장 최근 데이터 조회
            saved =  stockRepository.findToByOrderByDate();
            log.info("최근 데이터 전송");
        }

        if(!saved.isEmpty()) {
            List<ResponseOutputDTO> dtos = saved.stream().map(stock -> {
                ResponseOutputDTO dto = new ResponseOutputDTO();
                dto.setHtsKorIsnm(stock.getHtsKorIsnm());
                dto.setMkscShrnIscd(stock.getMkscShrnIscd());
                dto.setDataRank(stock.getDataRank());
                dto.setStckPrpr(stock.getStckPrpr());
                dto.setPrdyVrss(stock.getPrdyVrss());
                dto.setPrdyCtrt(stock.getPrdyCtrt());
                dto.setAcmlVol(stock.getAcmlVol());
                dto.setPrdyVol(stock.getPrdyVol());

                return dto;
            }).toList();

            cachedData.addAll(dtos);
        }
    }


    // 주식 주문
    public Mono<OrderResponse> submitOrder(Order order) {
        return webClient.post()
                .uri("/uapi/domestic-stock/v1/trading/order-cash")
                .headers(h -> {
                    h.setBearerAuth(token);
                    h.set("appkey", appkey);
                    h.set("appsecret", appsecret);
                    h.set("tr_id", order.getOrderType().equals("BUY")
                            ? "VTTC0802U"   // 모의투자 매수
                            : "VTTC0801U"); // 모의투자 매도
                    h.set("custtype", "P");
                })
                .bodyValue(Map.of(
                        "CANO",        "계좌번호 앞 8자리",
                        "ACNT_PRDT_CD","계좌번호 뒤 2자리",
                        "PDNO",        order.getStockCode(),   // 종목코드
                        "ORD_DVSN",    "00",                   // 지정가
                        "ORD_QTY",     String.valueOf(order.getQuantity()),  // 주문수량
                        "ORD_UNPR",    String.valueOf(order.getPrice()) // 주문단가
                ))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(this::parseOrderResult);
    }


    private Mono<OrderResponse> parseOrderResult(String response) {
        try{
            JsonNode node = objectMapper.readTree(response);
            OrderResponse orderResponse = new OrderResponse();

            orderResponse.setRtCd(node.get("rt_cd").asText());
            orderResponse.setMsg(node.get("msg1").asText());

            JsonNode output = node.get("output");
            if(output != null) {
                orderResponse.setOrdNo(output.get("KRX_FWDG_ORD_ORGNO").asText());
                orderResponse.setOrdTime(output.get("ORD_TMD").asText());
            }

            if(!"0".equals(orderResponse.getRtCd())) {
                return Mono.error(new RuntimeException(orderResponse.getMsg()));
            }

            return Mono.just(orderResponse);
        }catch (Exception e){
            return Mono.error(e);
        }
    }



}
