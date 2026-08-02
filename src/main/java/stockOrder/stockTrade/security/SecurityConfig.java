package stockOrder.stockTrade.security;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import stockOrder.stockTrade.member.CustomerDetailsService;
import stockOrder.stockTrade.member.KakaoOAuth2UserService;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomerDetailsService userDetailService;
    private final KakaoOAuth2UserService kakaoOAuth2UserService;

    /* 현재 로그인 세션 수 파악(어드민 화면)을 위한 세션 등록소 - 별도 의존성이 없어서 순환참조 위험 없음 */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /* HttpSession이 자연 만료/무효화될 때도 SessionRegistry가 정리되도록 이벤트를 퍼블리시 */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .userDetailsService(userDetailService)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(
                                "/login",
                                "/join",
                                "/css/**",
                                "/js/**",
                                "/stockHome",
                                "/stock/**",
                                "/api/member/**",
                                "/api/rank/**",
                                "/api/news/**",
                                "/api/stock/**",
                                "/api/stock/*/stream/**",
                                "/api/volume/**",
                                "/api/sse/**",
                                "/h2-console/**",
                                "/oauth2/**",
                                "/login/oauth2/**"
                        )
                        .permitAll()
                        .anyRequest().authenticated()
                )
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/stockHome", true)
                        .permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login")
                        .userInfoEndpoint(userInfo -> userInfo.userService(kakaoOAuth2UserService))
                        .defaultSuccessUrl("/stockHome", true)
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login")
                ).sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                                .maximumSessions(-1) // 세션 수를 제한하진 않되, SessionRegistry에 계속 등록/추적되게 함
                                .sessionRegistry(sessionRegistry())
                );

        return http.build();
    }

}
