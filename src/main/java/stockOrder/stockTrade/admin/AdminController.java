package stockOrder.stockTrade.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;
import stockOrder.stockTrade.member.CustomerDetails;
import stockOrder.stockTrade.order.Order;
import stockOrder.stockTrade.order.OrderRepository;
import stockOrder.stockTrade.order.OrderStatus;
import stockOrder.stockTrade.outbox.DeadLetterService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/* 미체결(PENDING/PARTIAL) 주문이 왜 아직 안 풀렸는지(호가 조건 미충족/데이터 없음/시스템 오류) 보여주는 관리자 화면.
   member 테이블에 별도 권한 체계가 없는 개인 프로젝트라, application.yaml의 admin.member-id 하나로만 접근을 제한한다. */
@Controller
@RequiredArgsConstructor
public class AdminController {

    private final OrderRepository orderRepository;
    private final LokiClient lokiClient;
    private final AdminAlertService adminAlertService;
    private final JdbcTemplate jdbcTemplate;
    private final DeadLetterService deadLetterService;

    @Value("${admin.member-id:}")
    private String adminMemberId;

    private boolean isAdmin(CustomerDetails customerDetails) {
        if (customerDetails == null || adminMemberId == null || adminMemberId.isBlank()) return false;
        return adminMemberId.equals(customerDetails.getMember().getMemberId());
    }

    /* 현재 만료되지 않은 로그인 세션 총 개수 - 한 회원이 탭/기기를 여러 개 열어두면 그만큼 중복 집계됨.
       세션이 서버 메모리가 아니라 MySQL(Spring Session JDBC)에 있으므로 서버 몇 대든 전체 기준으로 센다 */
    private int countActiveSessions() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME IS NOT NULL AND EXPIRY_TIME > ?",
                Integer.class, System.currentTimeMillis());
        return count == null ? 0 : count;
    }

    @GetMapping("/admin/orders")
    public String orders(@AuthenticationPrincipal CustomerDetails customerDetails, Model model) {
        if (!isAdmin(customerDetails)) {
            return "redirect:/stockHome";
        }

        List<Order> orders = orderRepository.findByOrderStatusIn(List.of(OrderStatus.PENDING, OrderStatus.PARTIAL));
        model.addAttribute("orders", orders);
        model.addAttribute("sessionCount", countActiveSessions());
        return "adminOrders";
    }

    /* 어드민 화면에서 주기적으로 폴링해서 실시간처럼 갱신하는 세션 수 */
    @GetMapping("/api/admin/session-count")
    @ResponseBody
    public ResponseEntity<?> sessionCount(@AuthenticationPrincipal CustomerDetails customerDetails) {
        if (!isAdmin(customerDetails)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(Map.of("count", countActiveSessions()));
    }

    /* 재시도 끝에 처리 못 한 Kafka 메시지(DLQ) 목록 - 아직 재발행 안 한 것만 */
    @GetMapping("/api/admin/dead-letters")
    @ResponseBody
    public ResponseEntity<?> deadLetters(@AuthenticationPrincipal CustomerDetails customerDetails) {
        if (!isAdmin(customerDetails)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(deadLetterService.findPending());
    }

    /* 원인을 해결한 뒤 DLQ 메시지를 원래 토픽으로 재발행 */
    @PostMapping("/api/admin/dead-letters/{id}/replay")
    @ResponseBody
    public ResponseEntity<?> replayDeadLetter(@AuthenticationPrincipal CustomerDetails customerDetails,
                                              @PathVariable Long id) throws Exception {
        if (!isAdmin(customerDetails)) {
            return ResponseEntity.status(403).build();
        }
        return deadLetterService.replay(id)
                ? ResponseEntity.ok(Map.of("message", "SUCCESS"))
                : ResponseEntity.status(404).body(Map.of("message", "없거나 이미 재발행한 메시지입니다."));
    }

    /* 특정 주문 근처 시각의 로그를 Loki에서 직접 조회(Grafana 없이 이 화면에서 바로 확인) */
    @GetMapping("/api/admin/logs")
    @ResponseBody
    public ResponseEntity<?> logs(@AuthenticationPrincipal CustomerDetails customerDetails,
                                   @RequestParam String query,
                                   @RequestParam(required = false) Long fromEpochSec,
                                   @RequestParam(required = false) Long toEpochSec) {
        if (!isAdmin(customerDetails)) {
            return ResponseEntity.status(403).build();
        }

        Instant end = toEpochSec != null ? Instant.ofEpochSecond(toEpochSec) : Instant.now();
        Instant start = fromEpochSec != null ? Instant.ofEpochSecond(fromEpochSec) : end.minusSeconds(600);

        List<LokiClient.LogLine> lines = lokiClient.queryRange(query, start, end, 1000);
        return ResponseEntity.ok(lines);
    }

    /* SYSTEM_ERROR(DB 커넥션 등)가 발생하는 순간 관리자 화면이 열려있으면 바로 알려주는 스트림 */
    @GetMapping(value = "/api/admin/alerts/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public Flux<ServerSentEvent<Object>> alertStream(@AuthenticationPrincipal CustomerDetails customerDetails) {
        if (!isAdmin(customerDetails)) {
            return Flux.empty();
        }

        return adminAlertService.stream()
                .map(alert -> ServerSentEvent.builder()
                        .event("admin-alert")
                        .data((Object) Map.of(
                                "orderId", alert.orderId() == null ? "" : alert.orderId(),
                                "stockCode", alert.stockCode(),
                                "message", alert.message(),
                                "occurredAt", alert.occurredAt().toString()
                        ))
                        .build());
    }
}
