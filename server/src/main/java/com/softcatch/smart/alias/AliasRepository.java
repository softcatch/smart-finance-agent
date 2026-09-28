package com.softcatch.smart.alias;

import com.softcatch.smart.alias.dto.response.AliasResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AliasRepository extends JpaRepository<Alias, Long> {

  // 별칭마다 계좌·회원을 따로 조회하면 N+1이 나서, 조인 하나로 목록 전체를 한 번에 가져온다.
  @Query(
      "SELECT new com.softcatch.smart.alias.dto.response.AliasResponse("
          + "a.id, a.alias, a.accountNumber, m.name) "
          + "FROM Alias a, Account acc, Member m "
          + "WHERE a.memberId = :memberId AND acc.accountNumber = a.accountNumber "
          + "AND m.id = acc.memberId "
          + "ORDER BY a.id ASC")
  List<AliasResponse> findResponsesByMemberId(Long memberId);

  Optional<Alias> findByMemberIdAndAlias(Long memberId, String alias);
}
