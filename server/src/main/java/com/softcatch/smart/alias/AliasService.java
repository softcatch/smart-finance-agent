package com.softcatch.smart.alias;

import com.softcatch.smart.account.Account;
import com.softcatch.smart.account.AccountRepository;
import com.softcatch.smart.alias.dto.response.AliasResponse;
import com.softcatch.smart.auth.MemberRepository;
import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AliasService {

  private final AliasRepository aliases;
  private final AccountRepository accounts;
  private final MemberRepository members;

  AliasService(AliasRepository aliases, AccountRepository accounts, MemberRepository members) {
    this.aliases = aliases;
    this.accounts = accounts;
    this.members = members;
  }

  @Transactional
  AliasResponse register(Long memberId, String alias, String accountNumber) {
    String ownerName = resolveOwnerName(accountNumber);
    Alias saved;
    try {
      saved = aliases.save(new Alias(memberId, alias, accountNumber));
    } catch (DataIntegrityViolationException e) {
      // (member_id, alias) UNIQUE 제약 위반 — 같은 이름으로 이미 등록된 별칭이 있다.
      throw new ApiException(ErrorCode.DUPLICATE_ALIAS);
    }
    return new AliasResponse(saved.getId(), saved.getAlias(), saved.getAccountNumber(), ownerName);
  }

  List<AliasResponse> list(Long memberId) {
    return aliases.findResponsesByMemberId(memberId);
  }

  private String resolveOwnerName(String accountNumber) {
    Account account =
        accounts
            .findByAccountNumber(accountNumber)
            .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND));
    return members.findById(account.getMemberId()).orElseThrow().getName();
  }
}
