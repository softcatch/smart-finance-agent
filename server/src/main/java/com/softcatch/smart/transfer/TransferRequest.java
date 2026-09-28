package com.softcatch.smart.transfer;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class TransferRequest {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String requestId;
  private Long senderMemberId;
  private String fromAccountNumber;
  private String recipientAccountNumber;
  private String recipientName;
  private Long amount;
  private boolean used;
  private Instant expiresAt;

  protected TransferRequest() {}

  TransferRequest(
      String requestId,
      Long senderMemberId,
      String fromAccountNumber,
      String recipientAccountNumber,
      String recipientName,
      Long amount,
      Instant expiresAt) {
    this.requestId = requestId;
    this.senderMemberId = senderMemberId;
    this.fromAccountNumber = fromAccountNumber;
    this.recipientAccountNumber = recipientAccountNumber;
    this.recipientName = recipientName;
    this.amount = amount;
    this.used = false;
    this.expiresAt = expiresAt;
  }

  public String getRequestId() {
    return requestId;
  }

  public Long getSenderMemberId() {
    return senderMemberId;
  }

  public String getFromAccountNumber() {
    return fromAccountNumber;
  }

  public String getRecipientAccountNumber() {
    return recipientAccountNumber;
  }

  public String getRecipientName() {
    return recipientName;
  }

  public Long getAmount() {
    return amount;
  }

  public boolean isUsed() {
    return used;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }
}
