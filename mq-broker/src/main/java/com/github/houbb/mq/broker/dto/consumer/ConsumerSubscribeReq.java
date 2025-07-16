package com.github.houbb.mq.broker.dto.consumer;

import com.github.houbb.mq.common.dto.req.MqCommonReq;

import java.util.Objects;

/**
 * 消费者注册入参
 * @author binbin.hou
 * @since 0.0.3
 */
public class ConsumerSubscribeReq extends MqCommonReq {

    /**
     * 分组名称
     * @since 0.0.3
     */
    private String groupName;

    /**
     * 标题名称
     */
    private String topicName;

    /**
     * 标签正则
     */
    private String tagRegex;

    /**
     * 消费者类型
     * @since 0.0.9
     */
    private String consumerType;

    public String getConsumerType() {
        return consumerType;
    }

    public void setConsumerType(String consumerType) {
        this.consumerType = consumerType;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getTopicName() {
        return topicName;
    }

    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }

    public String getTagRegex() {
        return tagRegex;
    }

    public void setTagRegex(String tagRegex) {
        this.tagRegex = tagRegex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConsumerSubscribeReq that = (ConsumerSubscribeReq) o;
        return Objects.equals(groupName, that.groupName) && Objects.equals(topicName, that.topicName) && Objects.equals(tagRegex, that.tagRegex) && Objects.equals(consumerType, that.consumerType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupName, topicName, tagRegex, consumerType);
    }
}
