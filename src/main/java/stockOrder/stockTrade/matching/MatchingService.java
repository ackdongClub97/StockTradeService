package stockOrder.stockTrade.matching;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import stockOrder.stockTrade.kis.KisService;
import stockOrder.stockTrade.member.Member;
import stockOrder.stockTrade.member.MemberRepository;
import stockOrder.stockTrade.order.Order;
import stockOrder.stockTrade.order.OrderRepository;
import stockOrder.stockTrade.order.OrderStatus;
import stockOrder.stockTrade.order.OrderType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;


@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingService {

    private final OrderRepository orderRepository;
    private final MemberRepository memberRepository;
    private final KisService kisService;
    private final Sinks.Many<Order> orderResultSink = Sinks.many().multicast().onBackpressureBuffer();

    @Scheduled(fixedRate = 30000, initialDelay = 5000)
    public void matching() {
        /* 장중 주문 시 30초 배치로 주문 금액 매칭 후 체결 */
        if(!kisService.isMarketOpen()) return;

        /* 대기 중인 종목 리스트 */
        List<String> pendingStockCodeBuyList = orderRepository.findPendingStockList(
                List.of(OrderStatus.PENDING, OrderStatus.PARTIAL)
        );

        if(pendingStockCodeBuyList.isEmpty()) return;

        log.info("[MatchingService] 대기 종목 {} 개 체결 시도",  pendingStockCodeBuyList.size());

        pendingStockCodeBuyList.forEach(pendingStockCode ->
           kisService.getStockDetail(pendingStockCode)
                   .delaySubscription(Duration.ofSeconds(1))
                   .subscribe(
                           stock -> matchOrders(pendingStockCode, Integer.parseInt(stock.getStckPrpr()))
                           , err -> log.error("[MatchingService] 체결 실패: {} / {}", pendingStockCode, err.getMessage())
                   )
        );

    }

    /* 현재가 주문(빠른 주문) */
    private void matchOrders(String pendingStockCode, int currentPrice) {
        /* 매수 */
        List<Order> buyOrders = orderRepository.findPendingStockCodeBuyOrderStatus(pendingStockCode, OrderType.BUY, List.of(OrderStatus.PENDING, OrderStatus.PARTIAL), currentPrice);
        buyOrders.forEach(order -> execute(order, currentPrice));

        /* 매도  */
        List<Order> sellOrders = orderRepository.findPendingStockCodeSellOrderStatus(pendingStockCode, OrderType.SELL, List.of(OrderStatus.PENDING, OrderStatus.PARTIAL), currentPrice);
        sellOrders.forEach(order -> execute(order,currentPrice));
    }

    /* 체결 */
    private void execute(Order order, int currentPrice) {
        int remainQty = order.getQuantity() - order.getMatchedQuantity();

        // 잔고(매수)
        if(order.getOrderType() == OrderType.BUY) {
            Member member = memberRepository.findByMemberId(order.getMemberId()).orElseThrow();
            int totalCost = currentPrice * remainQty;

            if(member.getSeed() < totalCost) {
                log.warn("[MatchingService] 잔고 부족 - 주문 실패 : {} / {}", order.getMemberId(), order.getOrderId());
                order.setOrderStatus(OrderStatus.FAILED);
                orderRepository.save(order);
                orderResultSink.tryEmitNext(order);
                return;
            }
        }

        // 잔량 체결
        order.setMatchedQuantity(order.getQuantity());
        order.setOrderStatus(OrderStatus.MATCHED);
        order.setMatchedPrice(currentPrice);

        // 잔고 업데이트
        updateSeed(order, currentPrice);

        orderRepository.save(order);
        orderResultSink.tryEmitNext(order);

        log.info("[체결] {} {} {}주중 {}주 @ {}원 -> {}",
                order.getMemberId(), order.getStockCode(), order.getQuantity(),
                order.getMatchedQuantity(), currentPrice, order.getOrderStatus());
    }


    private void updateSeed(Order order, int currentPrice) {
        Member member = memberRepository.findByMemberId(order.getMemberId()).orElseThrow();
        int current = member.getSeed();
        int amount = currentPrice * order.getMatchedQuantity();

        if(order.getOrderType() == OrderType.BUY) {
            member.setSeed(current - amount);
        } else {
            member.setSeed(amount + current);
        }
        memberRepository.save(member);
    }

    public Flux<Order> getOrderResultStream(String memberId) {
        return orderResultSink.asFlux()
                .filter(order -> order.getMemberId().equals(memberId));
    }


    public void tryMatch(Order order) {
        Order orderData = orderRepository.findById(order.getOrderId()).orElse(null);

        if(orderData == null) {
            log.error("[match] 주문 없음: {}", order.getOrderId());
            return;
        }

        kisService.getStockDetail(order.getStockCode())
                .subscribe(stock -> {
                    int currentPrice = Integer.parseInt(stock.getStckPrpr());
                    boolean matched = false;

                    if(order.getOrderType() == OrderType.BUY) {
                        matched = order.getPrice() >= currentPrice;
                    } else {
                        matched = order.getPrice() <= currentPrice;
                    }

                    if(matched) {
                        execute(order, currentPrice);
                        log.info("[matched] {} {}주 @ {}원 체결 되었습니다.", order.getStockName(), order.getQuantity(), currentPrice);
                    } else {
                        log.info("[pending] {} 지정가: {} / 현재가: {}", order.getStockName(), order.getPrice(), currentPrice);
                    }
                }, err -> log.error("[matching error] 현재가 조회 실패: {}", err.getMessage())
            );
    }
}








