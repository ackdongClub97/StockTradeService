package stockOrder.stockTrade.member;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/* 폼 로그인(UserDetails)과 카카오 로그인(OAuth2User) 양쪽 인증 방식이
   동일한 @AuthenticationPrincipal CustomerDetails 타입으로 처리되도록 두 인터페이스를 모두 구현 */
public class CustomerDetails implements UserDetails, OAuth2User {

    private final Member member;
    private final Map<String, Object> attributes;

    public CustomerDetails(Member member) {
        this(member, null);
    }

    public CustomerDetails(Member member, Map<String, Object> attributes) {
        this.member = member;
        this.attributes = attributes;
    }

    public Member getMember() {
        return member;
    }

    @Override
    public String getUsername() {
        return member.getName();
    }

    @Override
    public String getPassword() {
        return member.getPassword();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public String getName() {
        return member.getMemberId();
    }

    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return "ACTIVE".equals(member.getMemberStatus()); }
}
