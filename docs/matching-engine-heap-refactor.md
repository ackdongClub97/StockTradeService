# 매칭 엔진 우선순위 결정: DB 정렬 → Heap(PriorityQueue) 전환

## 배경

기존 이력서에 "우선순위 큐(Heap) 기반으로 가격-시간 우선순위 매칭 엔진 설계, O(log n)에 최우선 호가 탐색"이라고
적혀 있었지만, 실제 구현은 매칭 시점마다 DB에 `ORDER BY`로 정렬을 맡기는 구조였다. 이력서 문구를 고치는 대신
실제로 그렇게 만들었다.

## Before — DB `ORDER BY` 재정렬

매칭 사이클(30초 배치)마다 해당 종목의 대기 주문 전체를 DB에서 다시 조회 + 정렬했다.

```java
// OrderRepository
@Query("SELECT o FROM Order o WHERE o.stockCode = :stockCode AND o.orderStatus IN :statuses "
     + "ORDER BY o.price DESC, o.createdAt ASC")
List<Order> findPendingBuyOrders(String stockCode, List<OrderStatus> statuses);

// MatchingService
private void matchOrders(String pendingStockCode, AskingPriceDTO book) {
    BookLevels askLevels = new BookLevels(book.getAskPrices(), book.getAskVolumes());
    BookLevels bidLevels = new BookLevels(book.getBidPrices(), book.getBidVolumes());

    List<OrderStatus> pendingStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIAL);

    List<Order> buyOrders = orderRepository.findPendingBuyOrders(pendingStockCode, pendingStatuses);
    buyOrders.forEach(order -> execute(order, askLevels, true));

    List<Order> sellOrders = orderRepository.findPendingSellOrders(pendingStockCode, pendingStatuses);
    sellOrders.forEach(order -> execute(order, bidLevels, false));
}
```

- **정렬 비용**: 대기 주문 N건 기준 매 사이클 O(N log N) — 이미 방금 전 사이클에서 정렬해둔 결과라도 매번 처음부터 다시 정렬.
- **정렬 주체**: 애플리케이션이 아니라 DB. "누가 우선순위가 높은가"를 판단하는 로직이 SQL의 `ORDER BY` 절에 위임되어 있어, 애플리케이션 코드만 보면 우선순위 결정 방식이 드러나지 않음.
- 대기 주문이 많아질수록(부하가 몰릴수록) 매 사이클 정렬 비용이 그만큼 커짐.

## After — 종목별 OrderBook(PriorityQueue)

종목코드별로 매수/매도 호가창을 메모리에 유지하고, 신규 주문은 삽입 시점에 큐에 반영한다. 매칭 시점엔 재정렬 없이 큐에서 최우선 주문을 꺼내기만 한다.

```java
// OrderBook.java (신규)
public class OrderBook {
    record QueuedOrder(Long orderId, int price, LocalDateTime createdAt) {}

    private static final Comparator<QueuedOrder> BUY_PRIORITY =
            Comparator.comparingInt(QueuedOrder::price).reversed()
                    .thenComparing(QueuedOrder::createdAt);
    private static final Comparator<QueuedOrder> SELL_PRIORITY =
            Comparator.comparingInt(QueuedOrder::price)
                    .thenComparing(QueuedOrder::createdAt);

    private final PriorityBlockingQueue<QueuedOrder> buyQueue = new PriorityBlockingQueue<>(11, BUY_PRIORITY);
    private final PriorityBlockingQueue<QueuedOrder> sellQueue = new PriorityBlockingQueue<>(11, SELL_PRIORITY);
    // offerBuy/offerSell(O(log n) 삽입), pollBuy/pollSell(O(log n) 추출), requeueBuy/requeueSell
}

// MatchingService
private final Map<String, OrderBook> orderBooks = new ConcurrentHashMap<>();

public void registerOrder(Order order) {           // 주문 생성 시 O(log n) 삽입
    OrderBook orderBook = orderBooks.computeIfAbsent(order.getStockCode(), k -> new OrderBook());
    if (order.getOrderType() == OrderType.BUY) {
        orderBook.offerBuy(order.getOrderId(), order.getPrice(), order.getCreatedAt());
    } else {
        orderBook.offerSell(order.getOrderId(), order.getPrice(), order.getCreatedAt());
    }
}

private void matchOrders(String pendingStockCode, AskingPriceDTO book) {
    BookLevels askLevels = new BookLevels(book.getAskPrices(), book.getAskVolumes());
    BookLevels bidLevels = new BookLevels(book.getBidPrices(), book.getBidVolumes());
    OrderBook orderBook = orderBooks.computeIfAbsent(pendingStockCode, k -> new OrderBook());

    drainAndExecute(orderBook, true, askLevels);    // O(log n) per poll
    drainAndExecute(orderBook, false, bidLevels);
}
```

- **정렬 비용**: 삽입 시 O(log n) 1회. 매칭 사이클은 재정렬 없이 O(log n) per poll로 최우선 주문을 꺼내기만 함.
- **정렬 주체**: 애플리케이션 코드(Heap 자료구조)가 직접 우선순위를 관리.

## 사용한 방법 (설계상 신경 쓴 부분)

| 문제 | 해결 방법 |
|---|---|
| `PriorityQueue`는 임의 원소를 O(log n)에 못 지움(취소된 주문 즉시 제거 불가) | 큐엔 `orderId`+정렬값만 담은 불변 스냅샷(`QueuedOrder`)만 넣고, 실제 처리 시점에 DB에서 최신 상태를 재조회 — 이미 취소/체결된 건 이 시점에 걸러져 큐에서 자연히 빠짐(lazy deletion) |
| 큐에 가변 `Order` 엔티티를 직접 넣으면 다른 트랜잭션의 상태 변경을 못 따라감(stale 참조) | 큐엔 불변 스냅샷만 저장, 실제 `Order`는 항상 `orderId`로 재조회 |
| 앱 재시작 시 메모리상 큐가 비어서 대기 주문을 영영 못 찾음 | `@PostConstruct`에서 DB의 PENDING/PARTIAL 주문 전체로 큐를 재구성 — OrderBook은 DB 위에 얹은 성능 최적화 계층일 뿐, 진실의 원천은 항상 DB |
| 스케줄러 스레드와 주문 생성 요청 스레드가 큐에 동시 접근 | `PriorityBlockingQueue`(스레드 세이프)로 별도 락 없이 안전하게 처리 |

## 검증

- 새 유닛 테스트: 지정가 50,000원(먼저 생성)과 51,000원(나중에 생성) 매수 주문을 등록하고 매도호가 물량을 5주로 제한(둘 다 10주 요청, 경쟁 상황) → **더 늦게 큐에 들어간 주문이라도 지정가가 높으면 먼저 물량을 가져가는 것**을 확인.
- 재시작 복구, 실제 주문 생성→체결 대기, 장마감 자동취소까지 실제 앱 기동 상태로 라이브 검증.
- 기존 4개 테스트(부분체결/가격개선/시장가 시나리오)도 전부 그대로 통과 — 영향받는 경로가 아님.

## 부수 효과

매칭 사이클마다 DB `ORDER BY` 쿼리를 안 날리게 되어, 대기 주문이 많을수록(부하가 몰릴수록) DB 부하가 줄어드는 방향의 개선이기도 하다. 다만 이 효과 자체를 수치로 별도 측정하지는 않았다.
