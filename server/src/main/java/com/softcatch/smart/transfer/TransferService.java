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

    if (accounts.decreaseBalanceIfSufficient(fromAccount.getId(), request.getAmount()) == 0) {
      throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE);
    }

    Account toAccount =
        accounts.findByAccountNumber(request.getRecipientAccountNumber()).orElseThrow();
    accounts.increaseBalance(toAccount.getId(), request.getAmount());

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
}
