package com.softcatch.smart.transfer;

import com.softcatch.smart.transfer.dto.response.TransferHistoryItem;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

  // 내 계좌 조건을 Account 조인이 아니라 transfer 자신의 컬럼(from/to_account_number)에
  // 직접 걸어서 V7의 인덱스를 타게 한다 — Account 조인으로 걸면 그 조건이 인덱스를 못 타서
  // 내역이 적은 사용자도 transfer 테이블 전체를 id 역순으로 훑어야 했다(Codex 리뷰 지적).
  // 상대방 이름은 이제 상대 계좌 하나만 조인해서 구한다(N+1 방지는 그대로 유지).
  @Query(
      "SELECT new com.softcatch.smart.transfer.dto.response.TransferHistoryItem("
          + "t.id, "
          + "CASE WHEN t.fromAccountNumber IN :myAccountNumbers THEN 'SENT' ELSE 'RECEIVED' END, "
          + "counterpartMember.name, "
          + "CASE WHEN t.fromAccountNumber IN :myAccountNumbers THEN t.toAccountNumber ELSE t.fromAccountNumber END, "
          + "t.amount, t.createdAt) "
          + "FROM Transfer t, Account counterpartAccount, Member counterpartMember "
          + "WHERE counterpartAccount.accountNumber = "
          + "  CASE WHEN t.fromAccountNumber IN :myAccountNumbers THEN t.toAccountNumber ELSE t.fromAccountNumber END "
          + "AND counterpartMember.id = counterpartAccount.memberId "
          + "AND (t.fromAccountNumber IN :myAccountNumbers OR t.toAccountNumber IN :myAccountNumbers) "
          + "AND (:cursor IS NULL OR t.id < :cursor) "
          + "ORDER BY t.id DESC")
  List<TransferHistoryItem> findHistory(
      List<String> myAccountNumbers, Long cursor, Pageable pageable);
}
