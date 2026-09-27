package com.softcatch.smart.auth;

import com.softcatch.smart.common.ApiResponse;
import com.softcatch.smart.common.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

// /api/v1/auth/**만 열고 나머지 /api/**·/internal/**는 Bearer 토큰을 요구한다.
// 쿠키를 쓰지 않으므로 CSRF는 해당 없음(범위 원칙). 세션 없음(JWT라 STATELESS).
// 페이지 보호(비로그인 차단)는 범위 밖 — JSP·정적 자원은 그대로 연다.
@Configuration
@EnableWebSecurity
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtDecoder jwtDecoder, AuthenticationEntryPoint authEntryPoint)
      throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/v1/auth/**")
                    .permitAll()
                    .requestMatchers("/api/**", "/internal/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .exceptionHandling(handling -> handling.authenticationEntryPoint(authEntryPoint))
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)))
        .build();
  }

  // 인증 실패(토큰 없음·위조·만료)도 공통 응답 형식({success,data,error})으로 반환한다.
  @Bean
  AuthenticationEntryPoint authEntryPoint() {
    ObjectMapper json = new ObjectMapper();
    return (request, response, authException) -> {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write(json.writeValueAsString(ApiResponse.fail(ErrorCode.AUTH_INVALID)));
    };
  }
}
