package com.softcatch.smart.alias;

import com.softcatch.smart.account.Account;
import com.softcatch.smart.account.AccountRepository;
import com.softcatch.smart.alias.dto.response.AliasResponse;
import com.softcatch.smart.auth.MemberRepository;
import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
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
    Account account =
        accounts
            .findByAccountNumber(accountNumber)
            .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND));
    String ownerName = members.findById(account.getMemberId()).orElseThrow().getName();

    Alias saved = aliases.save(new Alias(memberId, alias, accountNumber));
    return new AliasResponse(saved.getId(), saved.getAlias(), saved.getAccountNumber(), ownerName);
  }
}
