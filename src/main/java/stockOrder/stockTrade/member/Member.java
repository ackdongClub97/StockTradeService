package stockOrder.stockTrade.member;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name="members")
public class Member {

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
