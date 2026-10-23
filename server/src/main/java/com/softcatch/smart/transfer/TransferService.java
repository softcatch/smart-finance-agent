package com.softcatch.smart.transfer;

import com.softcatch.smart.account.Account;
import com.softcatch.smart.account.AccountRepository;
import com.softcatch.smart.alias.AliasRepository;
import com.softcatch.smart.auth.MemberRepository;
import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
import com.softcatch.smart.notification.NotificationService;
import com.softcatch.smart.transfer.dto.request.TransferCreateRequest;
import com.softcatch.smart.transfer.dto.response.TransferConfirmResponse;
import com.softcatch.smart.transfer.dto.response.TransferCreateResponse;
import com.softcatch.smart.transfer.dto.response.TransferHistoryItem;
import com.softcatch.smart.transfer.dto.response.TransferHistoryListResponse;
import com.softcatch.smart.transfer.dto.response.TransferSummaryResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TransferService {

  private static class PasswordMismatchException extends ApiException {
    PasswordMismatchException() {
      super(ErrorCode.ACCOUNT_PASSWORD_INVALID);
    }
  }

  private static final Duration REQUEST_TTL = Duration.ofMinutes(5);
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final TransferRequestRepository transferRequests;
  private final TransferRepository transfers;
  private final AccountRepository accounts;
  private final AliasRepository aliases;
  private final MemberRepository members;
  private final PasswordEncoder passwordEncoder;
  private final NotificationService notifications;

  TransferService(
      TransferRequestRepository transferRequests,
      TransferRepository transfers,
      AccountRepository accounts,
      AliasRepository aliases,
      MemberRepository members,
      PasswordEncoder passwordEncoder,
      NotificationService notifications) {
    this.transferRequests = transferRequests;
    this.transfers = transfers;
    this.accounts = accounts;
    this.aliases = aliases;
    this.members = members;
    this.passwordEncoder = passwordEncoder;
    this.notifications = notifications;
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

  // 비밀번호가 틀리면 송금은 안 하되 요청ID는 폐기한다 — 사용 처리가 커밋되도록 이 예외만 롤백에서
  // 뺀다. 다른 예외(잔액 부족 등)는 지금처럼 전부 롤백한다.
  @Transactional(noRollbackFor = PasswordMismatchException.class)
  TransferConfirmResponse confirm(Long callerMemberId, String requestId, String password) {
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

    // 출금 계좌의 비밀번호를 확인한다. null은 빈 문자열로 보아 불일치로 처리한다.
    if (!passwordEncoder.matches(password == null ? "" : password, fromAccount.getPasswordHash())) {
      throw new PasswordMismatchException();
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

    // 알림(FR-024)은 송금과 같은 트랜잭션에 남긴다 — 커밋되면 반드시 남고 롤백되면 없다.
    notifications.recordTransfer(
        saved.getId(),
        callerMemberId,
        members.findById(callerMemberId).orElseThrow().getName(),
        toAccount.getMemberId(),
        request.getRecipientName(),
        request.getAmount());

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

  TransferHistoryListResponse list(Long memberId, Long cursor, Integer size) {
    int pageSize = size != null ? size : DEFAULT_PAGE_SIZE;

    List<String> myAccountNumbers =
        accounts.findByMemberIdOrderByIdAsc(memberId).stream()
            .map(Account::getAccountNumber)
            .toList();
    if (myAccountNumbers.isEmpty()) {
      return new TransferHistoryListResponse(List.of(), null, false);
    }

    // size보다 하나 더 가져와서, 남는 게 있으면 hasNext=true로 판단한다.
    List<TransferHistoryItem> rows =
        transfers.findHistory(myAccountNumbers, cursor, Pageable.ofSize(pageSize + 1));

    boolean hasNext = rows.size() > pageSize;
    List<TransferHistoryItem> items = hasNext ? rows.subList(0, pageSize) : rows;
    Long nextCursor = hasNext ? items.get(items.size() - 1).transferId() : null;

    return new TransferHistoryListResponse(items, nextCursor, hasNext);
  }

  // I-04: 기간(from~to, 한국 시간 하루 단위 양끝 포함) 내 합계와 내역. 합계는 여기서(DB 집계로) 계산한다.
  TransferSummaryResponse summary(
      Long memberId, LocalDate from, LocalDate to, Long cursor, Integer size) {
    int pageSize = size != null ? size : DEFAULT_PAGE_SIZE;

    List<String> myAccountNumbers =
        accounts.findByMemberIdOrderByIdAsc(memberId).stream()
            .map(Account::getAccountNumber)
            .toList();
    if (myAccountNumbers.isEmpty()) {
      return new TransferSummaryResponse(0L, 0L, 0, List.of(), null, false);
    }

    Instant start = from.atStartOfDay(KST).toInstant();
    Instant end = to.plusDays(1).atStartOfDay(KST).toInstant();

    List<TransferHistoryItem> rows =
        transfers.findHistoryBetween(
            myAccountNumbers, start, end, cursor, Pageable.ofSize(pageSize + 1));
    boolean hasNext = rows.size() > pageSize;
    List<TransferHistoryItem> items = hasNext ? rows.subList(0, pageSize) : rows;
    Long nextCursor = hasNext ? items.get(items.size() - 1).transferId() : null;

    return new TransferSummaryResponse(
        transfers.sumSent(myAccountNumbers, start, end),
        transfers.sumReceived(myAccountNumbers, start, end),
        Math.toIntExact(transfers.countBetween(myAccountNumbers, start, end)),
        items,
        nextCursor,
        hasNext);
  }
}
