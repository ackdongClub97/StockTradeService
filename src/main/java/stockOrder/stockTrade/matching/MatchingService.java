package stockOrder.stockTrade.matching;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import stockOrder.stockTrade.kis.AskingPriceDTO;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.kis.KisWebSocketService;
import stockOrder.stockTrade.member.Member;
import stockOrder.stockTrade.member.MemberRepository;
import stockOrder.stockTrade.order.Order;
import stockOrder.stockTrade.order.OrderRepository;
import stockOrder.stockTrade.order.OrderStatus;
import stockOrder.stockTrade.order.OrderType;
import stockOrder.stockTrade.order.RealizedPnlService;
import stockOrder.stockTrade.order.Trade;
import stockOrder.stockTrade.order.TradeRepository;

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
    private final Sinks.Many<Order> orderResultSink = Sinks.many().multicast().onBackpressureBuffer();

    @Scheduled(fixedRate = 30000, initialDelay = 5000)
    public void matching() {
        /* 장중 주문 시 30초 배치로 호가 기준 체결 시도 */
        if(!kisService.isMarketOpen()) return;

        /* 대기 중인 종목 리스트 */
        List<String> pendingStockCodeBuyList = orderRepository.findPendingStockList(
                List.of(OrderStatus.PENDING, OrderStatus.PARTIAL)
        );

        if(pendingStockCodeBuyList.isEmpty()) return;

        log.info("[MatchingService] 대기 종목 {} 개 체결 시도",  pendingStockCodeBuyList.size());

        pendingStockCodeBuyList.forEach(pendingStockCode -> {
            kisWebSocketService.subscribe(pendingStockCode);

            AskingPriceDTO book = kisWebSocketService.getCachedAskingPrice(pendingStockCode);
            if (book == null) {
                log.info("[MatchingService] {} 호가 데이터 아직 없음 - 다음 주기에 재시도", pendingStockCode);
                return;
            }

            matchOrders(pendingStockCode, book);
        });
    }

    /* 매수/매도 각각 같은 호가 스냅샷을 나눠 쓰므로, 같은 회차에서 여러 주문이 같은 물량을 중복으로 먹지 않도록
       레벨별 잔량을 로컬에서 차감해가며(BookLevels) 시간 우선(createdAt ASC) 순서로 소진시킨다. */
    private void matchOrders(String pendingStockCode, AskingPriceDTO book) {
        BookLevels askLevels = new BookLevels(book.getAskPrices(), book.getAskVolumes());
        BookLevels bidLevels = new BookLevels(book.getBidPrices(), book.getBidVolumes());

        List<Order> buyOrders = orderRepository.findPendingByStockCodeAndType(pendingStockCode, OrderType.BUY, List.of(OrderStatus.PENDING, OrderStatus.PARTIAL));
        buyOrders.forEach(order -> execute(order, askLevels, true));

        List<Order> sellOrders = orderRepository.findPendingByStockCodeAndType(pendingStockCode, OrderType.SELL, List.of(OrderStatus.PENDING, OrderStatus.PARTIAL));
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

    /* 지정가 조건에 맞는 호가 레벨들의 잔량 합만큼만 체결(부분체결 가능). 체결가는 그 중 가장 유리한(최우선) 호가.
       실제로 체결시킨 만큼은 levels에서 차감해서 같은 회차의 다음 주문이 중복으로 못 먹게 한다. */
    private FillPlan computeFill(Order order, BookLevels levels, int remainQty, boolean isBuy) {
        Integer fillPrice = null;
        long fillableQty = 0;

        for (int i = 0; i < levels.prices.length; i++) {
            int levelPrice = levels.prices[i];
            if (levelPrice <= 0 || levels.volumes[i] <= 0) continue;

            boolean eligible = isBuy ? levelPrice <= order.getPrice() : levelPrice >= order.getPrice();
            if (!eligible) continue;

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

            boolean eligible = isBuy ? levelPrice <= order.getPrice() : levelPrice >= order.getPrice();
            if (!eligible) continue;

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

        FillPlan fill = computeFill(order, levels, remainQty, isBuy);
        if (fill == null) {
            log.info("[pending] {} 체결 가능 호가 없음 (지정가 {})", order.getStockName(), order.getPrice());
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

        orderRepository.save(order);
        updateSeed(order, fill.price(), fill.quantity());
        orderResultSink.tryEmitNext(order);

        log.info("[체결] {} {} {}주중 누적 {}주(이번 회차 {}주) @ {}원 -> {}",
                order.getMemberId(), order.getStockCode(), order.getQuantity(),
                order.getMatchedQuantity(), fill.quantity(), fill.price(), order.getOrderStatus());
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
            log.info("[tryMatch] {} 호가 데이터 아직 없음 - 다음 배치에서 재시도", orderData.getStockCode());
            return;
        }

        boolean isBuy = orderData.getOrderType() == OrderType.BUY;
        BookLevels levels = isBuy
                ? new BookLevels(book.getAskPrices(), book.getAskVolumes())
                : new BookLevels(book.getBidPrices(), book.getBidVolumes());

        execute(orderData, levels, isBuy);
    }
}
