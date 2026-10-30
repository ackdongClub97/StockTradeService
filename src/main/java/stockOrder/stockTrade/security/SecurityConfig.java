package stockOrder.stockTrade.security;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import stockOrder.stockTrade.member.CustomerDetailsService;
import stockOrder.stockTrade.member.KakaoOAuth2UserService;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomerDetailsService userDetailService;
    private final KakaoOAuth2UserService kakaoOAuth2UserService;

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
                // 세션은 Spring Session JDBC로 MySQL(SPRING_SESSION 테이블)에 저장 - 서버 여러 대 뒤 nginx 라운드로빈이라
                // 로그인한 서버와 다음 요청을 받는 서버가 달라도 같은 세션을 본다(예전엔 서버 메모리라 sticky session이 필요했음)
                ).sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                );

        return http.build();
    }

}
