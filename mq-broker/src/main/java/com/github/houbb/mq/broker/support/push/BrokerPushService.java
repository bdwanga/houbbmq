package com.github.houbb.mq.broker.support.push;

import com.alibaba.fastjson.JSON;
import com.github.houbb.heaven.util.util.DateUtil;
import com.github.houbb.id.core.util.IdHelper;
import com.github.houbb.log.integration.core.Log;
import com.github.houbb.log.integration.core.LogFactory;
import com.github.houbb.mq.broker.constant.BrokerRespCode;
import com.github.houbb.mq.broker.dto.ChannelGroupNameDto;
import com.github.houbb.mq.broker.dto.persist.MqMessagePersistPut;
import com.github.houbb.mq.broker.support.persist.IMqBrokerPersist;
import com.github.houbb.mq.common.constant.MessageStatusConst;
import com.github.houbb.mq.common.constant.MethodType;
import com.github.houbb.mq.common.dto.req.MqCommonReq;
import com.github.houbb.mq.common.dto.req.MqMessage;
import com.github.houbb.mq.common.dto.resp.MqCommonResp;
import com.github.houbb.mq.common.dto.resp.MqConsumerResultResp;
import com.github.houbb.mq.common.resp.ConsumerStatus;
import com.github.houbb.mq.common.resp.MqCommonRespCode;
import com.github.houbb.mq.common.resp.MqException;
import com.github.houbb.mq.common.rpc.RpcMessageDto;
import com.github.houbb.mq.common.support.executor.OrderedExecutor;
import com.github.houbb.mq.common.support.invoke.IFutureInvokeService;
import com.github.houbb.mq.common.support.invoke.IInvokeService;
import com.github.houbb.mq.common.util.ChannelUtil;
import com.github.houbb.mq.common.util.DelimiterUtil;
import com.github.houbb.mq.common.util.ThreadUtil;
import com.github.houbb.sisyphus.core.core.Retryer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * @author binbin.hou
 * @since 0.0.3
 */
public class BrokerPushService implements IBrokerPushService {
    private static final Log log = LogFactory.getLog(BrokerPushService.class);

    // 1. 任务派发线程池（只需少量线程，因为不会阻塞）
    private static final ExecutorService EXECUTOR_SERVICE = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(10000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    // 2. 信号量限流器 Map（针对每个消费者组进行限流，防止把接收端打爆）
    // key: consumerGroupName, value: Semaphore
    private static final Map<String, Semaphore> CONSUMER_LIMITER_MAP = new ConcurrentHashMap<>();

    // 每个消费者组允许的最大在途请求数（可根据配置读取）
    private static final int MAX_INFLIGHT_PER_CONSUMER = 50;

    //顺序执行调度器
    private final OrderedExecutor ORDERED_EXECUTOR = new OrderedExecutor();

    /**
     * 同步顺序推送
     * @param context 上下文
     * @since 0.0.3
     */
    @Override
    public void syncPush(final BrokerPushContext context) {
        log.info("开始同步处理 {}", JSON.toJSON(context));
        final MqMessagePersistPut persistPut = context.mqMessagePersistPut();
        final MqMessage mqMessage = persistPut.getMqMessage();
        final List<ChannelGroupNameDto> channelList = context.channelList();
        final IMqBrokerPersist mqBrokerPersist = context.mqBrokerPersist();
        final IInvokeService invokeService = context.invokeService();
        final long responseTime = context.respTimeoutMills();
        final int pushMaxAttempt = context.pushMaxAttempt();

        ORDERED_EXECUTOR.execute(mqMessage.getOrderMsgKey(), () -> {
                // 更新状态为处理中
                final String messageId = mqMessage.getTraceId();
                log.info("开始更新消息为处理中：{}", messageId);

                for(final ChannelGroupNameDto channelGroupNameDto : channelList) {
                    final Channel channel = channelGroupNameDto.getChannel();
                    final String consumerGroupName =channelGroupNameDto.getConsumerGroupName();

                    try {
                        mqBrokerPersist.updateStatus(messageId, consumerGroupName, MessageStatusConst.TO_CONSUMER_PROCESS);

                        String channelId = ChannelUtil.getChannelId(channel);

                        log.info("开始处理 channelId: {}", channelId);
                        //1. 调用
                        mqMessage.setMethodType(MethodType.B_MESSAGE_PUSH);

                        // 重试推送
                        MqConsumerResultResp resultResp = Retryer.<MqConsumerResultResp>newInstance()
                                .maxAttempt(pushMaxAttempt)
                                .callable(new Callable<MqConsumerResultResp>() {
                                    @Override
                                    public MqConsumerResultResp call() throws Exception {
                                        MqConsumerResultResp resp = callServer(channel, mqMessage,
                                                MqConsumerResultResp.class, invokeService, responseTime);

                                        // 失败校验
                                        if(resp == null
                                                || !ConsumerStatus.SUCCESS.getCode()
                                                .equals(resp.getConsumerStatus())) {
                                            throw new MqException(BrokerRespCode.MSG_PUSH_FAILED);
                                        }
                                        return resp;
                                    }
                                }).retryCall();

                        //2. 更新状态
                        //2.1 处理成功，取 push 消费状态
                        if(MqCommonRespCode.SUCCESS.getCode().equals(resultResp.getRespCode())) {
                            mqBrokerPersist.updateStatus(messageId, consumerGroupName, resultResp.getConsumerStatus());
                        } else {
                            // 2.2 处理失败
                            log.error("消费失败：{}", JSON.toJSON(resultResp));
                            mqBrokerPersist.updateStatus(messageId, consumerGroupName, MessageStatusConst.TO_CONSUMER_FAILED);
                        }
                        log.info("完成处理 channelId: {}", channelId);
                    } catch (Exception exception) {
                        log.error("处理异常", exception);
                        mqBrokerPersist.updateStatus(messageId, consumerGroupName, MessageStatusConst.TO_CONSUMER_FAILED);
                    }
                }

                log.info("完成同步处理");
        });
    }

    /**
     * 调用服务端
     * @param channel 调用通道
     * @param commonReq 通用请求
     * @param respClass 类
     * @param invokeService 调用管理类
     * @param respTimeoutMills 响应超时时间
     * @param <T> 泛型
     * @param <R> 结果
     * @return 结果
     * @since 1.0.0
     */
    private <T extends MqCommonReq, R extends MqCommonResp> R callServer(Channel channel,
                                                                         T commonReq,
                                                                         Class<R> respClass,
                                                                         IInvokeService invokeService,
                                                                         long respTimeoutMills) {
        final String traceId = commonReq.getTraceId();
        final long requestTime = System.currentTimeMillis();

        RpcMessageDto rpcMessageDto = new RpcMessageDto();
        rpcMessageDto.setTraceId(traceId);
        rpcMessageDto.setRequestTime(requestTime);
        rpcMessageDto.setJson(JSON.toJSONString(commonReq));
        rpcMessageDto.setMethodType(commonReq.getMethodType());
        rpcMessageDto.setRequest(true);

        // 添加调用服务
        invokeService.addRequest(traceId, respTimeoutMills);

        // 遍历 channel
        // 关闭当前线程，以获取对应的信息
        // 使用序列化的方式
        ByteBuf byteBuf = DelimiterUtil.getMessageDelimiterBuffer(rpcMessageDto);

        //负载均衡获取 channel
        channel.writeAndFlush(byteBuf);

        String channelId = ChannelUtil.getChannelId(channel);
        log.debug("[Client] channelId {} 发送消息 {}", channelId, JSON.toJSON(rpcMessageDto));
//        channel.closeFuture().syncUninterruptibly();

        if (respClass == null) {
            log.debug("[Client] 当前消息为 one-way 消息，忽略响应");
            return null;
        } else {
            //channelHandler 中获取对应的响应
            RpcMessageDto messageDto = invokeService.getResponse(traceId);
            if (MqCommonRespCode.TIMEOUT.getCode().equals(messageDto.getRespCode())) {
                throw new MqException(MqCommonRespCode.TIMEOUT);
            }

            String respJson = messageDto.getJson();
            return JSON.parseObject(respJson, respClass);
        }
    }

    @Override
    public void asyncPush(final BrokerPushContext context) {
        EXECUTOR_SERVICE.submit(() -> {
            log.info("开始异步处理 {}", JSON.toJSON(context));
            final MqMessagePersistPut persistPut = context.mqMessagePersistPut();
            final MqMessage mqMessage = persistPut.getMqMessage();
            final List<ChannelGroupNameDto> channelList = context.channelList();
            final IMqBrokerPersist mqBrokerPersist = context.mqBrokerPersist();
            final IFutureInvokeService invokeService = context.futureInvokeService();
            final long responseTime = context.respTimeoutMills();
            final int pushMaxAttempt = context.pushMaxAttempt();

            final String messageId = mqMessage.getTraceId();
            log.info("开始更新消息为处理中：{}", messageId);

            // 并发处理多个 Channel
            List<CompletableFuture<Void>> futures = channelList.stream()
                    .map(channelGroupNameDto -> CompletableFuture.runAsync(() -> {
                        processSingleChannel(channelGroupNameDto, mqMessage, mqBrokerPersist,
                                invokeService, responseTime, pushMaxAttempt, messageId);
                    }, EXECUTOR_SERVICE))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            log.info("完成异步处理 messageId: {}", messageId);
        });
    }

    /**
     * 处理单个 Channel：包含信号量获取、重试调用、结果回调
     */
    private void processSingleChannel(ChannelGroupNameDto channelGroupNameDto, MqMessage mqMessage,
                                      IMqBrokerPersist mqBrokerPersist, IFutureInvokeService invokeService,
                                      long responseTime, int pushMaxAttempt, String messageId) {
        final Channel channel = channelGroupNameDto.getChannel();
        final String consumerGroupName = channelGroupNameDto.getConsumerGroupName();
        String channelId = ChannelUtil.getChannelId(channel);

        // 1. 获取该消费者组的信号量
        Semaphore semaphore = CONSUMER_LIMITER_MAP.computeIfAbsent(consumerGroupName, k -> new Semaphore(MAX_INFLIGHT_PER_CONSUMER));

        try {
            // 尝试获取令牌，最多等待 200ms。如果超时说明该消费者处理不过来（背压）
            if (!semaphore.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                log.warn("消费者组 {} 并发达到上限 {}，快速失败，不再推送。", consumerGroupName, MAX_INFLIGHT_PER_CONSUMER);
                mqBrokerPersist.updateStatus(messageId, consumerGroupName, MessageStatusConst.TO_CONSUMER_FAILED);
                return;
            }

            // 获取到令牌，更新状态并开始推送
            mqBrokerPersist.updateStatus(messageId, consumerGroupName, MessageStatusConst.TO_CONSUMER_PROCESS);
            log.info("开始处理 channelId: {}", channelId);
            mqMessage.setMethodType(MethodType.B_MESSAGE_PUSH);

            // 2. 调用带异步重试的推送方法
            CompletableFuture<MqConsumerResultResp> resultFuture = retryPushAsync(
                    channel, mqMessage, invokeService, responseTime, pushMaxAttempt, 1);

            // 3. 绑定最终回调：无论成功失败，这里被触发
            resultFuture.whenComplete((resultResp, ex) -> {
                try {
                    if (ex != null) {
                        log.error("处理异常 channelId: {}", channelId, ex);
                        mqBrokerPersist.updateStatus(messageId, consumerGroupName, MessageStatusConst.TO_CONSUMER_FAILED);
                    } else if (resultResp == null || !MqCommonRespCode.SUCCESS.getCode().equals(resultResp.getRespCode())) {
                        log.error("消费失败：{}", JSON.toJSON(resultResp));
                        mqBrokerPersist.updateStatus(messageId, consumerGroupName, MessageStatusConst.TO_CONSUMER_FAILED);
                    } else {
                        // 最终成功
                        mqBrokerPersist.updateStatus(messageId, consumerGroupName, resultResp.getConsumerStatus());
                    }
                    log.info("完成处理 channelId: {}", channelId);
                } finally {
                    // 【关键】无论成功失败，必须释放信号量，让后续消息可以继续推送
                    semaphore.release();
                }
            });

        } catch (Exception exception) {
            log.error("提交推送任务异常 channelId: {}", channelId, exception);
            mqBrokerPersist.updateStatus(messageId, consumerGroupName, MessageStatusConst.TO_CONSUMER_FAILED);
            // 发生异常时也要释放信号量
            semaphore.release();
        }
    }

    /**
     * 异步重试机制 (递归调用)
     */
    private CompletableFuture<MqConsumerResultResp> retryPushAsync(Channel channel,
                                                                   MqMessage mqMessage,
                                                                   IFutureInvokeService invokeService,
                                                                   long responseTime,
                                                                   int maxAttempt,
                                                                   int currentAttempt) {
        // 1. 发起异步调用
        CompletableFuture<MqConsumerResultResp> future = callServerAsync(channel, mqMessage,
                MqConsumerResultResp.class, invokeService, responseTime);

        // 2. 处理重试逻辑
        return future.handle((resp, ex) -> {
            boolean needRetry = (ex != null) || (resp == null) ||
                    !MqCommonRespCode.SUCCESS.getCode().equals(resp.getRespCode()) ||
                    !ConsumerStatus.SUCCESS.getCode().equals(resp.getConsumerStatus());

            if (needRetry) {
                if (currentAttempt < maxAttempt) {
                    // 每次重试前，必须更新 traceId/seqId
//                    String newTraceId = IdHelper.uuid32();
                    int nextAttempt = currentAttempt + 1;
                    mqMessage.setTraceId(mqMessage.getTraceId() + "-" + nextAttempt);
                    log.warn("推送失败，准备进行第 {} 次重试。traceId: {}", nextAttempt, mqMessage.getTraceId());
                    // 递归调用进行重试
                    return retryPushAsync(channel, mqMessage, invokeService, responseTime, maxAttempt, nextAttempt);
                } else {
                    log.error("推送失败且已达最大重试次数 {}。traceId: {}", maxAttempt, mqMessage.getTraceId());
                    MqConsumerResultResp failResp = new MqConsumerResultResp();
                    failResp.setRespCode(MqCommonRespCode.FAIL.getCode());
                    return CompletableFuture.completedFuture(failResp);
                }
            } else {
                return CompletableFuture.completedFuture(resp);
            }
        }).thenCompose(f -> f);
    }

    /**
     * 异步非阻塞网络调用
     */
    private <T extends MqCommonReq, R extends MqCommonResp> CompletableFuture<R> callServerAsync(
            Channel channel, T commonReq, Class<R> respClass,
            IFutureInvokeService invokeService, long respTimeoutMills) {

        final String traceId = commonReq.getTraceId();
        final long requestTime = System.currentTimeMillis();

        RpcMessageDto rpcMessageDto = new RpcMessageDto();
        rpcMessageDto.setTraceId(traceId);
        rpcMessageDto.setRequestTime(requestTime);
        rpcMessageDto.setJson(JSON.toJSONString(commonReq));
        rpcMessageDto.setMethodType(commonReq.getMethodType());
        rpcMessageDto.setRequest(true);

        // 1. 注册请求，获取 Future
        CompletableFuture<RpcMessageDto> rpcFuture = invokeService.addRequest(traceId, respTimeoutMills);

        // 2. 发送消息（非阻塞）
        ByteBuf byteBuf = DelimiterUtil.getMessageDelimiterBuffer(rpcMessageDto);
        channel.writeAndFlush(byteBuf);

        String channelId = ChannelUtil.getChannelId(channel);
        log.debug("[Client] channelId {} 发送消息 {}", channelId, JSON.toJSON(rpcMessageDto));

        // 3. 处理 One-way 消息
        if (respClass == null) {
            rpcFuture.complete(null);
            return CompletableFuture.completedFuture(null);
        }

        // 4. 转换 Future 的类型
        return rpcFuture.thenApply(rpcMessageDto1 -> {
            if (rpcMessageDto1 == null) {
                return null;
            }
            if (MqCommonRespCode.TIMEOUT.getCode().equals(rpcMessageDto1.getRespCode())) {
                throw new MqException(MqCommonRespCode.TIMEOUT);
            }
            String respJson = rpcMessageDto1.getJson();
            return JSON.parseObject(respJson, respClass);
        });
    }

    @Override
    public void destroyAll() {
        log.info("开始关闭broker线程池等");
        //等待5s，确保注销任务完成
        DateUtil.sleep(5000);
        ThreadUtil.shutdownExecutor(EXECUTOR_SERVICE);
        ORDERED_EXECUTOR.shutdown();
    }
}
