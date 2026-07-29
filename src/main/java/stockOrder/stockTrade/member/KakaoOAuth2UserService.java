package stockOrder.stockTrade.member;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class KakaoOAuth2UserService extends DefaultOAuth2UserService {

    private final MemberRepository memberRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        Map<String, Object> attributes = oAuth2User.getAttributes();

        String memberId = "kakao_" + attributes.get("id");

        @SuppressWarnings("unchecked")
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = kakaoAccount != null ? (Map<String, Object>) kakaoAccount.get("profile") : null;

        String nickname = (profile != null && profile.get("nickname") != null)
                ? profile.get("nickname").toString() : "카카오사용자";
        String email = (kakaoAccount != null && kakaoAccount.get("email") != null)
                ? kakaoAccount.get("email").toString() : null;

        Member member = memberRepository.findByMemberId(memberId).orElseGet(() -> {
            Member newMember = new Member();
            newMember.setMemberId(memberId);
            newMember.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            newMember.setName(nickname);
            newMember.setEmail(email);
            newMember.setProvider("KAKAO");
            newMember.setSeed(10000000);
            newMember.setLevel(1);
            newMember.setMemberStatus("ACTIVE");
            newMember.setJoinDate(LocalDate.now().toString());

            log.info("[Kakao] 신규 회원 자동 가입: {}", memberId);
            return memberRepository.save(newMember);
        });

        if(!"ACTIVE".equals(member.getMemberStatus())) {
            throw new OAuth2AuthenticationException(new OAuth2Error("account_withdrawn"), "탈퇴한 계정입니다.");
        }

        return new CustomerDetails(member, attributes);
    }
}
