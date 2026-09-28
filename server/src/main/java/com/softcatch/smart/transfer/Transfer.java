package com.softcatch.smart.transfer;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Transfer {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String fromAccountNumber;
  private String toAccountNumber;
  private Long amount;

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
}
