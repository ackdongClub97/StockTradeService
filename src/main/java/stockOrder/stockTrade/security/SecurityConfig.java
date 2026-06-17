package stockOrder.stockTrade.security;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import stockOrder.stockTrade.member.CustomerDetailsService;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomerDetailsService userDetailService;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
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
                                "/h2-console/**"
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
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login")
                ).sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                );

        return http.build();
    }

}
