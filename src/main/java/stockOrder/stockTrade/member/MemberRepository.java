package stockOrder.stockTrade.member;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
