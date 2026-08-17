/*
 * Copyright 2026 Guillermo Castro
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package net.jircd.core.session;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only path that writes to one session's socket (research.md "Message fan-out concurrency
 * model"). Owns a bounded outbound queue and a dedicated writer virtual thread; a sender never
 * writes cross-thread to another session's socket, and never blocks waiting for a slow recipient —
 * queue overflow transitions that recipient to {@code CLOSING} instead (data-model.md {@code
 * ClientSession} validation rules).
 */
public final class SessionWriter implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(SessionWriter.class);
  private static final int QUEUE_CAPACITY = 256;

  private final ClientSession session;
  private final OutputStream out;
  private final TagRenderer tagRenderer;
  private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final Thread writerThread;
  private volatile Runnable onOverflow = () -> {};

  public SessionWriter(ClientSession session, OutputStream out, TagRenderer tagRenderer) {
    this.session = session;
    this.out = out;
    this.tagRenderer = tagRenderer;
    this.writerThread =
        Thread.ofVirtual()
            .name("session-writer-" + session.connectionId())
            .unstarted(this::drainLoop);
    this.writerThread.start();
  }

  public void setOnOverflow(Runnable onOverflow) {
    this.onOverflow = onOverflow;
  }

  /**
   * Enqueues a fan-out message; capability-dependent tags are rendered per-recipient at drain time.
   */
  public void enqueue(OutboundMessage message) {
    boolean offered = queue.offer(() -> writeFanOutMessage(message));
    if (!offered) {
      handleOverflow();
    }
  }

  /**
   * Enqueues a pre-built raw protocol line (numeric replies, echoes) — no tag decoration applied.
   */
  public void enqueueRaw(Message rawMessage) {
    boolean offered = queue.offer(() -> writeLine(MessageSerializer.serialize(rawMessage)));
    if (!offered) {
      handleOverflow();
    }
  }

  private void handleOverflow() {
    LOG.warn("Outbound queue overflow for connection {}; closing", session.connectionId());
    onOverflow.run();
  }

  private void writeFanOutMessage(OutboundMessage message) {
    Map<String, String> tags = tagRenderer.render(session, message);
    java.util.List<String> params =
        message.body() != null
            ? java.util.List.of(message.target(), message.body())
            : java.util.List.of(message.target());
    Message wireMessage =
        new Message(
            tags,
            message.senderPresentedForm(),
            Command.fromToken(message.command()),
            message.command(),
            params);
    writeLine(MessageSerializer.serialize(wireMessage));
  }

  private void writeLine(String line) {
    try {
      out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    } catch (IOException e) {
      LOG.debug("Write failed for connection {}: {}", session.connectionId(), e.toString());
      onOverflow.run();
    }
  }

  private void drainLoop() {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        Runnable task = queue.take();
        task.run();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    writerThread.interrupt();
  }
}
