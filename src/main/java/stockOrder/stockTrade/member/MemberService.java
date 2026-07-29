package stockOrder.stockTrade.member;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@Slf4j
@RequiredArgsConstructor
public class MemberService {
    private final MemberRepository memberRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public Member join(Member member) throws IllegalAccessException {
        if(memberRepository.existsByMemberId(member.getMemberId())) {
            throw new IllegalAccessException(("이미 사용 중인 아이디입니다."));
        }

        if(memberRepository.existsByPhone(member.getPhone())) {
            throw new IllegalAccessException("이미 가입된 번호입니다.");
        }
        // 비밀번호 암호화
        member.setPassword(passwordEncoder.encode(member.getPassword()));

        member.setSeed(10000000);
        member.setLevel(1);
        member.setMemberStatus("ACTIVE");
        member.setJoinDate(LocalDate.now().toString());
        member.setProvider("LOCAL");
        System.out.println("데이터 저장");
        memberRepository.save(member);

        return member;
    }

    public Member updateProfile(String memberId, String name, String phone) throws IllegalAccessException {
        Member member = memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalAccessException("존재하지 않는 회원입니다."));

        if(phone != null && !phone.equals(member.getPhone()) && memberRepository.existsByPhone(phone)) {
            throw new IllegalAccessException("이미 등록된 전화번호입니다.");
        }

        if(name != null && !name.isBlank()) {
            member.setName(name);
        }
        if(phone != null && !phone.isBlank()) {
            member.setPhone(phone);
        }

        memberRepository.save(member);
        return member;
    }

    public void withdraw(String memberId) throws IllegalAccessException {
        Member member = memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalAccessException("존재하지 않는 회원입니다."));

        member.setMemberStatus("WITHDRAWN");
        memberRepository.save(member);
    }

}
