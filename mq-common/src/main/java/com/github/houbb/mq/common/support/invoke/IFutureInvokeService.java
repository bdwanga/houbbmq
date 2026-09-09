package com.github.houbb.mq.common.support.invoke;

import com.github.houbb.mq.common.rpc.RpcMessageDto;

import java.util.concurrent.CompletableFuture;

public interface IFutureInvokeService {
    /**
     * 添加请求
     * @param seqId 序列号
     * @param timeoutMills 超时时间
     * @return 返回该请求的 CompletableFuture，用于异步回调
     */
    CompletableFuture<RpcMessageDto> addRequest(String seqId, long timeoutMills);

    /**
     * 放入响应结果（由 Netty 接收线程调用）
     * @param seqId 序列号
     * @param rpcResponse 响应结果
     * @return this
     */
    IFutureInvokeService addResponse(String seqId, RpcMessageDto rpcResponse);

    /**
     * 是否还有未完成的请求
     * @return boolean
     */
    boolean remainsRequest();
}
