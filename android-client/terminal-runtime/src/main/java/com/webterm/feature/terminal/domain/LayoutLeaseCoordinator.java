package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** 页面 attach/detach、layout lease、续期和最新 resize 的唯一状态所有者。 */
public final class LayoutLeaseCoordinator {
  public interface Environment {
    boolean isTerminalConnected();
    @Nullable TerminalSessionRuntime.ScreenConnection connection();
    void onInputReadyChanged(boolean ready);
  }

  private enum State { DETACHED, ACQUIRING, HELD }

  private final TerminalSessionRuntime.TimeoutScheduler scheduler;
  private final Executor executor;
  private final Environment environment;
  private final long[] retryBackoffMs;
  private final long requestTimeoutMs;
  private final long fallbackRenewMs;
  private final long minRenewDelayMs;
  private final AtomicLong generation = new AtomicLong(1L);
  private final AtomicLong nextRequestId = new AtomicLong();
  private volatile State state = State.DETACHED;
  private volatile boolean pageAttached = true;
  private volatile String leaseId = "";
  private volatile String pendingRequestId = "";
  private long expiresAtMs;
  private int retryAttempt;
  private volatile int latestCols;
  private volatile int latestRows;

  public LayoutLeaseCoordinator(TerminalSessionRuntime.TimeoutScheduler scheduler,
                                Executor executor,
                                Environment environment,
                                long[] retryBackoffMs,
                                long requestTimeoutMs,
                                long fallbackRenewMs,
                                long minRenewDelayMs) {
    this.scheduler = scheduler;
    this.executor = executor;
    this.environment = environment;
    this.retryBackoffMs = retryBackoffMs.clone();
    this.requestTimeoutMs = requestTimeoutMs;
    this.fallbackRenewMs = fallbackRenewMs;
    this.minRenewDelayMs = minRenewDelayMs;
  }

  public void attachPage() {
    boolean wasAttached = pageAttached;
    pageAttached = true;
    long token = wasAttached ? generation.get() : generation.incrementAndGet();
    executor.execute(() -> ensureLease(token, false));
  }

  public void detachPage() {
    if (!pageAttached) return;
    pageAttached = false;
    TerminalSessionRuntime.ScreenConnection connection = environment.connection();
    if (connection != null) connection.releaseLayout();
    invalidate();
  }

  public void onSynchronizationComplete() {
    ensureLease(generation.get(), false);
  }

  public void requestResize(int cols, int rows) {
    if (cols <= 0 || rows <= 0) return;
    latestCols = cols;
    latestRows = rows;
    TerminalSessionRuntime.ScreenConnection connection = environment.connection();
    if (environment.isTerminalConnected() && hasLease() && connection != null) {
      connection.requestResize(cols, rows);
    }
  }

  public void handle(@NonNull TerminalScreenProto.LayoutLease lease) {
    if (!environment.isTerminalConnected() || !pageAttached) return;
    String responseRequestId = lease.getRequestId();
    boolean unsolicitedRevocation = !lease.getGranted() && responseRequestId.isEmpty();
    if (!unsolicitedRevocation) {
      if (pendingRequestId.isEmpty()) return;
      if (!responseRequestId.isEmpty() && !responseRequestId.equals(pendingRequestId)) {
        TerminalResumeMetrics.leaseStaleResponse();
        return;
      }
    }
    pendingRequestId = "";
    if (lease.getGranted()) {
      if (lease.getLeaseId().isEmpty()) {
        clearState();
        updateConnectionLease();
        notifyReady();
        scheduleRetry(generation.get());
        return;
      }
      leaseId = lease.getLeaseId();
      state = State.HELD;
      expiresAtMs = lease.getExpiresAtMs();
      retryAttempt = 0;
    } else {
      if (unsolicitedRevocation) TerminalResumeMetrics.leaseRevoked();
      else TerminalResumeMetrics.leaseDenied();
      clearState();
    }
    TerminalSessionRuntime.ScreenConnection connection = environment.connection();
    if (connection != null) {
      connection.setLayoutLeaseId(leaseId);
      if (hasLease() && latestCols > 0 && latestRows > 0) {
        connection.requestResize(latestCols, latestRows);
      }
    }
    long token = generation.get();
    if (hasLease()) scheduleRenewal(token, leaseId, expiresAtMs);
    else scheduleRetry(token);
    notifyReady();
  }

  public void invalidate() {
    generation.incrementAndGet();
    pendingRequestId = "";
    retryAttempt = 0;
    clearState();
    updateConnectionLease();
    notifyReady();
  }

  public boolean hasLease() {
    return state == State.HELD;
  }

  public boolean isPageAttached() {
    return pageAttached;
  }

  @NonNull
  public String leaseId() {
    return leaseId;
  }

  private void ensureLease(long token, boolean renewal) {
    if (token != generation.get() || !pageAttached
        || !environment.isTerminalConnected() || environment.connection() == null) return;
    if (!pendingRequestId.isEmpty()) return;
    if (!renewal && hasLease()) return;
    String requestId = "layout-" + token + "-" + nextRequestId.incrementAndGet();
    pendingRequestId = requestId;
    if (!hasLease()) {
      state = State.ACQUIRING;
      notifyReady();
    }
    TerminalResumeMetrics.leaseAcquire(renewal);
    environment.connection().acquireLayout(requestId, true);
    scheduler.schedule(() -> executor.execute(() -> onRequestTimeout(token, requestId)),
        requestTimeoutMs);
  }

  private void onRequestTimeout(long token, String requestId) {
    if (token != generation.get() || !requestId.equals(pendingRequestId)) return;
    pendingRequestId = "";
    if (hasLease()) {
      TerminalResumeMetrics.leaseRetry();
      scheduler.schedule(() -> executor.execute(() -> ensureLease(token, true)),
          minRenewDelayMs);
    } else {
      scheduleRetry(token);
    }
  }

  private void scheduleRetry(long token) {
    if (token != generation.get() || !pageAttached
        || !environment.isTerminalConnected() || hasLease()) return;
    int index = Math.min(retryAttempt, retryBackoffMs.length - 1);
    long delayMs = retryBackoffMs[index];
    retryAttempt++;
    TerminalResumeMetrics.leaseRetry();
    scheduler.schedule(() -> executor.execute(() -> ensureLease(token, false)), delayMs);
  }

  private void scheduleRenewal(long token, String expectedLeaseId, long expectedExpiresAtMs) {
    long nowMs = System.currentTimeMillis();
    long delayMs = fallbackRenewMs;
    if (expectedExpiresAtMs > 0L) {
      long remainingMs = expectedExpiresAtMs - nowMs;
      if (remainingMs <= 0L) {
        clearState();
        scheduleRetry(token);
        return;
      }
      long halfTtl = remainingMs / 2L;
      long beforeExpiry = Math.max(minRenewDelayMs, remainingMs - 60_000L);
      delayMs = Math.max(minRenewDelayMs, Math.min(halfTtl, beforeExpiry));
    }
    scheduler.schedule(() -> executor.execute(() -> {
      if (token != generation.get() || !pageAttached || !hasLease()
          || !expectedLeaseId.equals(leaseId)) return;
      ensureLease(token, true);
    }), delayMs);
    if (expectedExpiresAtMs > 0L) {
      long expiryDelay = Math.max(minRenewDelayMs, expectedExpiresAtMs - nowMs);
      scheduler.schedule(() -> executor.execute(() -> {
        if (token != generation.get() || expectedExpiresAtMs != expiresAtMs
            || !expectedLeaseId.equals(leaseId)
            || System.currentTimeMillis() < expectedExpiresAtMs) return;
        clearState();
        updateConnectionLease();
        notifyReady();
        scheduleRetry(token);
      }), expiryDelay);
    }
  }

  private void clearState() {
    leaseId = "";
    expiresAtMs = 0L;
    state = State.DETACHED;
  }

  private void updateConnectionLease() {
    TerminalSessionRuntime.ScreenConnection connection = environment.connection();
    if (connection != null) connection.setLayoutLeaseId(leaseId);
  }

  private void notifyReady() {
    environment.onInputReadyChanged(hasLease());
  }
}
