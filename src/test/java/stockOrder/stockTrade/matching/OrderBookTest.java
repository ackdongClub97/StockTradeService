package stockOrder.stockTrade.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrderBookTest {

    @Test
    @DisplayName("멱등 등록 - 같은 주문이 여러 번 등록돼도(Outbox 중복 발행, 재시작 복구와 겹침) 큐에는 한 번만 들어간다")
    void 같은_주문을_여러_번_등록해도_한_번만_꺼내진다() {
        OrderBook book = new OrderBook();
        LocalDateTime now = LocalDateTime.now();

        book.offerBuy(1L, 50_000, now);
        book.offerBuy(1L, 50_000, now);
        book.offerBuy(1L, 50_000, now);

        assertEquals(1L, book.pollBuy().orderId());
        assertNull(book.pollBuy(), "중복 등록분이 남아 있으면 안 된다");
    }

    @Test
    @DisplayName("꺼낸 뒤에는 다시 등록·재등록할 수 있고, 재등록도 중복되지 않는다")
    void 꺼낸_주문은_다시_넣을_수_있다() {
        OrderBook book = new OrderBook();
        book.offerSell(7L, 50_000, LocalDateTime.now());

        OrderBook.QueuedOrder polled = book.pollSell();
        book.requeueSell(polled);
        book.offerSell(7L, 50_000, polled.createdAt()); // 처리 중에 같은 주문 메시지가 또 온 경우

        assertEquals(7L, book.pollSell().orderId());
        assertNull(book.pollSell());
    }
}
