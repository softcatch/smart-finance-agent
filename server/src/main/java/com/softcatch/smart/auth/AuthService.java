package com.softcatch.smart.auth;

import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
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

  AuthService(
      MemberRepository members,
      PasswordEncoder passwordEncoder,
      JwtEncoder jwtEncoder,
      @Value("${smart.jwt.expiration-seconds}") long expirationSeconds) {
    this.members = members;
    this.passwordEncoder = passwordEncoder;
    this.jwtEncoder = jwtEncoder;
    this.expirationSeconds = expirationSeconds;
  }

  Long signup(String loginId, String rawPassword, String name) {
    members
        .findByLoginId(loginId)
        .ifPresent(
            m -> {
              throw new ApiException(ErrorCode.DUPLICATE_LOGIN_ID);
            });
    return members.save(new Member(loginId, passwordEncoder.encode(rawPassword), name)).getId();
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
