package com.samourai.whirlpool.client.wallet.beans;

import com.samourai.whirlpool.client.whirlpool.beans.Pool;

public class WhirlpoolUtxoConfig {
  private static final int PRIORITY_DEFAULT = 5;

  private Pool pool;
  private int mixsTarget;
  private int priority;
  private int mixsDone;

  public WhirlpoolUtxoConfig(int mixsTarget) {
    this(null, mixsTarget, PRIORITY_DEFAULT);
  }

  public WhirlpoolUtxoConfig(Pool pool, int mixsTarget, int priority) {
    this.pool = pool;
    this.mixsTarget = mixsTarget;
    this.priority = priority;
    this.mixsDone = 0;
  }

  public void set(WhirlpoolUtxoConfig copy) {
    this.pool = copy.pool;
    this.mixsTarget = copy.mixsTarget;
    this.priority = copy.priority;
    this.mixsDone = copy.mixsDone;
  }

  public WhirlpoolUtxoConfig copy() {
    WhirlpoolUtxoConfig copy = new WhirlpoolUtxoConfig(this.mixsTarget);
    copy.set(this);
    return copy;
  }

  public Pool getPool() {
    return pool;
  }

  public void setPool(Pool pool) {
    this.pool = pool;
  }

  public int getMixsTarget() {
    return mixsTarget;
  }

  public void setMixsTarget(int mixsTarget) {
    this.mixsTarget = mixsTarget;
  }

  public int getPriority() {
    return priority;
  }

  public void setPriority(int priority) {
    this.priority = priority;
  }

  public int getMixsDone() {
    return mixsDone;
  }

  public void incrementMixsDone() {
    this.mixsDone++;
  }

  @Override
  public String toString() {
    return "poolId="
        + (pool != null ? pool.getPoolId() : "null")
        + ", mixsTarget="
        + mixsTarget
        + ", priority="
        + priority;
  }
}