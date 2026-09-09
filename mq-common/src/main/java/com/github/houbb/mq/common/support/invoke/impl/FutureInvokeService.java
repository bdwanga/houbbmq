package com.github.houbb.mq.common.support.invoke.impl;

import com.github.houbb.mq.common.support.invoke.IFutureInvokeService;

import com.alibaba.fastjson.JSON;
import com.github.houbb.heaven.util.lang.ObjectUtil;
import com.github.houbb.log.integration.core.Log;
import com.github.houbb.log.integration.core.LogFactory;
import com.github.houbb.mq.common.rpc.RpcMessageDto;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FutureInvokeService implements IFutureInvokeService {
    private static final Log logger = LogFactory.getLog(InvokeService.class);

    private final ConcurrentHashMap<String, Long> requestMap;
    private final ConcurrentHashMap<String, CompletableFuture<RpcMessageDto>> futureMap;
    private final ScheduledExecutorService timeoutScheduler;

    public FutureInvokeService() {
        requestMap = new ConcurrentHashMap<>();
        futureMap = new ConcurrentHashMap<>();
        timeoutScheduler = Executors.newScheduledThreadPool(1);
    }

    @Override
    public CompletableFuture<RpcMessageDto> addRequest(String seqId, long timeoutMills) {
        logger.debug("[Invoke] start add request for seqId: {}, timeoutMills: {}", seqId, timeoutMills);

        final long expireTime = System.currentTimeMillis() + timeoutMills;
        requestMap.put(seqId, expireTime);

        CompletableFuture<RpcMessageDto> future = new CompletableFuture<>();
        futureMap.put(seqId, future);

        // 超时自动完成机制
        timeoutScheduler.schedule(() -> {
            if (!future.isDone()) {
                logger.warn("[Timeout] seq {} 等待响应超时({}ms)", seqId, timeoutMills);
                future.complete(RpcMessageDto.timeout());
                requestMap.remove(seqId);
                futureMap.remove(seqId);
            }
        }, timeoutMills, TimeUnit.MILLISECONDS);

        return future;
    }

    @Override
    public IFutureInvokeService addResponse(String seqId, RpcMessageDto rpcResponse) {
        Long expireTime = this.requestMap.get(seqId);
        if (ObjectUtil.isNull(expireTime)) {
            logger.debug("[Invoke] seqId:{} 信息已超时或不存在，忽略响应。", seqId);
            return this;
        }

        CompletableFuture<RpcMessageDto> future = this.futureMap.get(seqId);
        if (future != null && !future.isDone()) {
            boolean completeSuccess = future.complete(rpcResponse);
            if (completeSuccess) {
                logger.debug("[Invoke] 获取结果信息，seqId: {}, rpcResponse: {}", seqId, JSON.toJSON(rpcResponse));
            }
        }

        requestMap.remove(seqId);
        futureMap.remove(seqId);
        return this;
    }

    @Override
    public boolean remainsRequest() {
        return this.requestMap.size() > 0;
    }
}
