package com.softcatch.smart.auth;

import jakarta.persistence.Entity;
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

  protected Member() {}

  Member(String loginId, String passwordHash, String name) {
    this.loginId = loginId;
    this.passwordHash = passwordHash;
    this.name = name;
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
}
