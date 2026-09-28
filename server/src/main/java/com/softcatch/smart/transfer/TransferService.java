package com.softcatch.smart.transfer;

import com.softcatch.smart.account.Account;
import com.softcatch.smart.account.AccountRepository;
import com.softcatch.smart.alias.AliasRepository;
import com.softcatch.smart.auth.MemberRepository;
import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
import com.softcatch.smart.transfer.dto.request.TransferCreateRequest;
import com.softcatch.smart.transfer.dto.response.TransferConfirmResponse;
import com.softcatch.smart.transfer.dto.response.TransferCreateResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TransferService {

  private static final Duration REQUEST_TTL = Duration.ofMinutes(5);

  private final TransferRequestRepository transferRequests;
  private final TransferRepository transfers;
  private final AccountRepository accounts;
  private final AliasRepository aliases;
  private final MemberRepository members;

  TransferService(
      TransferRequestRepository transferRequests,
      TransferRepository transfers,
      AccountRepository accounts,
      AliasRepository aliases,
      MemberRepository members) {
    this.transferRequests = transferRequests;
    this.transfers = transfers;
    this.accounts = accounts;
    this.aliases = aliases;
    this.members = members;
  }

  @Transactional
  TransferCreateResponse createRequest(Long senderMemberId, TransferCreateRequest request) {
    if (request.amount() == null || request.amount() <= 0) {
      throw new ApiException(ErrorCode.INVALID_AMOUNT);
    }

    String targetAccountNumber = resolveTargetAccountNumber(senderMemberId, request);

    Account recipientAccount =
        accounts
            .findByAccountNumber(targetAccountNumber)
            .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND));
    String recipientName = members.findById(recipientAccount.getMemberId()).orElseThrow().getName();

    Account fromAccount =
        accounts
            .findByMemberIdAndIsPrimaryTrue(senderMemberId)
            .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND));

    String requestId = "tr_" + UUID.randomUUID();
    Instant expiresAt = Instant.now().plus(REQUEST_TTL);

    transferRequests.save(
        new TransferRequest(
            requestId,
            senderMemberId,
            fromAccount.getAccountNumber(),
            targetAccountNumber,
            recipientName,
            request.amount(),
            expiresAt));

    return new TransferCreateResponse(
        requestId,
        recipientName,
        targetAccountNumber,
        fromAccount.getAccountNumber(),
        request.amount(),
        expiresAt.toString());
  }

  private String resolveTargetAccountNumber(Long senderMemberId, TransferCreateRequest request) {
    if (request.recipientAccountNumber() != null) {
      return request.recipientAccountNumber();
    }
    return aliases
        .findByMemberIdAndAlias(senderMemberId, request.alias())
        .map(alias -> alias.getAccountNumber())
        .orElseThrow(() -> new ApiException(ErrorCode.ALIAS_NOT_FOUND));
  }

  @Transactional
  TransferConfirmResponse confirm(Long callerMemberId, String requestId) {
    TransferRequest request =
        transferRequests
            .findByRequestId(requestId)
            .filter(r -> r.getSenderMemberId().equals(callerMemberId))
            .orElseThrow(() -> new ApiException(ErrorCode.TRANSFER_REQUEST_NOT_FOUND));

    if (Instant.now().isAfter(request.getExpiresAt())) {
      throw new ApiException(ErrorCode.TRANSFER_REQUEST_EXPIRED);
    }

    if (transferRequests.markUsedIfNotUsed(requestId) == 0) {
      throw new ApiException(ErrorCode.TRANSFER_REQUEST_ALREADY_USED);
    }

    Account fromAccount =
        accounts.findByAccountNumber(request.getFromAccountNumber()).orElseThrow();
    if (!fromAccount.getMemberId().equals(callerMemberId)) {
      throw new ApiException(ErrorCode.ACCOUNT_NOT_OWNED);
    }

    Account toAccount =
        accounts.findByAccountNumber(request.getRecipientAccountNumber()).orElseThrow();

    // 두 계좌를 항상 id 오름차순으로 갱신한다 — A→B와 B→A가 동시에 확정되면 반대 순서로
    // 잠갔을 때 서로의 행을 기다리며 교착 상태(deadlock)가 나는데, 순서를 고정하면
    // 두 트랜잭션이 같은 행부터 순서대로 줄을 서서 교착이 안 생긴다.
    if (fromAccount.getId() < toAccount.getId()) {
      decreaseThenIncrease(fromAccount.getId(), toAccount.getId(), request.getAmount());
    } else {
      increaseThenDecrease(fromAccount.getId(), toAccount.getId(), request.getAmount());
    }

    Transfer saved =
        transfers.save(
            new Transfer(
                request.getFromAccountNumber(),
                request.getRecipientAccountNumber(),
                request.getAmount()));

    Long balanceAfter = accounts.findById(fromAccount.getId()).orElseThrow().getBalance();

    return new TransferConfirmResponse(
        saved.getId(),
        request.getRecipientName(),
        request.getAmount(),
        request.getFromAccountNumber(),
        balanceAfter);
  }

  private void decreaseThenIncrease(Long fromAccountId, Long toAccountId, Long amount) {
    if (accounts.decreaseBalanceIfSufficient(fromAccountId, amount) == 0) {
      throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE);
    }
    accounts.increaseBalance(toAccountId, amount);
  }

  private void increaseThenDecrease(Long fromAccountId, Long toAccountId, Long amount) {
    accounts.increaseBalance(toAccountId, amount);
    if (accounts.decreaseBalanceIfSufficient(fromAccountId, amount) == 0) {
      throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE);
    }
  }
}
