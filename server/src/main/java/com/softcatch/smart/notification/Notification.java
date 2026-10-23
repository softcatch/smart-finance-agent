package com.softcatch.smart.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class Notification {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long memberId;
  private Long transferId;

  @Enumerated(EnumType.STRING)
  private Direction direction;

  private String counterpartName;
  private Long amount;

  // DB 기본값(now())에 맡긴다 — 같은 트랜잭션의 송금(transfer.created_at)과 같은 시각이다.
  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  protected Notification() {}

  Notification(
      Long memberId, Long transferId, Direction direction, String counterpartName, Long amount) {
    this.memberId = memberId;
    this.transferId = transferId;
    this.direction = direction;
    this.counterpartName = counterpartName;
    this.amount = amount;
  }

  public Long getId() {
    return id;
  }

  public Long getMemberId() {
    return memberId;
  }

  public Long getTransferId() {
    return transferId;
  }

  public Direction getDirection() {
    return direction;
  }

  public String getCounterpartName() {
    return counterpartName;
  }

  public Long getAmount() {
    return amount;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
