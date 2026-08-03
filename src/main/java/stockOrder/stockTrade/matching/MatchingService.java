package stockOrder.stockTrade.matching;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import stockOrder.stockTrade.admin.AdminAlert;
import stockOrder.stockTrade.admin.AdminAlertService;
import stockOrder.stockTrade.fds.FraudDetectionService;
import stockOrder.stockTrade.kis.AskingPriceDTO;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.kis.KisWebSocketService;
import stockOrder.stockTrade.member.Member;
import stockOrder.stockTrade.member.MemberRepository;
import stockOrder.stockTrade.order.Order;
import stockOrder.stockTrade.order.OrderRepository;
import stockOrder.stockTrade.order.OrderStatus;
import stockOrder.stockTrade.order.OrderType;
import stockOrder.stockTrade.order.PriceMode;
import stockOrder.stockTrade.order.RealizedPnlService;
import stockOrder.stockTrade.order.Trade;
import stockOrder.stockTrade.order.TradeRepository;
import stockOrder.stockTrade.order.UnmatchReason;

import java.time.LocalDateTime;
import java.util.List;


@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingService {

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final MemberRepository memberRepository;
    private final KisService kisService;
    private final KisWebSocketService kisWebSocketService;
    private final RealizedPnlService realizedPnlService;
    private final AdminAlertService adminAlertService;
    private final FraudDetectionService fraudDetectionService;
    private final Sinks.Many<Order> orderResultSink = Sinks.many().multicast().onBackpressureBuffer();

    /* 장마감 대기주문 일괄취소를 하루에 한 번만 실행하기 위한 플래그 - KisService.saveToday와 동일한 패턴 */
    private boolean marketCloseCancelDone = false;

    @Scheduled(fixedRate = 30000, initialDelay = 5000)
    public void matching() {
        /* 장중 주문 시 30초 배치로 호가 기준 체결 시도 */
        if(!kisService.isMarketOpen()) {
            cancelPendingOrdersAtMarketClose();
            return;
        }
        marketCloseCancelDone = false;

        /* 대기 중인 종목 리스트 */
        List<String> pendingStockCodeBuyList = orderRepository.findPendingStockList(
                List.of(OrderStatus.PENDING, OrderStatus.PARTIAL)
        );

        if(pendingStockCodeBuyList.isEmpty()) return;

        log.info("[MatchingService] 대기 종목 {} 개 체결 시도",  pendingStockCodeBuyList.size());

        pendingStockCodeBuyList.forEach(pendingStockCode -> {
            try {
                kisWebSocketService.subscribe(pendingStockCode);

                AskingPriceDTO book = kisWebSocketService.getCachedAskingPrice(pendingStockCode);
                if (book == null) {
                    log.info("[MatchingService] {} 호가 데이터 아직 없음 - 다음 주기에 재시도", pendingStockCode);
                    markUnmatched(pendingStockCode, UnmatchReason.NO_ORDERBOOK_DATA);
                    return;
                }

                matchOrders(pendingStockCode, book);
            } catch (Exception e) {
                // 종목 하나 처리 중 예외(대부분 DB 커넥션 관련)가 나도 이번 배치의 나머지 종목은 계속 처리되게 격리
                log.error("[MatchingService] {} 처리 중 예외 발생: {}", pendingStockCode, e.getMessage(), e);
                markUnmatched(pendingStockCode, UnmatchReason.SYSTEM_ERROR);
                adminAlertService.publish(new AdminAlert(null, pendingStockCode,
                        "매칭 배치 중 예외: " + e.getMessage(), LocalDateTime.now()));
            }
        });
    }

    /* 장이 마감되면(isMarketOpen()==false) 그 시점까지도 대기 중(PENDING/PARTIAL)인 주문을 전부 취소 처리.
       matching()과 같은 30초 주기에서 호출되지만 marketCloseCancelDone 플래그로 하루 한 번만 실행됨 */
    private void cancelPendingOrdersAtMarketClose() {
        if (marketCloseCancelDone) return;

        List<Order> pendingOrders = orderRepository.findByOrderStatusIn(
                List.of(OrderStatus.PENDING, OrderStatus.PARTIAL)
        );

        if (!pendingOrders.isEmpty()) {
            log.info("[MatchingService] 장마감 - 대기 주문 {}건 취소 처리 시작", pendingOrders.size());
            pendingOrders.forEach(order -> {
                try {
                    cancelForMarketClose(order);
                } catch (Exception e) {
                    log.error("[MatchingService] 장마감 취소 처리 실패 orderId={} : {}", order.getOrderId(), e.getMessage(), e);
                }
            });
        }

        marketCloseCancelDone = true;
    }

    /* 매수 주문은 생성 시점에 전체 금액이 예약(차감)되어 있으므로, 미체결 잔량분만큼 환급 후 취소 처리 - OrderController.cancelOrder의 환급 로직과 동일 */
    private void cancelForMarketClose(Order order) {
        if (order.getOrderType() == OrderType.BUY) {
            int remainingQty = order.getQuantity() - order.getMatchedQuantity();
            int refund = order.getPrice() * remainingQty;

            if (refund > 0) {
                Member member = memberRepository.findByMemberId(order.getMemberId()).orElseThrow();
                member.setSeed(member.getSeed() + refund);
                memberRepository.save(member);
            }
        }

        order.setOrderStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        orderResultSink.tryEmitNext(order);
        log.info("[MatchingService] 장마감 주문취소 {} / {}", order.getMemberId(), order.getOrderId());
    }

    /* 특정 종목의 대기(PENDING/PARTIAL) 주문 전체에 이번 회차 미체결 사유를 기록 - 호가 데이터 자체가 없었거나 배치 중 예외가 난 경우 */
    private void markUnmatched(String stockCode, UnmatchReason reason) {
        try {
            List<OrderStatus> pendingStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIAL);
            List<Order> orders = orderRepository.findPendingBuyOrders(stockCode, pendingStatuses);
            List<Order> sellOrders = orderRepository.findPendingSellOrders(stockCode, pendingStatuses);

            LocalDateTime now = LocalDateTime.now();
            orders.forEach(o -> { o.setLastUnmatchedReason(reason); o.setLastAttemptAt(now); });
            sellOrders.forEach(o -> { o.setLastUnmatchedReason(reason); o.setLastAttemptAt(now); });
            orderRepository.saveAll(orders);
            orderRepository.saveAll(sellOrders);
        } catch (Exception e) {
            // 사유 기록 자체도 DB 문제로 실패할 수 있음(SYSTEM_ERROR의 근본원인이 DB인 경우) - 이 경우엔 로그(Loki)가 유일한 기록임
            log.warn("[MatchingService] {} 미체결 사유 기록 실패: {}", stockCode, e.getMessage());
        }
    }

    /* 매수/매도 각각 같은 호가 스냅샷을 나눠 쓰므로, 같은 회차에서 여러 주문이 같은 물량을 중복으로 먹지 않도록
       레벨별 잔량을 로컬에서 차감해가며(BookLevels) 가격 우선 → 시간 우선(price priority → time priority) 순서로 소진시킨다.
       (매수는 지정가가 높을수록, 매도는 지정가가 낮을수록 더 공격적인 주문이라 먼저 체결 기회를 준다 - findPendingBuyOrders/findPendingSellOrders 정렬 참고) */
    private void matchOrders(String pendingStockCode, AskingPriceDTO book) {
        BookLevels askLevels = new BookLevels(book.getAskPrices(), book.getAskVolumes());
        BookLevels bidLevels = new BookLevels(book.getBidPrices(), book.getBidVolumes());

        List<OrderStatus> pendingStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIAL);

        List<Order> buyOrders = orderRepository.findPendingBuyOrders(pendingStockCode, pendingStatuses);
        buyOrders.forEach(order -> execute(order, askLevels, true));

        List<Order> sellOrders = orderRepository.findPendingSellOrders(pendingStockCode, pendingStatuses);
        sellOrders.forEach(order -> execute(order, bidLevels, false));
    }

    /* 호가 10단계 스냅샷 - 매칭 도중 소진된 잔량을 로컬에서 차감해가며 추적 */
    private static class BookLevels {
        final int[] prices;
        final long[] volumes;

        BookLevels(String[] priceStrs, String[] volStrs) {
            prices = new int[priceStrs.length];
            volumes = new long[volStrs.length];
            for (int i = 0; i < priceStrs.length; i++) {
                prices[i] = parseIntSafe(priceStrs[i]);
                volumes[i] = parseIntSafe(volStrs[i]);
            }
        }
    }

    private record FillPlan(int quantity, int price) {}

    private static int parseIntSafe(String s) {
        try {
            return (s == null || s.isBlank()) ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /* 매수 자동 가격개선 하한 비율 - 지정가보다 5%를 초과해서 낮은 호가는 "너무 좋은 게 이상하다"고 보고 자동체결 대상에서 제외 */
    private static final double BUY_IMPROVEMENT_FLOOR_RATIO = 0.95;

    private boolean isEligible(int levelPrice, Order order, boolean isBuy) {
        if (isBuy) {
            // 5% 자동 가격개선 밴드는 지정가(LIMIT) 매수 전용 기능 - 현재가(MARKET) 매수는 밴드 없이 지정가 이하 최우선호가로만 체결
            if (order.getPriceMode() != PriceMode.LIMIT) {
                return levelPrice <= order.getPrice();
            }
            int minEligiblePrice = (int) Math.ceil(order.getPrice() * BUY_IMPROVEMENT_FLOOR_RATIO);
            return levelPrice <= order.getPrice() && levelPrice >= minEligiblePrice;
        }
        return levelPrice >= order.getPrice();
    }

    /* 지정가 조건에 맞는 호가 레벨들의 잔량 합만큼만 체결(부분체결 가능). 체결가는 그 중 가장 유리한(최우선) 호가.
       매수는 지정가 대비 5%를 초과해서 낮은 호가는 자동개선 대상에서 제외(그만큼은 미체결로 남김).
       실제로 체결시킨 만큼은 levels에서 차감해서 같은 회차의 다음 주문이 중복으로 못 먹게 한다. */
    private FillPlan computeFill(Order order, BookLevels levels, int remainQty, boolean isBuy) {
        Integer fillPrice = null;
        long fillableQty = 0;

        for (int i = 0; i < levels.prices.length; i++) {
            int levelPrice = levels.prices[i];
            if (levelPrice <= 0 || levels.volumes[i] <= 0) continue;
            if (!isEligible(levelPrice, order, isBuy)) continue;

            fillableQty += levels.volumes[i];
            if (fillPrice == null) fillPrice = levelPrice; // 배열은 1호가부터 순서대로 옴 - 첫 적중이 최우선호가
        }

        if (fillPrice == null || fillableQty <= 0) return null;

        int fillQty = (int) Math.min(remainQty, fillableQty);
        if (fillQty <= 0) return null;

        int remaining = fillQty;
        for (int i = 0; i < levels.prices.length && remaining > 0; i++) {
            int levelPrice = levels.prices[i];
            if (levelPrice <= 0 || levels.volumes[i] <= 0) continue;
            if (!isEligible(levelPrice, order, isBuy)) continue;

            int take = (int) Math.min(remaining, levels.volumes[i]);
            levels.volumes[i] -= take;
            remaining -= take;
        }

        return new FillPlan(fillQty, fillPrice);
    }

    /* 체결 - 호가 잔량만큼만 체결하고 Trade를 insert, Order는 누적 상태만 갱신(PARTIAL/MATCHED) */
    private void execute(Order order, BookLevels levels, boolean isBuy) {
        int remainQty = order.getQuantity() - order.getMatchedQuantity();
        if (remainQty <= 0) return;

        try {
            FillPlan fill = computeFill(order, levels, remainQty, isBuy);
            order.setLastAttemptAt(LocalDateTime.now());

            if (fill == null) {
                log.info("[pending] {} {} 체결 가능 호가 없음 (지정가 {})", order.getOrderId(), order.getStockName(), order.getPrice());
                order.setLastUnmatchedReason(UnmatchReason.NO_ELIGIBLE_PRICE);
                orderRepository.save(order);
                return;
            }

            // 매수는 주문 생성 시점에 order.price 기준으로 이미 전액 예약(차감)해뒀으므로
            // 여기서 잔고 부족으로 체결이 막힐 일은 없다 (updateSeed에서 실제체결가와의 차액만 정산)

            Trade trade = new Trade();
            trade.setOrderId(order.getOrderId());
            trade.setMemberId(order.getMemberId());
            trade.setStockCode(order.getStockCode());
            trade.setStockName(order.getStockName());
            trade.setOrderType(order.getOrderType());
            trade.setMatchedPrice(fill.price());
            trade.setMatchedQuantity(fill.quantity());
            trade.setMatchedAt(LocalDateTime.now());
            tradeRepository.save(trade);
            realizedPnlService.invalidate(order.getMemberId()); // 새 체결이 생겼으니 보유종목/매매차익 캐시 갱신 필요

            order.setMatchedQuantity(order.getMatchedQuantity() + fill.quantity());
            order.setMatchedPrice(fill.price());
            order.setUpdatedAt(LocalDateTime.now());
            order.setOrderStatus(order.getMatchedQuantity() >= order.getQuantity() ? OrderStatus.MATCHED : OrderStatus.PARTIAL);
            order.setLastUnmatchedReason(null); // 이번 회차에 진전이 있었으니 이전 미체결 사유는 더 이상 유효하지 않음

            orderRepository.save(order);
            updateSeed(order, fill.price(), fill.quantity());
            orderResultSink.tryEmitNext(order);
            fraudDetectionService.checkOnExecute(order);

            log.info("[체결] {} {} {} {}주중 누적 {}주(이번 회차 {}주) @ {}원 -> {}",
                    order.getOrderId(), order.getMemberId(), order.getStockCode(), order.getQuantity(),
                    order.getMatchedQuantity(), fill.quantity(), fill.price(), order.getOrderStatus());

            logPriceImprovement(order, isBuy, fill.price());
        } catch (Exception e) {
            log.error("[매칭 오류] 주문 {} 처리 중 예외 발생: {}", order.getOrderId(), e.getMessage(), e);
            adminAlertService.publish(new AdminAlert(order.getOrderId(), order.getStockCode(),
                    "체결 시도 중 예외: " + e.getMessage(), LocalDateTime.now()));
            try {
                order.setLastUnmatchedReason(UnmatchReason.SYSTEM_ERROR);
                order.setLastAttemptAt(LocalDateTime.now());
                orderRepository.save(order);
            } catch (Exception saveEx) {
                // 근본원인이 DB 문제(예: 커넥션 풀 고갈)라면 이 저장도 실패할 수 있음 - 그 경우 위 log.error가 Loki에 남는 유일한 기록이 됨
                log.warn("[매칭 오류] 주문 {} 사유 저장도 실패: {}", order.getOrderId(), saveEx.getMessage());
            }
        }
    }

    /* 지정가 매수 자동 가격개선(5% 밴드)이 실제로 지정가보다 얼마나 유리하게 체결되는지 통계 낼 수 있도록 체결 시점에 기록만 해둔다.
       별도 DB 컬럼/통계 시스템 없이, Order.price(지정가)와 Trade.matchedPrice(실제 체결가)가 이미 DB에 남아있으므로
       이 로그는 Loki/Grafana에서 실시간으로 훑어볼 수 있는 보조 채널이고, 정확한 집계는 두 테이블을 JOIN해서 언제든 다시 계산 가능하다. */
    private void logPriceImprovement(Order order, boolean isBuy, int fillPrice) {
        if (!isBuy || order.getPriceMode() != PriceMode.LIMIT || order.getPrice() <= 0) return;

        double improvementPercent = (order.getPrice() - fillPrice) * 100.0 / order.getPrice();
        log.info("[가격개선] {} {} 지정가 {}원 체결가 {}원 개선율 {}%",
                order.getOrderId(), order.getStockCode(), order.getPrice(), fillPrice,
                String.format("%.2f", improvementPercent));
    }

    private void updateSeed(Order order, int fillPrice, int fillQty) {
        Member member = memberRepository.findByMemberId(order.getMemberId()).orElseThrow();

        if(order.getOrderType() == OrderType.BUY) {
            // 주문 시점에 order.price 기준으로 이미 예약(차감)해뒀으니, 실제체결가가 더 쌌으면(호가가 지정가보다 유리) 차액만 환급
            int refund = (order.getPrice() - fillPrice) * fillQty;
            if (refund != 0) {
                member.setSeed(member.getSeed() + refund);
            }
        } else {
            int amount = fillPrice * fillQty;
            member.setSeed(amount + member.getSeed());
        }
        memberRepository.save(member);
    }

    public Flux<Order> getOrderResultStream(String memberId) {
        return orderResultSink.asFlux()
                .filter(order -> order.getMemberId().equals(memberId));
    }

    /* Kafka로 주문이 들어온 직후 즉시 한 번 체결을 시도(빠른 주문) */
    public void tryMatch(Order order) {
        Order orderData = orderRepository.findById(order.getOrderId()).orElse(null);

        if(orderData == null) {
            log.error("[match] 주문 없음: {}", order.getOrderId());
            return;
        }

        // 본장 전에는 구독만 걸어서 데이터가 미리 쌓이게 하고, 실제 체결 시도는 본장이 열려야 함
        kisWebSocketService.subscribe(orderData.getStockCode());

        if (!kisService.isMarketOpen()) {
            log.info("[tryMatch] 본장 아님 - 대기만 걸어둠: {}", orderData.getOrderId());
            return;
        }

        AskingPriceDTO book = kisWebSocketService.getCachedAskingPrice(orderData.getStockCode());
        if (book == null) {
            log.info("[tryMatch] {} {} 호가 데이터 아직 없음 - 다음 배치에서 재시도", orderData.getOrderId(), orderData.getStockCode());
            orderData.setLastUnmatchedReason(UnmatchReason.NO_ORDERBOOK_DATA);
            orderData.setLastAttemptAt(LocalDateTime.now());
            orderRepository.save(orderData);
            return;
        }

        boolean isBuy = orderData.getOrderType() == OrderType.BUY;
        BookLevels levels = isBuy
                ? new BookLevels(book.getAskPrices(), book.getAskVolumes())
                : new BookLevels(book.getBidPrices(), book.getBidVolumes());

        execute(orderData, levels, isBuy);
    }
}
