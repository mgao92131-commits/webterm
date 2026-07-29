package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class HistoryDemandMailboxTest {
  @Test
  public void oneThousandUpdatesScheduleOneDrainAndKeepLatest() {
    ArrayDeque<Runnable> executor = new ArrayDeque<>();
    List<HistoryDemandMailbox.Update> applied = new ArrayList<>();
    HistoryDemandMailbox mailbox = new HistoryDemandMailbox(executor::add, applied::add);

    for (int i = 1; i <= 1_000; i++) {
      mailbox.offer(i, i + 10, i, 1, 11, i);
    }

    assertEquals(1, executor.size());
    executor.remove().run();
    assertEquals(1, applied.size());
    assertEquals(1_000, applied.get(0).visibleFromSeq);
  }

  @Test
  public void updateArrivingDuringDrainIsAppliedWithoutLoss() {
    ArrayDeque<Runnable> executor = new ArrayDeque<>();
    List<Long> applied = new ArrayList<>();
    final HistoryDemandMailbox[] holder = new HistoryDemandMailbox[1];
    holder[0] = new HistoryDemandMailbox(executor::add, update -> {
      applied.add(update.visibleFromSeq);
      if (update.visibleFromSeq == 1) {
        holder[0].offer(2, 2, 2, 1, 1, 2);
      }
    });
    holder[0].offer(1, 1, 1, 1, 1, 1);

    executor.remove().run();

    assertEquals(java.util.Arrays.asList(1L, 2L), applied);
    assertEquals(0, executor.size());
  }

  @Test
  public void invalidateDropsPendingAndFutureDemandCanScheduleAgain() {
    ArrayDeque<Runnable> executor = new ArrayDeque<>();
    List<Long> applied = new ArrayList<>();
    HistoryDemandMailbox mailbox =
        new HistoryDemandMailbox(executor::add, update -> applied.add(update.visibleFromSeq));
    mailbox.offer(1, 1, 1, 0, 1, 1);
    mailbox.invalidatePending();

    executor.remove().run();
    assertEquals(0, applied.size());

    mailbox.offer(2, 2, 2, 0, 1, 2);
    executor.remove().run();
    assertEquals(java.util.Collections.singletonList(2L), applied);
  }

  @Test
  public void detachClearReplacesPendingDemand() {
    ArrayDeque<Runnable> executor = new ArrayDeque<>();
    List<HistoryDemandMailbox.Update> applied = new ArrayList<>();
    HistoryDemandMailbox mailbox = new HistoryDemandMailbox(executor::add, applied::add);
    mailbox.offer(10, 20, 10, 1, 11, 1);
    mailbox.offerClear(2);

    assertEquals(1, executor.size());
    executor.remove().run();
    assertEquals(1, applied.size());
    assertEquals(true, applied.get(0).clear);
  }

  @Test
  public void closeRacingQueuedDrainCannotRestoreScheduling() {
    ArrayDeque<Runnable> executor = new ArrayDeque<>();
    List<Long> applied = new ArrayList<>();
    HistoryDemandMailbox mailbox =
        new HistoryDemandMailbox(executor::add, update -> applied.add(update.visibleFromSeq));
    mailbox.offer(1, 1, 1, 0, 1, 1);
    mailbox.close();

    executor.remove().run();
    assertEquals(0, applied.size());
    assertEquals(HistoryDemandMailbox.OfferResult.REJECTED,
        mailbox.offer(2, 2, 2, 0, 1, 2));
    assertNull(mailbox.pendingForTest());
  }
}
