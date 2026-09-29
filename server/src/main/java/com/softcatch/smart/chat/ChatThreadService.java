package com.softcatch.smart.chat;

import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class ChatThreadService {

  private final ChatThreadRepository chatThreads;

  ChatThreadService(ChatThreadRepository chatThreads) {
    this.chatThreads = chatThreads;
  }

  ChatThread create(Long memberId) {
    String threadId = "th_" + UUID.randomUUID();
    return chatThreads.save(new ChatThread(threadId, memberId));
  }
}
