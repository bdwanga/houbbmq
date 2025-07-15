package com.github.houbb.mq.consumer.core;

import com.alibaba.fastjson.JSON;
import com.github.houbb.heaven.util.util.CollectionUtil;
import com.github.houbb.log.integration.core.Log;
import com.github.houbb.log.integration.core.LogFactory;
import com.github.houbb.mq.common.constant.ConsumerTypeConst;
import com.github.houbb.mq.common.dto.req.MqMessage;
import com.github.houbb.mq.common.dto.req.component.MqConsumerUpdateStatusDto;
import com.github.houbb.mq.common.dto.resp.MqCommonResp;
import com.github.houbb.mq.common.dto.resp.MqConsumerPullResp;
import com.github.houbb.mq.common.resp.ConsumerStatus;
import com.github.houbb.mq.common.resp.MqCommonRespCode;
import com.github.houbb.mq.common.resp.MqException;
import com.github.houbb.mq.consumer.api.IMqConsumerListenerContext;
import com.github.houbb.mq.consumer.dto.MqTopicTagDto;
import com.github.houbb.mq.consumer.support.listener.MqConsumerListenerContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 拉取消费策略
 *
 * @author binbin.hou
 * @since 0.0.9
 */
public class MqConsumerPull extends MqConsumerPush  {

    private static final Log log = LogFactory.getLog(MqConsumerPull.class);

    /**
     * 拉取定时任务
     *
     * @since 0.0.9
     */
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

    /**
     * 单次拉取大小
     * @since 0.0.9
     */
    private int size = 10;

    /**
     * 初始化延迟毫秒数
     * @since 0.0.9
     */
    private int pullInitDelaySeconds = 5;

    /**
     * 拉取周期
     * @since 0.0.9
     */
    private int pullPeriodSeconds = 5;

    /**
     * 订阅列表
     * @since 0.0.9
     */
    private final List<MqTopicTagDto> subscribeList = new ArrayList<>();

    /**
     * 状态回执是否批量
     * @since 0.1.3
     */
    private boolean ackBatchFlag = true;

    public MqConsumerPull size(int size) {
        this.size = size;
        return this;
    }

    public MqConsumerPull pullInitDelaySeconds(int pullInitDelaySeconds) {
        this.pullInitDelaySeconds = pullInitDelaySeconds;
        return this;
    }

    public MqConsumerPull pullPeriodSeconds(int pullPeriodSeconds) {
        this.pullPeriodSeconds = pullPeriodSeconds;
        return this;
    }

    public MqConsumerPull ackBatchFlag(boolean ackBatchFlag) {
        this.ackBatchFlag = ackBatchFlag;
        return this;
    }

    /**
     * 初始化拉取消息
     * @since 0.0.6
     */
    @Override
    public void afterInit() {
        //5S 发一次心跳
        scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if(CollectionUtil.isEmpty(subscribeList)) {
                    log.warn("订阅列表为空，忽略处理。");
                    return;
                }
                try {
                    for(MqTopicTagDto tagDto : subscribeList) {
                        final String topicName = tagDto.getTopicName();
                        final String tagRegex = tagDto.getTagRegex();

                        MqConsumerPullResp resp = consumerBrokerService.pull(topicName, tagRegex, size);

                        if(MqCommonRespCode.SUCCESS.getCode().equals(resp.getRespCode())) {
                            List<MqMessage> mqMessageList = resp.getList();
                            if(CollectionUtil.isNotEmpty(mqMessageList)) {
                                List<MqConsumerUpdateStatusDto> statusDtoList = new ArrayList<>(mqMessageList.size());
                                for(MqMessage mqMessage : mqMessageList) {
                                    IMqConsumerListenerContext context = new MqConsumerListenerContext();
                                    final String messageId = mqMessage.getTraceId();
                                    ConsumerStatus consumerStatus = mqListenerService.consumer(mqMessage, context);
                                    log.info("消息：{} 消费结果 {}", messageId, consumerStatus);

                                    // 状态同步更新
                                    if(!ackBatchFlag) {
                                        MqCommonResp ackResp = consumerBrokerService.consumerStatusAck(messageId, consumerStatus);
                                        log.info("消息：{} 状态回执结果 {}", messageId, JSON.toJSON(ackResp));
                                    } else {
                                        // 批量
                                        MqConsumerUpdateStatusDto statusDto = new MqConsumerUpdateStatusDto();
                                        statusDto.setMessageId(messageId);
                                        statusDto.setMessageStatus(consumerStatus.getCode());
                                        statusDto.setConsumerGroupName(groupName);
                                        statusDtoList.add(statusDto);
                                    }
                                }

                                // 批量执行
                                if(ackBatchFlag) {
                                    MqCommonResp ackResp = consumerBrokerService.consumerStatusAckBatch(statusDtoList);
                                    log.info("消息：{} 状态批量回执结果 {}", statusDtoList, JSON.toJSON(ackResp));
                                    statusDtoList = null;
                                }
                            }
                        } else {
                            log.error("拉取消息失败: {}", JSON.toJSON(resp));
                        }
                    }
                } catch (MqException e) {
                    log.error("拉取消息返回异常", e);
                    if (MqCommonRespCode.TIMEOUT.getCode().equals(e.getCode())) {
                        log.warn("拉取消息超时，忽略处理。");
                    } else {
                        log.error("拉取消息异常", e);
                    }
                } catch (Exception e) {
                    log.error("拉取消息异常", e);
                }

            }
        }, pullInitDelaySeconds, pullPeriodSeconds, TimeUnit.SECONDS);
    }

    @Override
    protected String getConsumerType() {
        return ConsumerTypeConst.PULL;
    }

    @Override
    public synchronized void subscribe(String topicName, String tagRegex) {
        MqTopicTagDto tagDto = buildMqTopicTagDto(topicName, tagRegex);

        if(!subscribeList.contains(tagDto)) {
            subscribeList.add(tagDto);
        }
    }

    @Override
    public void unSubscribe(String topicName, String tagRegex) {
        MqTopicTagDto tagDto = buildMqTopicTagDto(topicName, tagRegex);

        subscribeList.remove(tagDto);
    }

    private MqTopicTagDto buildMqTopicTagDto(String topicName, String tagRegex) {
        MqTopicTagDto dto = new MqTopicTagDto();
        dto.setTagRegex(tagRegex);
        dto.setTopicName(topicName);
        // 主动拉取这里会有问题，会导致不同的 groupName 的消息，实际上已经被消费了
        // 所有实际上应该有一个消息+group 的映射关系表，单个消息可以被多次重复消费。
        // groupName+messageId+status==>在数据库层面实现
        dto.setGroupName(groupName);
        return dto;
    }

}
