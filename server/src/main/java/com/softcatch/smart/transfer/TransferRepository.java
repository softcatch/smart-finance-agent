package com.softcatch.smart.transfer;

import com.softcatch.smart.transfer.dto.response.TransferHistoryItem;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

  // 송금마다 계좌·회원을 따로 조회하면 N+1이 나서, 조인 하나로 방향(SENT/RECEIVED)과
  // 상대방 정보까지 한 번에 계산한다. 커서(t.id < :cursor)로 최신순 페이지네이션한다.
  @Query(
      "SELECT new com.softcatch.smart.transfer.dto.response.TransferHistoryItem("
          + "t.id, "
          + "CASE WHEN fromAcc.memberId = :memberId THEN 'SENT' ELSE 'RECEIVED' END, "
          + "CASE WHEN fromAcc.memberId = :memberId THEN toMember.name ELSE fromMember.name END, "
          + "CASE WHEN fromAcc.memberId = :memberId THEN t.toAccountNumber ELSE t.fromAccountNumber END, "
          + "t.amount, t.createdAt) "
          + "FROM Transfer t, Account fromAcc, Account toAcc, Member fromMember, Member toMember "
          + "WHERE fromAcc.accountNumber = t.fromAccountNumber "
          + "AND toAcc.accountNumber = t.toAccountNumber "
          + "AND fromMember.id = fromAcc.memberId "
          + "AND toMember.id = toAcc.memberId "
          + "AND (fromAcc.memberId = :memberId OR toAcc.memberId = :memberId) "
          + "AND (:cursor IS NULL OR t.id < :cursor) "
          + "ORDER BY t.id DESC")
  List<TransferHistoryItem> findHistory(Long memberId, Long cursor, Pageable pageable);
}
