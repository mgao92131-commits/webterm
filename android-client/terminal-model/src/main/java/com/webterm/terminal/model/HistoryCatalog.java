package com.webterm.terminal.model;

/** WS 权威历史目录：决定存在性与封存水位，不含正文。 */
public final class HistoryCatalog {
  public final long generation;
  public final long trimBeforeSeq;
  public final long sealedThroughSeq;
  public final long tailLastSeq;

  public static final HistoryCatalog EMPTY = new HistoryCatalog(0, 1, 0, 0);

  public HistoryCatalog(long generation, long trimBeforeSeq,
                        long sealedThroughSeq, long tailLastSeq) {
    this.generation = generation;
    this.trimBeforeSeq = trimBeforeSeq;
    this.sealedThroughSeq = sealedThroughSeq;
    this.tailLastSeq = tailLastSeq;
  }

  /** 该段是否与当前 Catalog 有效范围相交且已封存。 */
  public boolean acceptsSegment(long generation, long firstSeq, long lastSeq) {
    if (generation == 0 || generation != this.generation) return false;
    if (sealedThroughSeq == 0 || lastSeq > sealedThroughSeq) return false;
    if (lastSeq < trimBeforeSeq) return false;
    return true;
  }
}
