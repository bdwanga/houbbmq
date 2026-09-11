package com.github.houbb.mq.broker.support.push;

import com.github.houbb.mq.common.api.Destroyable;

/**
 * 消息推送服务
 *
 * @author binbin.hou
 * @since 1.0.0
 */
public interface IBrokerPushService extends Destroyable {

    /**
     * 异步推送
     * @param context 消息
     */
    void asyncPush(final BrokerPushContext context);

    /**
     * 同步推送
     * @param context 消息
     */
    void syncPush(final BrokerPushContext context);

}
