package com.softcatch.smart.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

// 상세 보기 ID의 주인(FR-016). 보고서 자체는 FastAPI가 클릭할 때 만들고, 여기서는 누구 것인지만 안다.
@Entity
public class ChatDetail {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String detailId;
  private Long memberId;

  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  protected ChatDetail() {}

  ChatDetail(String detailId, Long memberId) {
    this.detailId = detailId;
    this.memberId = memberId;
  }

  public Long getId() {
    return id;
  }

  public String getDetailId() {
    return detailId;
  }

  public Long getMemberId() {
    return memberId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
