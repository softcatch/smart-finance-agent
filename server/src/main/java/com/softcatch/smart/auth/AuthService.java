package com.softcatch.smart.auth;

import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
class AuthService {

  private final MemberRepository members;
  private final PasswordEncoder passwordEncoder;
  private final JwtEncoder jwtEncoder;
  private final long expirationSeconds;
  private final List<String> adminLoginIds;

  AuthService(
      MemberRepository members,
      PasswordEncoder passwordEncoder,
      JwtEncoder jwtEncoder,
      @Value("${smart.jwt.expiration-seconds}") long expirationSeconds,
      @Value("${smart.admin.login-ids:}") List<String> adminLoginIds) {
    this.members = members;
    this.passwordEncoder = passwordEncoder;
    this.jwtEncoder = jwtEncoder;
    this.expirationSeconds = expirationSeconds;
    this.adminLoginIds = adminLoginIds;
  }

  Long signup(String loginId, String rawPassword, String name) {
    members
        .findByLoginId(loginId)
        .ifPresent(
            m -> {
              throw new ApiException(ErrorCode.DUPLICATE_LOGIN_ID);
            });
    // 설정한 아이디로 먼저 가입한 사람이 관리자다(아이디는 유일하다). 그 밖의 관리자는 운영자가 DB에서 지정한다.
    Role role = adminLoginIds.contains(loginId) ? Role.ADMIN : Role.USER;
    try {
      return members
          .save(new Member(loginId, passwordEncoder.encode(rawPassword), name, role))
          .getId();
    } catch (DataIntegrityViolationException e) {
      // 동시 가입 요청이 사전 조회를 함께 통과한 경우 — UNIQUE 제약 위반을 409로 변환한다.
      throw new ApiException(ErrorCode.DUPLICATE_LOGIN_ID);
    }
  }

  String login(String loginId, String rawPassword) {
    Member member =
        members
            .findByLoginId(loginId)
            .filter(m -> passwordEncoder.matches(rawPassword, m.getPasswordHash()))
            .orElseThrow(() -> new ApiException(ErrorCode.AUTH_INVALID));

    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(String.valueOf(member.getId()))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(expirationSeconds))
            .build();
    return jwtEncoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }
}
