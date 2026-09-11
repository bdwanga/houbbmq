package com.github.houbb.mq.common.support.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于哈希路由的顺序执行器
 * 保证相同 key 的任务在同一个线程中串行执行
 */
public class OrderedExecutor {

    // 默认分区数（建议设置为 2 的幂次方，如 16, 32，分散更均匀）
    private static final int DEFAULT_PARTITIONS = 16;

    private final List<ExecutorService> executors;
    private final int partitions;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public OrderedExecutor() {
        this(DEFAULT_PARTITIONS);
    }

    public OrderedExecutor(int partitions) {
        this.partitions = partitions;
        this.executors = new ArrayList<>(partitions);
        // 初始化 partitions 个单线程执行器
        for (int i = 0; i < partitions; i++) {
            executors.add(Executors.newSingleThreadExecutor());
        }
    }

    /**
     * 提交任务
     * @param sequenceFlag 业务顺序标识 (如 订单ID、用户ID)
     * @param task 要执行的任务 
     */
    public void execute(String sequenceFlag, Runnable task) {
        if (!running.get()) {
            throw new IllegalStateException("OrderedExecutor has been shutdown");
        }

        if (sequenceFlag == null) {
            // 如果没有标识，直接随机找一个线程执行（或者抛出异常，视业务而定）
            int index = (int) (System.currentTimeMillis() % partitions);
            executors.get(index).execute(task);
            return;
        }

        // 核心路由逻辑：计算 hash 并取模
        // 使用 0x7FFFFFFF 确保永远是正数，防止负数取模导致数组越界
        int hash = sequenceFlag.hashCode() & 0x7FFFFFFF;
        int index = hash % partitions;

        executors.get(index).execute(task);
    }

    /**
     * 优雅关闭
     */
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            for (ExecutorService executor : executors) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}