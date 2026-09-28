package com.softcatch.smart.alias;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Alias {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long memberId;
  private String alias;
  private String accountNumber;

  protected Alias() {}

  Alias(Long memberId, String alias, String accountNumber) {
    this.memberId = memberId;
    this.alias = alias;
    this.accountNumber = accountNumber;
  }

  public Long getId() {
    return id;
  }

  public String getAlias() {
    return alias;
  }

  public String getAccountNumber() {
    return accountNumber;
  }
}
