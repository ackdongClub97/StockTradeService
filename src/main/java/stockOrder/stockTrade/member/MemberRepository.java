package stockOrder.stockTrade.member;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member, String> {
    Optional<Member> findByMemberId(String memberId);
    boolean existsByMemberId(String memberId);
    boolean existsByPhone(String phone);

    @Query("SELECT o FROM Member o WHERE o.memberId = :memberId")
    Member findMemberInfo(
            @Param("memberId") String memberId
    );

    /* 주문 접수용 - 회원 행에 쓰기 락(SELECT ... FOR UPDATE)을 걸어 같은 회원의 동시 주문을 한 건씩 처리하게 한다.
       잔고 확인(매수)과 보유수량 확인(매도, 다른 테이블 합계 조회)이 둘 다 "읽고 → 확인 → 쓰기"라서,
       락 없이 동시에 들어오면 둘 다 확인을 통과해 잔고/보유수량을 넘어서 주문이 들어갈 수 있었음. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.memberId = :memberId")
    Optional<Member> findByMemberIdForUpdate(@Param("memberId") String memberId);

    /* 잔고 증감을 DB에서 원자적으로 처리 (seed = seed + delta).
       예전엔 엔티티를 읽어 setSeed 후 저장하는 방식이라, 체결 환급/취소 환급/주문 차감이 동시에 일어나면
       나중에 저장한 쪽이 먼저 저장한 쪽의 변경을 덮어써서 금액이 사라질 수 있었음(lost update). */
    @Transactional
    @Modifying
    @Query("UPDATE Member m SET m.seed = m.seed + :delta WHERE m.memberId = :memberId")
    int addSeed(@Param("memberId") String memberId, @Param("delta") int delta);
}
