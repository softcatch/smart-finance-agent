package com.softcatch.smart.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Account {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long memberId;
  private String accountNumber;
  private String idempotencyKey;
  private Long balance;
  private boolean isPrimary;

  protected Account() {}

  Account(Long memberId, String accountNumber, String idempotencyKey, boolean isPrimary) {
    this.memberId = memberId;
    this.accountNumber = accountNumber;
    this.idempotencyKey = idempotencyKey;
    this.balance = 0L;
    this.isPrimary = isPrimary;
  }

  public Long getId() {
    return id;
  }

  public Long getMemberId() {
    return memberId;
  }

  public String getAccountNumber() {
    return accountNumber;
  }

  public boolean isPrimary() {
    return isPrimary;
  }
}
