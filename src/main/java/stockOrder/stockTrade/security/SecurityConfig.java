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
                );

        return http.build();
    }

}
