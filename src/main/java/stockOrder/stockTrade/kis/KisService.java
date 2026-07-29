package stockOrder.stockTrade.kis;

import io.netty.handler.codec.PrematureChannelClosureException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;

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
import stockOrder.stockTrade.stock.Stock;
import stockOrder.stockTrade.stock.StockRepository;
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

    /* KisWebSocketService도 KisService(pushStockDetail)를 참조하므로 순환참조 방지를 위해 지연 주입 */
    @Autowired
    @Lazy
    private KisWebSocketService kisWebSocketService;


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
    // 종목별 상세 sink가 없어도(=해당 종목 상세페이지를 아무도 안 보고 있어도) 가격 갱신을 알 수 있도록 하는 전역 sink - 보유주식 실시간 갱신 등에 사용
    private final Sinks.Many<String> priceUpdateSink = Sinks.many().multicast().onBackpressureBuffer();

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

    public Mono<List<ResponseOutputDTO>> getVolumeRank(String timeChk) {
        HttpHeaders headers = createVolumeRankHttpHeaders();
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/uapi/domestic-stock/v1/quotations/volume-rank")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_COND_SCR_DIV_CODE", "20171")
                        .queryParam("FID_INPUT_ISCD", "0002")
                        .queryParam("FID_DIV_CLS_CODE", "0")
                        .queryParam("FID_BLNG_CLS_CODE", timeChk)
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
                                .filter(this::isRetryable)
                                .doBeforeRetry(s -> log.debug("랭킹 연결 재시도 중...."))
                );

    }

    /* 연결이 조기 종료됐거나(PrematureChannelClosureException), KIS 초당 호출 제한(EGW00201)에 걸린 경우엔
       잠깐 기다렸다 다시 시도하면 대부분 해결되는 일시적 오류라서 재시도 대상으로 취급한다 */
    private boolean isRetryable(Throwable e) {
        return e instanceof PrematureChannelClosureException
                || (e.getMessage() != null && e.getMessage().contains("EGW00201"));
    }

    @Scheduled(fixedDelayString = "${kis.rank.refresh-interval-ms:30000}", initialDelay = 0)
    public void refreshRank() {
        // 실제로 확인해보니 본장(정규장) 외 시간엔 KIS가 살아있는 랭킹 데이터를 안 줌 - 본장에만 실시간 호출하고
        // 나머지 시간엔 저장해둔 마지막(종가) 데이터를 그대로 재전송한다.
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

        saveToday = false;

        getVolumeRank("0").subscribe(
                list -> {
                    cachedData.clear();
                    cachedData.addAll(list);
                    rankSink.tryEmitNext(List.copyOf(cachedData));
                }, err -> log.error("[refreshRank] 조회 실패: {}", err.getMessage())
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
                    if(isMarketOpen()) {
                        getVolumeRank("0").subscribe(
                                data -> rankSink.tryEmitNext(data),
                                err -> log.error("랭킹 조회 실패: {}", err.getMessage())
                        );
                    } else if(!cachedData.isEmpty()) {
                        rankSink.tryEmitNext(List.copyOf(cachedData));
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
                                .filter(this::isRetryable)
                                .doBeforeRetry(s -> log.debug("detail 연결 재시도 중...."))
                );
    }

    public Flux<ResponseOutputDTO> getStockDetailStream(String code) {
        Sinks.Many<ResponseOutputDTO> sink =  detailSink.computeIfAbsent(code, k ->
                Sinks.many().replay().latest());

        return sink.asFlux()
                .doOnSubscribe(s -> {
                    log.info("{} 종목 실시간 구독", code);
                    kisWebSocketService.subscribe(code);

                    // subscribe()는 종목이 이미 구독중이면(=오늘 한 번이라도 봤으면) 웜업 조회를 다시 안 하므로,
                    // 새로고침 등으로 새로 접속한 시점에 캐시된 마지막 값이 있으면 바로 하나 밀어준다.
                    // (장마감 후엔 새 틱이 안 와서, 이게 없으면 재접속 시 영원히 빈 화면으로 남는다)
                    ResponseOutputDTO cached = cachedStockData.get(code);
                    if (cached != null) {
                        sink.tryEmitNext(cached);
                    }
                })
                .doOnCancel(() -> {
                    log.info("{} 종목 조회 종료", code);
                })
                // sink를 map에서 지우지 않는다 - replay().latest()가 마지막 값을 들고 있어야
                // 재접속(새로고침) 시 새 틱 없이도 마지막 값을 바로 다시 보여줄 수 있다.
                .onErrorResume(e -> {           // ← 에러 시에만 새 Sink로 교체
                    detailSink.remove(code);
                    return getStockDetailStream(code);
                });
    }

    /* 웹소켓(KisWebSocketService)이 실시간 체결가를 받을 때마다 호출 - 캐시 갱신 + SSE sink emit */
    public void pushStockDetail(String code, ResponseOutputDTO dto) {
        cachedStockData.put(code, dto);
        Sinks.Many<ResponseOutputDTO> sink = detailSink.get(code);
        if(sink != null) sink.tryEmitNext(dto);
        priceUpdateSink.tryEmitNext(code);
    }

    /* 특정 종목 상세페이지를 아무도 안 보고 있어도 가격 갱신 여부를 알 수 있는 전역 스트림 (예: 보유주식 실시간 갱신용) */
    public Flux<String> getPriceUpdateStream() {
        return priceUpdateSink.asFlux();
    }

    public boolean isMarketOpen() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Seoul"));
        LocalTime open = LocalTime.of(9, 0);
        LocalTime close = LocalTime.of(15, 30);

        DayOfWeek day = LocalDate.now(ZoneId.of("Asia/Seoul")).getDayOfWeek();
        boolean isWeekday  = day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;

        return isWeekday && now.isAfter(open) && now.isBefore(close);
    }

    public boolean isAfterMarket() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Seoul"));
        LocalTime open = LocalTime.of(15, 30);
        LocalTime close = LocalTime.of(18, 0);

        DayOfWeek day = LocalDate.now(ZoneId.of("Asia/Seoul")).getDayOfWeek();
        boolean isWeekday  = day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;

        return isWeekday && now.isAfter(open) && now.isBefore(close);
    }

    /* 주문 접수 시작 시각(오전 8시) 이전인지 - 이 시간 전에는 주문 자체를 받지 않음 */
    public boolean isBeforeOrderWindow() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Seoul"));
        return now.isBefore(LocalTime.of(8, 0));
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

    public ResponseOutputDTO getCachedStockData(String stockCode) {
        return cachedStockData.get(stockCode);
    }

    public void putCachedStockData(String stockCode, ResponseOutputDTO dto) {
        cachedStockData.put(stockCode, dto);
    }

}
