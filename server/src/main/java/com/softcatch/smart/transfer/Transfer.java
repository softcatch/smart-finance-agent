package com.softcatch.smart.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class Transfer {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String fromAccountNumber;
  private String toAccountNumber;
  private Long amount;

  // DB 컬럼(created_at)은 처음부터 DEFAULT now()로 있었다 — INSERT는 DB 기본값에 맡기고 읽기만 한다.
  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  protected Transfer() {}

  Transfer(String fromAccountNumber, String toAccountNumber, Long amount) {
    this.fromAccountNumber = fromAccountNumber;
    this.toAccountNumber = toAccountNumber;
    this.amount = amount;
  }

  public Long getId() {
    return id;
  }

  public String getFromAccountNumber() {
    return fromAccountNumber;
  }

  public String getToAccountNumber() {
    return toAccountNumber;
  }

  public Long getAmount() {
    return amount;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
