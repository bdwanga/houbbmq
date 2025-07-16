package com.github.houbb.mq.consumer.support.broker;

import com.alibaba.fastjson.JSON;
import com.github.houbb.heaven.util.net.NetUtil;
import com.github.houbb.heaven.util.util.DateUtil;
import com.github.houbb.id.core.util.IdHelper;
import com.github.houbb.load.balance.api.ILoadBalance;
import com.github.houbb.log.integration.core.Log;
import com.github.houbb.log.integration.core.LogFactory;
import com.github.houbb.mq.broker.dto.BrokerRegisterReq;
import com.github.houbb.mq.broker.dto.ServiceEntry;
import com.github.houbb.mq.broker.dto.consumer.ConsumerSubscribeReq;
import com.github.houbb.mq.broker.dto.consumer.ConsumerUnSubscribeReq;
import com.github.houbb.mq.broker.utils.InnerChannelUtils;
import com.github.houbb.mq.common.constant.MethodType;
import com.github.houbb.mq.common.dto.req.*;
import com.github.houbb.mq.common.dto.req.component.MqConsumerUpdateStatusDto;
import com.github.houbb.mq.common.dto.resp.MqCommonResp;
import com.github.houbb.mq.common.dto.resp.MqConsumerPullResp;
import com.github.houbb.mq.common.resp.ConsumerStatus;
import com.github.houbb.mq.common.resp.MqCommonRespCode;
import com.github.houbb.mq.common.resp.MqException;
import com.github.houbb.mq.common.rpc.RpcChannelFuture;
import com.github.houbb.mq.common.rpc.RpcMessageDto;
import com.github.houbb.mq.common.support.invoke.IInvokeService;
import com.github.houbb.mq.common.support.status.IStatusManager;
import com.github.houbb.mq.common.util.ChannelFutureUtils;
import com.github.houbb.mq.common.util.ChannelUtil;
import com.github.houbb.mq.common.util.DelimiterUtil;
import com.github.houbb.mq.common.util.RandomUtils;
import com.github.houbb.mq.consumer.constant.ConsumerRespCode;
import com.github.houbb.mq.consumer.handler.MqConsumerHandler;
import com.github.houbb.mq.consumer.support.listener.IMqListenerService;
import com.github.houbb.sisyphus.core.core.Retryer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;

import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author binbin.hou
 * @since 0.0.5
 */
public class ConsumerBrokerService implements IConsumerBrokerService {

    private static final Log log = LogFactory.getLog(ConsumerBrokerService.class);

    /**
     * 分组名称
     */
    private String groupName;

    /**
     * 中间人地址
     */
    private String brokerAddress;

    /**
     * 调用管理服务
     * @since 0.0.2
     */
    private IInvokeService invokeService;

    /**
     * 获取响应超时时间
     * @since 0.0.2
     */
    private long respTimeoutMills;

    /**
     * 请求列表
     * @since 0.0.3
     */
    private List<RpcChannelFuture> channelFutureList;

    /**
     * 检测 broker 可用性
     * @since 0.0.4
     */
    private boolean check;

    /**
     * 状态管理
     * @since 0.0.5
     */
    private IStatusManager statusManager;

    /**
     * 监听服务类
     * @since 0.0.5
     */
    private IMqListenerService mqListenerService;

    private ConsumerBrokerConfig config;

    /**
     * 心跳定时任务
     *
     * @since 0.0.6
     */
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

    /**
     * 负载均衡策略
     * @since 0.0.7
     */
    private ILoadBalance<RpcChannelFuture> loadBalance;

    /**
     * 订阅最大尝试次数
     * @since 0.0.8
     */
    private int subscribeMaxAttempt;

    /**
     * 取消订阅最大尝试次数
     * @since 0.0.8
     */
    private int unSubscribeMaxAttempt;

    /**
     * 消费状态更新最大尝试次数
     * @since 0.1.0
     */
    private int consumerStatusMaxAttempt;

    /**
     * 账户标识
     * @since 0.1.4
     */
    protected String appKey;

    /**
     * 账户密码
     * @since 0.1.4
     */
    protected String appSecret;

    private boolean isReconnect = false;

    private Set<ConsumerSubscribeReq> subscribeReqSet = new CopyOnWriteArraySet<>();

    @Override
    public void initChannelFutureList(ConsumerBrokerConfig config) {
        //1. 配置初始化
        this.invokeService = config.invokeService();
        this.check = config.check();
        this.respTimeoutMills = config.respTimeoutMills();
        this.brokerAddress = config.brokerAddress();
        this.groupName = config.groupName();
        this.statusManager = config.statusManager();
        this.mqListenerService = config.mqListenerService();
        this.loadBalance = config.loadBalance();
        this.subscribeMaxAttempt = config.subscribeMaxAttempt();
        this.unSubscribeMaxAttempt = config.unSubscribeMaxAttempt();
        this.consumerStatusMaxAttempt = config.consumerStatusMaxAttempt();
        this.appKey = config.appKey();
        this.appSecret = config.appSecret();

        //2. 初始化
        this.channelFutureList = ChannelFutureUtils.initChannelFutureList(brokerAddress,
                initChannelHandler(), check);

        //3. 初始化心跳
        this.initHeartbeat();
    }

    /**
     * 初始化心跳
     * @since 0.0.6
     */
    private void initHeartbeat() {
        //5S 发一次心跳
        scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                heartbeat();
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private ChannelHandler initChannelHandler() {
        final ByteBuf delimiterBuf = DelimiterUtil.getByteBuf(DelimiterUtil.DELIMITER);

        final MqConsumerHandler mqProducerHandler = new MqConsumerHandler();
        mqProducerHandler.setInvokeService(invokeService);
        mqProducerHandler.setMqListenerService(mqListenerService);

        // handler 实际上会被多次调用，如果不是 @Shareable，应该每次都重新创建。
        ChannelHandler handler = new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()
                        .addLast(new DelimiterBasedFrameDecoder(DelimiterUtil.LENGTH, delimiterBuf))
                        .addLast(mqProducerHandler);
            }
        };

        return handler;
    }

    @Override
    public void registerToBroker() {
        int successCount = 0;

        for(RpcChannelFuture channelFuture : this.channelFutureList) {
            ServiceEntry serviceEntry = new ServiceEntry();
            serviceEntry.setGroupName(groupName);
            serviceEntry.setAddress(channelFuture.getAddress());
            serviceEntry.setPort(channelFuture.getPort());
            serviceEntry.setWeight(channelFuture.getWeight());

            BrokerRegisterReq brokerRegisterReq = new BrokerRegisterReq();
            brokerRegisterReq.setServiceEntry(serviceEntry);
            brokerRegisterReq.setMethodType(MethodType.C_REGISTER);
            brokerRegisterReq.setTraceId(IdHelper.uuid32());
            brokerRegisterReq.setAppKey(appKey);
            brokerRegisterReq.setAppSecret(appSecret);

            log.info("[Register] 开始注册到 broker：{}", JSON.toJSON(brokerRegisterReq));
            final Channel channel = channelFuture.getChannelFuture().channel();
            MqCommonResp resp = callServer(channel, brokerRegisterReq, MqCommonResp.class);
            log.info("[Register] 完成注册到 broker：{}", JSON.toJSON(resp));

            if(MqCommonRespCode.SUCCESS.getCode().equals(resp.getRespCode())) {
                successCount++;
            }
        }

        if(successCount <= 0 && check) {
            log.error("校验 broker 可用性，可连接成功数为 0");
            throw new MqException(MqCommonRespCode.C_REGISTER_TO_BROKER_FAILED);
        }
    }

    @Override
    public <T extends MqCommonReq, R extends MqCommonResp> R callServer(Channel channel, T commonReq, Class<R> respClass) {
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
    public Channel getChannel(String key) {
        // 等待启动完成
        while (!statusManager.status()) {
            if(statusManager.initFailed()) {
                log.error("初始化失败");
                throw new MqException(MqCommonRespCode.C_INIT_FAILED);
            }

            log.debug("等待初始化完成...");
            DateUtil.sleep(100);
        }

        RpcChannelFuture rpcChannelFuture = RandomUtils.loadBalance(loadBalance,
                channelFutureList, key);
        return rpcChannelFuture.getChannelFuture().channel();
    }

    /**
     * 重新订阅
     */
    @Override
    public void reSubscribe() {
        for(ConsumerSubscribeReq req : subscribeReqSet) {
            this.subscribe(req.getTopicName(), req.getTagRegex(), req.getConsumerType());
        }
    }

    @Override
    public void subscribe(String topicName, String tagRegex, String consumerType) {
        final ConsumerSubscribeReq req = new ConsumerSubscribeReq();

        String messageId = IdHelper.uuid32();
        req.setTraceId(messageId);
        req.setMethodType(MethodType.C_SUBSCRIBE);
        req.setTopicName(topicName);
        req.setTagRegex(tagRegex);
        req.setGroupName(groupName);
        req.setConsumerType(consumerType);
        ConsumerBrokerService that = this;

        // 重试订阅
        Retryer.<String>newInstance()
                .maxAttempt(subscribeMaxAttempt)
                .callable(new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        Channel channel = getChannel(null);
                        MqCommonResp resp = callServer(channel, req, MqCommonResp.class);
                        if(!MqCommonRespCode.SUCCESS.getCode().equals(resp.getRespCode())) {
                            throw new MqException(ConsumerRespCode.SUBSCRIBE_FAILED);
                        }
                        that.subscribeReqSet.add(req);
                        return resp.getRespCode();
                    }
                }).retryCall();
    }

    @Override
    public void unSubscribe(String topicName, String tagRegex, String consumerType) {
        final ConsumerUnSubscribeReq req = new ConsumerUnSubscribeReq();

        String messageId = IdHelper.uuid32();
        req.setTraceId(messageId);
        req.setMethodType(MethodType.C_UN_SUBSCRIBE);
        req.setTopicName(topicName);
        req.setTagRegex(tagRegex);
        req.setGroupName(groupName);
        req.setConsumerType(consumerType);
        ConsumerBrokerService that = this;
        // 重试取消订阅
        Retryer.<String>newInstance()
                .maxAttempt(unSubscribeMaxAttempt)
                .callable(new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        Channel channel = getChannel(null);
                        MqCommonResp resp = callServer(channel, req, MqCommonResp.class);
                        if(!MqCommonRespCode.SUCCESS.getCode().equals(resp.getRespCode())) {
                            throw new MqException(ConsumerRespCode.UN_SUBSCRIBE_FAILED);
                        }
                        final ConsumerSubscribeReq subreq = new ConsumerSubscribeReq();
                        subreq.setTopicName(topicName);
                        subreq.setTagRegex(tagRegex);
                        subreq.setGroupName(groupName);
                        subreq.setConsumerType(consumerType);
                        that.subscribeReqSet.remove(subreq);
                        return resp.getRespCode();
                    }
                }).retryCall();
    }

    @Override
    public void heartbeat() {
        if (this.isReconnect) {
            return;
        }
        final MqHeartBeatReq req = new MqHeartBeatReq();
        final String traceId = IdHelper.uuid32();
        req.setTraceId(traceId);
        req.setMethodType(MethodType.C_HEARTBEAT);
        req.setAddress(NetUtil.getLocalHost());
        req.setPort(0);
        req.setTime(System.currentTimeMillis());

        log.debug("[HEARTBEAT] 往服务端发送心跳包 {}", JSON.toJSON(req));

        // 通知全部
        for(RpcChannelFuture channelFuture : channelFutureList) {
            try {
                Channel channel = channelFuture.getChannelFuture().channel();
                MqCommonResp resp = callServer(channel, req, MqCommonResp.class);
                log.debug("[HEARTBEAT] 服务端心跳处理结果 {}", JSON.toJSON(resp));
                if (!MqCommonRespCode.SUCCESS.getCode().equals(resp.getRespCode())) {
                    log.error("[HEARTBEAT] 服务端心跳处理异常 {}", JSON.toJSON(resp));
                }
            } catch (MqException e) {
                this.isReconnect = true;
                log.error("[HEARTBEAT] 服务端返回心跳异常", e);
                this.destroyAll();
                final ConsumerBrokerService that = this;

                // 创建 Future 引用包装器
                final AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
                final ScheduledFuture<?> future = scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            that.initChannelFutureList(that.config);
                            that.registerToBroker();
                            that.reSubscribe();
                            that.isReconnect = false;
                            futureRef.get().cancel(true);
                        } catch (MqException e) {
                            log.error("[HEARTBEAT] 服务端重连失败", e);
                        } catch (Exception e) {
                            log.error("[HEARTBEAT] 服务端重连异常", e);
                            futureRef.get().cancel(true);
                        }
                    }
                }, 5, 5, TimeUnit.SECONDS);
                // 将 Future 存入包装器
                futureRef.set(future);

            }catch (Exception exception) {
                log.error("[HEARTBEAT] 往服务端处理异常", exception);
            }
        }
    }

    @Override
    public MqConsumerPullResp pull(String topicName, String tagRegex, int fetchSize) {
        MqConsumerPullReq req = new MqConsumerPullReq();
        req.setSize(fetchSize);
        req.setGroupName(groupName);
        req.setTagRegex(tagRegex);
        req.setTopicName(topicName);

        final String traceId = IdHelper.uuid32();
        req.setTraceId(traceId);
        req.setMethodType(MethodType.C_MESSAGE_PULL);

        Channel channel = getChannel(null);
        return this.callServer(channel, req, MqConsumerPullResp.class);
    }

    @Override
    public MqCommonResp consumerStatusAck(String messageId, ConsumerStatus consumerStatus) {
        final MqConsumerUpdateStatusReq req = new MqConsumerUpdateStatusReq();
        req.setMessageId(messageId);
        req.setMessageStatus(consumerStatus.getCode());
        req.setConsumerGroupName(groupName);

        final String traceId = IdHelper.uuid32();
        req.setTraceId(traceId);
        req.setMethodType(MethodType.C_CONSUMER_STATUS);

        // 重试
        return Retryer.<MqCommonResp>newInstance()
                .maxAttempt(consumerStatusMaxAttempt)
                .callable(new Callable<MqCommonResp>() {
                    @Override
                    public MqCommonResp call() throws Exception {
                        Channel channel = getChannel(null);
                        MqCommonResp resp = callServer(channel, req, MqCommonResp.class);
                        if(!MqCommonRespCode.SUCCESS.getCode().equals(resp.getRespCode())) {
                            throw new MqException(ConsumerRespCode.CONSUMER_STATUS_ACK_FAILED);
                        }
                        return resp;
                    }
                }).retryCall();
    }

    @Override
    public MqCommonResp consumerStatusAckBatch(List<MqConsumerUpdateStatusDto> statusDtoList) {
        final MqConsumerUpdateStatusBatchReq req = new MqConsumerUpdateStatusBatchReq();
        req.setStatusList(statusDtoList);

        final String traceId = IdHelper.uuid32();
        req.setTraceId(traceId);
        req.setMethodType(MethodType.C_CONSUMER_STATUS_BATCH);

        // 重试
        return Retryer.<MqCommonResp>newInstance()
                .maxAttempt(consumerStatusMaxAttempt)
                .callable(new Callable<MqCommonResp>() {
                    @Override
                    public MqCommonResp call() throws Exception {
                        Channel channel = getChannel(null);
                        MqCommonResp resp = callServer(channel, req, MqCommonResp.class);
                        if(!MqCommonRespCode.SUCCESS.getCode().equals(resp.getRespCode())) {
                            throw new MqException(ConsumerRespCode.CONSUMER_STATUS_ACK_BATCH_FAILED);
                        }
                        return resp;
                    }
                }).retryCall();
    }

    @Override
    public void destroyAll() {
        for(RpcChannelFuture channelFuture : channelFutureList) {
            Channel channel = channelFuture.getChannelFuture().channel();
            final String channelId = ChannelUtil.getChannelId(channel);
            log.info("开始注销：{}", channelId);

            ServiceEntry serviceEntry = InnerChannelUtils.buildServiceEntry(channelFuture);

            BrokerRegisterReq brokerRegisterReq = new BrokerRegisterReq();
            brokerRegisterReq.setServiceEntry(serviceEntry);

            String messageId = IdHelper.uuid32();
            brokerRegisterReq.setTraceId(messageId);
            brokerRegisterReq.setMethodType(MethodType.C_UN_REGISTER);

            this.callServer(channel, brokerRegisterReq, null);

            log.info("完成注销：{}", channelId);
        }
    }

    public ConsumerBrokerConfig getConfig() {
        return config;
    }

    @Override
    public void setConfig(ConsumerBrokerConfig config) {
        this.config = config;
    }
}
