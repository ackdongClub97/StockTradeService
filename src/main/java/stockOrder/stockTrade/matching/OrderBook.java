package stockOrder.stockTrade.matching;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /* 지금 큐에 들어 있는 주문ID - 같은 주문이 두 번 등록되지 않게 한다(멱등 등록).
       Outbox 릴레이는 "최소 한 번" 발행이라 같은 주문 메시지가 중복으로 올 수 있고, 재시작 복구(DB 재구성)와
       신규 등록이 겹칠 수도 있음. 같은 주문이 큐에 두 개 있으면 한 배치 회차에 같은 주문을 두 번 체결 시도하게 된다. */
    private final Set<Long> queuedIds = ConcurrentHashMap.newKeySet();

    void offerBuy(Long orderId, int price, LocalDateTime createdAt) {
        if (queuedIds.add(orderId)) buyQueue.offer(new QueuedOrder(orderId, price, createdAt));
    }

    void offerSell(Long orderId, int price, LocalDateTime createdAt) {
        if (queuedIds.add(orderId)) sellQueue.offer(new QueuedOrder(orderId, price, createdAt));
    }

    void requeueBuy(QueuedOrder queuedOrder) {
        if (queuedIds.add(queuedOrder.orderId())) buyQueue.offer(queuedOrder);
    }

    void requeueSell(QueuedOrder queuedOrder) {
        if (queuedIds.add(queuedOrder.orderId())) sellQueue.offer(queuedOrder);
    }

    QueuedOrder pollBuy() {
        return removeId(buyQueue.poll());
    }

    QueuedOrder pollSell() {
        return removeId(sellQueue.poll());
    }

    private QueuedOrder removeId(QueuedOrder polled) {
        if (polled != null) queuedIds.remove(polled.orderId());
        return polled;
    }
}
