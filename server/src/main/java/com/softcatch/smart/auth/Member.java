package com.softcatch.smart.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Member {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String loginId;
  private String passwordHash;
  private String name;

  @Enumerated(EnumType.STRING)
  private Role role;

  protected Member() {}

  Member(String loginId, String passwordHash, String name, Role role) {
    this.loginId = loginId;
    this.passwordHash = passwordHash;
    this.name = name;
    this.role = role;
  }

  public Long getId() {
    return id;
  }

  String getPasswordHash() {
    return passwordHash;
  }

  public String getName() {
    return name;
  }

  public boolean isAdmin() {
    return role == Role.ADMIN;
  }
}
