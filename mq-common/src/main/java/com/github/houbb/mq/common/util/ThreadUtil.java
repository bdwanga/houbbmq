package com.github.houbb.mq.common.util;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class ThreadUtil {
    /**
     * 优雅关闭线程池
     */
    public static void shutdownExecutor(ExecutorService executorService) {
        // 1. 停止接收新任务
        executorService.shutdown();

        try {
            // 2. 等待 60 秒，让已提交的任务（包括队列中的）执行完毕
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                System.out.println("线程池未在 60 秒内关闭，准备强制关闭");

                // 3. 超时后强制关闭（尝试中断正在执行的任务，并返回未执行的任务列表）
                List<Runnable> droppedTasks = executorService.shutdownNow();

                // 4. 再次等待 10 秒，确保被中断的任务能够响应中断信号
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    System.err.println("线程池未能完全关闭，可能有任务拒绝响应中断");
                }

                if (!droppedTasks.isEmpty()) {
                    System.err.println("强制关闭时，丢弃了 " + droppedTasks.size() + " 个未执行的任务");
                    // 实际生产中，这里可以把 droppedTasks 持久化到数据库或死信队列，后续补偿
                }
            }
        } catch (InterruptedException e) {
            // 当前等待线程被中断，立即强制关闭
            executorService.shutdownNow();
            // 恢复中断状态
            Thread.currentThread().interrupt();
        }
    }
}
