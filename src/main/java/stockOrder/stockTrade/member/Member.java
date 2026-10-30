package stockOrder.stockTrade.member;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name="members")
// Serializable: 로그인 세션(SecurityContext -> CustomerDetails -> Member)을 Spring Session JDBC가 DB에 저장할 때
// 자바 직렬화를 쓰기 때문. 세션이 서버 메모리에만 있을 땐 필요 없었음
public class Member implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String memberId;        // 아이디

    private String email;           // 이메일
    private String password;        // 비밀번호
    private String name;            // 이름
    private String phone;           // 전화번호
    private int seed;               // 투자금액
    private int level;              // 레벨
    private String account;         // 한투 모의 계좌
    private String memberStatus;    // 활성화/비활성화(탈퇴)
    private String joinDate;
    private LocalDateTime loginTime;
    private String provider;        // 가입 경로: LOCAL / KAKAO
}
