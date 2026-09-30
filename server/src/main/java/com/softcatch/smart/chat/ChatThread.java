package com.softcatch.smart.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class ChatThread {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String threadId;
  private Long memberId;

  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  protected ChatThread() {}

  ChatThread(String threadId, Long memberId) {
    this.threadId = threadId;
    this.memberId = memberId;
  }

  public Long getId() {
    return id;
  }

  public String getThreadId() {
    return threadId;
  }

  public Long getMemberId() {
    return memberId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
