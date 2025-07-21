# mq

[mq](https://github.com/houbb/mq) 是基于 netty 实现的 java mq 框架，类似于 rocket mq。
本代码是基于houbbmq的升级变更,非常感谢老马啸西风开源的项目，原项目：https://github.com/houbb/mq

[![Build Status](https://travis-ci.com/houbb/mq.svg?branch=master)](https://travis-ci.com/houbb/mq)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.houbb/mq/badge.svg)](http://mvnrepository.com/artifact/com.github.houbb/mq)
[![](https://img.shields.io/badge/license-Apache2-FF0080.svg)](https://github.com/houbb/mq/blob/master/LICENSE.txt)
[![Open Source Love](https://badges.frapsoft.com/os/v2/open-source.svg?v=103)](https://github.com/houbb/nlp-common)

> [变更日志](https://github.com/houbb/mq/blob/master/CHANGELOG.md)
2025-07-16变更：升级maven包,增加断线重连，优化代码

主要用于个人学习，由渐入深，理解 mq 的底层实现原理。

## 特性

- 基于 netty4 的客户端调用服务端

- timeout 超时处理

- broker 启动的 check 检测服务可用性

- load balance 负载均衡

- 基于 TAG 的消息过滤，broker 端实现

- 生产者的消息同步发送，ONE WAY 发送

- 生产消息的批量发送

- 消息状态的批量确认

- fail 支持 failOver failFast 等失败处理策略

- heartbeat 服务端心跳

- AT LEAST ONCE 最少一次原则

- 断线重连

# 快速入门

## 测试

### 注册中心

依赖 maven 包：

```xml
<dependency>
    <groupId>io.github.bdwanga</groupId>
    <artifactId>mq-broker</artifactId>
    <version>0.1.5</version>
</dependency>
```

代码实现：

```java
MqBroker broker = new MqBroker();
broker.start();
```

### 消费者

依赖 maven 包：

```xml
<dependency>
    <groupId>io.github.bdwanga</groupId>
    <artifactId>mq-consumer</artifactId>
    <version>0.1.5</version>
</dependency>
```

代码实现：


```java
final MqConsumerPush mqConsumerPush = new MqConsumerPush();
mqConsumerPush.start();

mqConsumerPush.subscribe("TOPIC", "TAGA");
mqConsumerPush.registerListener(new IMqConsumerListener() {
    @Override
    public ConsumerStatus consumer(MqMessage mqMessage, IMqConsumerListenerContext context) {
        System.out.println("---------- 自定义 " + JSON.toJSONString(mqMessage));
        return ConsumerStatus.SUCCESS;
    }
});
```

### 生产者

依赖 maven 包：

```xml
<dependency>
    <groupId>io.github.bdwanga</groupId>
    <artifactId>mq-producer</artifactId>
    <version>0.1.5</version>
</dependency>
```

代码实现：

```java
MqProducer mqProducer = new MqProducer();
mqProducer.start();

String message = "HELLO MQ!";
MqMessage mqMessage = new MqMessage();
mqMessage.setTopic("TOPIC");
mqMessage.setTags(Arrays.asList("TAGA", "TAGB"));
mqMessage.setPayload(message);

SendResult sendResult = mqProducer.send(mqMessage);
System.out.println(JSON.toJSON(sendResult));
```

# 前言

工作至今，接触 mq 框架已经有很长时间。

但是对于其原理一直只是知道个大概，从来没有深入学习过。

以前一直想写，但由于各种原因被耽搁。

## 技术准备

[Java 并发实战学习](https://houbb.github.io/2019/01/18/jcip-00-overview)

[TCP/IP 协议学习笔记](https://houbb.github.io/2019/04/05/protocol-tcp-ip-01-overview-01)

[Netty 权威指南学习](https://houbb.github.io/2019/05/10/netty-definitive-gudie-00-overview)

这些技术的准备阶段，花费了比较长的时间。

也建议想写 mq 框架的有相关的知识储备。

其他 mq 框架使用的经验此处不再赘述。

## 快速迭代

原来一直想写 mq，却不行动的原因就是想的太多，做的太少。

想一下把全部写完，结果就是啥都没写。

所以本次的开发，每个代码分支做的事情实际很少，只做一个功能点。

陆陆续续经过近一个月的完善，对 mq 框架有了自己的体会和进一步的认知。

代码实现功能，主要参考 [Apache Dubbo](https://dubbo.apache.org/zh/docs/introduction/)

# 文档

## 文档

文档将使用 markdown 文本的形式，补充 code 层面没有的东西。

[【mq】从零开始实现 mq-01-生产者、消费者启动 ](https://mp.weixin.qq.com/s/moF528JiVG9dqCi5oFMbVg)

[【mq】从零开始实现 mq-02-如何实现生产者调用消费者？](https://mp.weixin.qq.com/s/_OF4hbh9llaxN27Cv_cToQ)

[【mq】从零开始实现 mq-03-引入 broker 中间人](https://mp.weixin.qq.com/s/BvEWsLp3_35yFVRqBOxS2w)

[【mq】从零开始实现 mq-04-启动检测与实现优化](https://mp.weixin.qq.com/s/BvEWsLp3_35yFVRqBOxS2w)

[【mq】从零开始实现 mq-05-实现优雅停机](https://mp.weixin.qq.com/s/BvEWsLp3_35yFVRqBOxS2w)

[【mq】从零开始实现 mq-06-消费者心跳检测 heartbeat](https://mp.weixin.qq.com/s/lsvm9UoQWK98Jy3kuS2aNg)

[【mq】从零开始实现 mq-07-负载均衡 load balance](https://mp.weixin.qq.com/s/ZNuecNeVJzIPCp252Hn4GQ)

[【mq】从零开始实现 mq-08-配置优化 fluent](https://mp.weixin.qq.com/s/_O20KKdGwxMcHc87rcuWug)

[【mq】从零开始实现 mq-09-消费者拉取消息 pull message](https://mp.weixin.qq.com/s/bAqOJ4fKWTAVet0Oqv8S0g)

[【mq】从零开始实现 mq-10-消费者拉取消息回执 pull message ack](https://mp.weixin.qq.com/s/OgcQI-Go1ZS9-pdLtYwkcg)

[【mq】从零开始实现 mq-11-消费者消息回执添加分组信息 pull message ack groupName](https://mp.weixin.qq.com/s/3RnB7KhZB3n8yGI6Z02-bw)

[【mq】从零开始实现 mq-12-消息的批量发送与回执](https://mp.weixin.qq.com/s/tg0gxwbGWd7cn_RGMiEWew)

[【mq】从零开始实现 mq-13-注册鉴权 auth](https://mp.weixin.qq.com/s/SzWAqyHpeTrDQyUTknsJGQ)


## 代码注释

代码有详细的注释，便于阅读和后期维护。

## 测试

目前测试代码算不上完善。后续将陆续补全。

# mq 模块

| 模块 | 说明 |
|:---|:---|
| mq-common | 公共代码 |
| mq-broker | 注册中心 |
| mq-producer | 服务端 |
| mq-consumer | 客户端 |
| mq-test | 测试模块 |

# 测试代码

这部分测试代码可以关注公众号【老马啸西风】，后台回复【mq】领取。

![qrcode](qrcode.jpg)

# 后期 ROAD-MAP

- [ ] all 模块

- [x] check broker 启动检测
  
- [x] 关闭时通知 register center

- [x] 优雅关闭添加超时设置
  
- [x] heartbeat 心跳检测机制

- [x] 完善 load-balance 实现 + shardingkey 粘性消费、请求

- [x] 失败重试的拓展

- [x] 消费者 pull 策略实现

- [x] pull 消息消费的 ACK 处理

- [x] broker springboot 实现

- [x] 消息的 ack 处理，要基于 groupName 进行处理

- [x] 消息的回溯消费 offset 

- [x] 消息的批量发送，批量 ACK

- [x] 添加注册鉴权，保证安全性

- [ ] 顺序消息 

- [ ] 事务消息

- [ ] 定时消息

- [ ] 流量控制 back-press 反压

- [ ] 消息可靠性

- [ ] offline message 离线消息

- [ ] dead message 死信队列

- [x] 断线重连


# 中间件等工具开源矩阵

[heaven: 收集开发中常用的工具类](https://github.com/houbb/heaven)

[rpc: 基于 netty4 实现的远程调用工具](https://github.com/houbb/rpc)

[mq: 简易版 mq 实现](https://github.com/houbb/mq)

[ioc: 模拟简易版 spring ioc](https://github.com/houbb/ioc)

[mybatis: 简易版 mybatis](https://github.com/houbb/mybatis)

[cache: 渐进式 redis 缓存](https://github.com/houbb/cache)

[jdbc-pool: 数据库连接池实现](https://github.com/houbb/jdbc-pool)

[sandglass: 任务调度时间工具框架](https://github.com/houbb/sandglass)

[sisyphus: 支持注解的重试框架](https://github.com/houbb/sisyphus)

[resubmit: 防止重复提交框架，支持注解](https://github.com/houbb/resubmit)

[auto-log: 日志自动输出](https://github.com/houbb/auto-log)

[async: 多线程异步并行框架](https://github.com/houbb/async)

