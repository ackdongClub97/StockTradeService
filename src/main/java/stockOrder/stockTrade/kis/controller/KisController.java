package stockOrder.stockTrade.kis.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import stockOrder.stockTrade.kis.service.KisService;
import stockOrder.stockTrade.kis.dto.ResponseOutputDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class KisController {

    private KisService kisService;

    @Autowired
    public KisController(KisService kisService) {
        this.kisService = kisService;
    }

    @GetMapping("/volume-rank")
    public Mono<List<ResponseOutputDTO>> getVolumeRank() {
        return kisService.getVolumeRank();
    }

    @GetMapping("/volume")
    public Mono<Map<String, Object>> getVolumeRanking() {
        return kisService.getVolumeRank().map(list -> Map.of(
                "updatedTime", LocalDateTime.now().toString(),
                "count", list.size(),
                "data", list)
        );
    }

    /* 30초 갱신 캐시 데이터 즉시 반환 */
    @GetMapping("/volume/cached")
    public Map<String, Object> getCachedRank() {
        List<ResponseOutputDTO> list = kisService.getCachedRanking();
        return Map.of(
                "updatedTime", LocalDateTime.now().toString(),
                "count", list.size(),
                "data", list
        );
    }

    /* SSE 실시간 스트림 */
    @GetMapping(value="/volume/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> getVolumeStream() {
        return kisService.getRankingStream()
                .map(list -> ServerSentEvent.builder()
                        .event("volume-rank")
                        .data((Object) Map.of(
                                "updateTime", LocalDateTime.now().toString(),
                                "count", list.size(),
                                "data", list
                        )).build()
        ).doOnSubscribe(s -> log.info("SSE 클라이언트 연결"))
                .doOnCancel(() -> log.info("SSE 클라이언트 연결 종료"));
    }

    @GetMapping("/stock-detail")
    public Mono<ResponseOutputDTO> getStockDetail(@PathVariable String code) {
        return kisService.getStockDetail(code);
    }

    @GetMapping("/stock/{code}/stream")
    public Flux<ServerSentEvent<Object>> getStockStream(@PathVariable String code) {
        return kisService.getStockDetailStream(code)
                .map(data -> ServerSentEvent.builder()
                        .event("stock-detail").data((Object) data).build());
    }
}