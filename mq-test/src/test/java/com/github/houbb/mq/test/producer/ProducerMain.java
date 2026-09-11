package com.github.houbb.mq.test.producer;

import com.alibaba.fastjson.JSON;
import com.github.houbb.heaven.util.util.DateUtil;
import com.github.houbb.mq.common.dto.req.MqMessage;
import com.github.houbb.mq.producer.core.MqProducer;
import com.github.houbb.mq.producer.dto.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * @author binbin.hou
 * @since 1.0.0
 */
public class ProducerMain {

    public static void main(String[] args) {
        MqProducer mqProducer = new MqProducer();
        mqProducer.appKey("test")
                .appSecret("mq");
        mqProducer.start();

        for(int i = 0; i < 10; i++) {
            MqMessage mqMessage = buildMessage(i);
//            SendResult sendResult = mqProducer.send(mqMessage);
//            System.out.println(JSON.toJSON(mqMessage));
            mqProducer.send(mqMessage);
//            try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }
        }
    }

    private static MqMessage buildMessage(int i) {
        String message = "HELLO MQ!" + i;
        MqMessage mqMessage = new MqMessage();
        mqMessage.setTopic("TOPIC");
        mqMessage.setTags(Arrays.asList("TAGA", "TAGB"));
        mqMessage.setPayload(message);
        mqMessage.setOrderMsgKey("devno");
        return mqMessage;
    }

}
