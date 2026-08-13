package stockOrder.stockTrade.matching;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;

/* 종목 하나에 대한 매수/매도 호가창(Order Book). 가격-시간 우선순위(price-time priority)를
   PriorityQueue로 유지해서, 매칭 시점마다 DB에 ORDER BY로 다시 정렬을 요청하는 대신
   O(log n)에 삽입하고 최우선 주문을 꺼낼 수 있게 한다.

   실제 Order 엔티티(가변, 별도 트랜잭션에서 상태가 바뀔 수 있음)를 그대로 큐에 들고 있으면
   취소/체결 이후에도 stale한 상태를 참조하게 될 위험이 있어서, 정렬에 필요한 값(가격/생성시각)만
   담은 불변 스냅샷(QueuedOrder)만 큐에 넣는다. 실제 체결 시도 시점에는 orderId로 DB에서 최신
   상태를 다시 조회해서 처리하고(MatchingService.drainAndExecute), 이미 취소/체결된 주문은 그
   시점에 걸러져 큐에서 자연히 제거된다(lazy deletion) - PriorityQueue는 임의 원소를 O(log n)에
   지울 수 없기 때문에 채택한 방식. */
public class OrderBook {

    record QueuedOrder(Long orderId, int price, LocalDateTime createdAt) {}

    // 매수: 지정가가 높을수록 우선, 같으면 먼저 낸 주문이 우선
    private static final Comparator<QueuedOrder> BUY_PRIORITY =
            Comparator.comparingInt(QueuedOrder::price).reversed()
                    .thenComparing(QueuedOrder::createdAt);

    // 매도: 지정가가 낮을수록 우선, 같으면 먼저 낸 주문이 우선
    private static final Comparator<QueuedOrder> SELL_PRIORITY =
            Comparator.comparingInt(QueuedOrder::price)
                    .thenComparing(QueuedOrder::createdAt);

    private final PriorityBlockingQueue<QueuedOrder> buyQueue = new PriorityBlockingQueue<>(11, BUY_PRIORITY);
    private final PriorityBlockingQueue<QueuedOrder> sellQueue = new PriorityBlockingQueue<>(11, SELL_PRIORITY);

    void offerBuy(Long orderId, int price, LocalDateTime createdAt) {
        buyQueue.offer(new QueuedOrder(orderId, price, createdAt));
    }

    void offerSell(Long orderId, int price, LocalDateTime createdAt) {
        sellQueue.offer(new QueuedOrder(orderId, price, createdAt));
    }

    void requeueBuy(QueuedOrder queuedOrder) {
        buyQueue.offer(queuedOrder);
    }

    void requeueSell(QueuedOrder queuedOrder) {
        sellQueue.offer(queuedOrder);
    }

    QueuedOrder pollBuy() {
        return buyQueue.poll();
    }

    QueuedOrder pollSell() {
        return sellQueue.poll();
    }
}
