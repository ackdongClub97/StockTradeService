package stockOrder.stockTrade.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import stockOrder.stockTrade.member.Member;
import stockOrder.stockTrade.member.MemberRepository;
import stockOrder.stockTrade.outbox.OutboxEvent;
import stockOrder.stockTrade.outbox.OutboxEventRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

/* 주문 접수/취소의 DB 쓰기(잔고 예약·환급 + 주문 상태 + 발행할 메시지)를 한 트랜잭션으로 처리한다.
   예전엔 OrderController 메서드에 @Transactional이 붙어 있어서, 트랜잭션 안에서 Kafka 발행까지 하는 바람에
   커밋 전에 컨슈머가 메시지를 먼저 읽을 수 있었고, 발행이 실패하면 로그만 남고 메시지가 사라졌음. */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCommandService {

    private final OrderRepository orderRepository;
    private final MemberRepository memberRepository;
    private final RealizedPnlService realizedPnlService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public enum SubmitResult { OK, INSUFFICIENT_SEED, INSUFFICIENT_QUANTITY }

    public enum CancelResult { OK, NOT_FOUND, NOT_CANCELLABLE, CONFLICT }

    @Transactional
    public SubmitResult submit(Order order, String memberId) {
        // 회원 행 락 - 같은 회원의 주문 접수는 여기서 한 건씩 줄을 선다(다른 회원끼리는 서로 안 막힘)
        Member member = memberRepository.findByMemberIdForUpdate(memberId).orElseThrow();

        if (order.getOrderType() == OrderType.BUY) {
            long totalCost = (long) order.getPrice() * order.getQuantity();

            if (member.getSeed() < totalCost) {
                return SubmitResult.INSUFFICIENT_SEED;
            }

            // 대기 중인 주문 금액만큼 즉시 예약(차감) - 안 그러면 같은 잔고로 여러 건 주문이 가능해짐.
            // 실제 체결가가 지정가보다 유리했으면 그 차액만 체결 시점에 환급되고(MatchingService.updateSeed),
            // 취소하면 미체결분 예약이 그대로 환급된다(cancel).
            member.setSeed((int) (member.getSeed() - totalCost));
        } else {
            // 회원 행 락을 잡은 상태라, 같은 회원의 다른 매도 주문이 커밋되기 전엔 여기까지 못 들어온다
            // -> sumReservedSellQuantity가 직전 주문까지 반영된 값을 본다
            long remaining = realizedPnlService.getRemainingQuantity(memberId, order.getStockCode());
            long reserved = orderRepository.sumReservedSellQuantity(memberId, order.getStockCode());

            if (order.getQuantity() > remaining - reserved) {
                return SubmitResult.INSUFFICIENT_QUANTITY;
            }
        }

        // 요청 바디로 들어온 값은 신뢰하지 않음 - orderId가 채워져 오면 save가 기존 주문을 덮어쓰는(merge) 경로를 탐
        order.setOrderId(null);
        order.setVersion(null);
        order.setMatchedQuantity(0);
        order.setMemberId(memberId);
        order.setOrderStatus(OrderStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now());
        orderRepository.save(order);

        // Kafka로 바로 보내지 않고 같은 트랜잭션에서 Outbox에 기록 - 주문 저장과 "보낼 메시지"가 같이 커밋되거나 같이 롤백된다.
        // 실제 발행은 커밋 이후 OutboxRelay가 한다(즉시 발행 + 실패 시 주기 재발행).
        // 메시지 키 = 종목코드: 같은 종목 주문은 같은 파티션 -> 그 파티션을 맡은 서버 한 곳에서 순서대로 처리(단일 writer)
        outboxEventRepository.save(OutboxEvent.of(OrderKafkaConfig.ORDER_TOPIC, order.getStockCode(),
                objectMapper.writeValueAsString(order), order.getOrderId()));

        return SubmitResult.OK;
    }

    /* 대기 중(PENDING/PARTIAL)인 주문만 취소 가능. 이미 체결된 수량은 그대로 Trade에 남고, 남은 미체결분만 취소됨 */
    @Transactional
    public CancelResult cancel(String memberId, Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);

        if (order == null || !order.getMemberId().equals(memberId)) {
            return CancelResult.NOT_FOUND;
        }

        if (order.getOrderStatus() != OrderStatus.PENDING && order.getOrderStatus() != OrderStatus.PARTIAL) {
            return CancelResult.NOT_CANCELLABLE;
        }

        order.setOrderStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(LocalDateTime.now());

        try {
            // 여기서 바로 flush해서 버전 충돌을 메서드 안에서 확인 - 위에서 주문을 읽은 뒤 체결이 먼저 커밋됐으면
            // 읽은 시점의 버전과 달라서 실패한다. 그 경우 트랜잭션을 롤백하고 충돌로 응답.
            // 주문 행을 잔고(회원 행)보다 먼저 갱신하는 이유: 체결 트랜잭션(MatchingService.execute)도 주문 → 회원 순서라,
            // 순서를 맞춰야 둘이 서로의 행을 기다리는 데드락이 안 생긴다
            orderRepository.saveAndFlush(order);
        } catch (OptimisticLockingFailureException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.info("[주문취소] {} / {} 취소 처리 중 체결이 먼저 반영됨 - 취소 롤백", memberId, orderId);
            return CancelResult.CONFLICT;
        }

        // 매수는 주문 생성 시점에 전체 금액을 예약(차감)해뒀으므로, 취소 시 미체결 잔량분만큼 환급
        if (order.getOrderType() == OrderType.BUY) {
            int refund = order.getPrice() * (order.getQuantity() - order.getMatchedQuantity());
            if (refund > 0) {
                memberRepository.addSeed(memberId, refund);
            }
        }

        log.info("[주문취소] {} / {}", memberId, orderId);
        return CancelResult.OK;
    }
}
