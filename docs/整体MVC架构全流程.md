# 整体 MVC 架构全流程：从模块分层到全链路

> 本文是系列总纲，回答一个问题：**这个项目凭什么这样分层，每一层在整条链路里承担什么。**
> 阅读顺序建议：先看第 1、2 节建立全局印象，再对照第 6、11 节看装配与时序，最后看第 12 节的架构评估。
> 相关文档：《微信扫码登录鉴权全流程》《商品下单全流程》《支付宝支付对接全流程》《支付回调处理全流程》《前端页面全流程》。

---

## 目录

1. [架构总览](#1-架构总览)
2. [五个模块与依赖方向](#2-五个模块与依赖方向)
3. [分层职责与边界](#3-分层职责与边界)
4. [MVC 在 Spring MVC 中的真实落位](#4-mvc-在-spring-mvc-中的真实落位)
5. [一次请求的生命周期](#5-一次请求的生命周期)
6. [Bean 装配全景](#6-bean-装配全景)
7. [数据模型的分层设计](#7-数据模型的分层设计)
8. [出站与入站通信架构](#8-出站与入站通信架构)
9. [横切能力](#9-横切能力)
10. [部署架构](#10-部署架构)
11. [全链路时序](#11-全链路时序)
12. [架构评估与演进](#12-架构评估与演进)
13. [自测清单](#13-自测清单)

---

## 1. 架构总览

```
┌──────────────────────────────── 表现层（web 模块）────────────────────────────────┐
│  WeixinPortalController   LoginController   AliPayController                      │
│  + config（Retrofit2 / AliPay / Guava）  + job（3 个）  + listener（1 个）          │
└──────────────────────────────────────┬───────────────────────────────────────────┘
                                       │ 接口调用（IOrderService / ILoginService）
┌──────────────────────────────────────▼───────────────────────────────────────────┐
│  业务层（service 模块）                                                          │
│  OrderServiceImpl   WeixinLoginServiceImpl   ProductRPC                          │
│  外部调用：IWeixinApiService（Retrofit）  AlipayClient（SDK）                     │
└──────────────────────────────────────┬───────────────────────────────────────────┘
                                       │ DAO 接口
┌──────────────────────────────────────▼───────────────────────────────────────────┐
│  持久层（dao 模块 + web/resources/mybatis）                                       │
│  IOrderDao + pay_order_mapper.xml                                                │
└──────────────────────────────────────┬───────────────────────────────────────────┘
                                       │ PO
┌──────────────────────────────────────▼───────────────────────────────────────────┐
│  模型层（domain）  PayOrder / ProductVO / 各类 Req、Res                           │
│  公共层（common）  Response / Constants / XmlUtil / SignatureUtil                 │
└──────────────────────────────────────┬───────────────────────────────────────────┘
                                       ▼
┌──────────────────────────────── 基础设施 ────────────────────────────────────────┐
│  MySQL   Guava Cache   Guava EventBus   Tomcat                                   │
│  外部：api.weixin.qq.com   支付宝沙箱网关                                         │
└──────────────────────────────────────────────────────────────────────────────────┘
```

**一句话概括这套架构：**

> 表现层只做协议转换，业务层只做编排，持久层只做数据访问，模型层只做数据承载；
> 所有外部依赖（微信、支付宝）都通过**接口**接入，所有横向能力（日志、任务、事件、缓存）都挂在 web 模块。

---

## 2. 五个模块与依赖方向

```
s-pay-mall-mvc（聚合工程）
│
├── s-pay-mall-common      通用能力，无业务
├── s-pay-mall-domain      数据模型，无逻辑
├── s-pay-mall-dao         数据访问接口
├── s-pay-mall-service     业务编排
└── s-pay-mall-web         启动 + 表现层 + 装配
```

**依赖关系（单向，不可逆）：**

```
        ┌─────────┐
        │   web   │
        └────┬────┘
             │ 依赖
        ┌────▼─────┐
        │ service  │
        └──┬────┬──┘
           │    │
     ┌─────▼┐  ┌▼──────┐
     │ dao  │  │domain │
     └──┬───┘  └▲──────┘
        │       │
        └───────┘
             ▲
        ┌────┴────┐
        │ common  │  （所有模块都可依赖）
        └─────────┘
```

| 模块 | 依赖谁 | 被谁依赖 | 能否依赖 Spring Web |
|---|---|---|---|
| common | 无（仅工具库） | 全部 | ❌ |
| domain | common | dao / service / web | ❌ |
| dao | domain | service / web | ❌ |
| service | dao / domain / common | web | ❌ |
| web | 全部 | 无 | ✅ |

**为什么 `service` 不能依赖 Spring Web？**
Service 应能脱离 HTTP 复用（比如被定时任务、MQ 消费者调用）。一旦引入 `HttpServletRequest`，业务逻辑就跟「请求」绑死，无法单测、无法复用。

**为什么 `dao` 只有接口，没有实现？**
MyBatis 用 JDK 动态代理在运行时生成实现。`IOrderDao` 上的 `@Mapper` + XML 的 `namespace` 就完成了绑定，不需要手写 Impl。

**一个不完全符合教科书的地方：**
`mapper xml` 放在 **web 模块**的 `resources/mybatis/mapper/`，因为 `mapper-locations: classpath:/mybatis/mapper/*.xml` 是相对**启动模块** classpath 解析的。更规范的做法是把 XML 放进 dao 模块的 resources，让 dao 自己闭环。

---

## 3. 分层职责与边界

### 3.1 每层做什么、不做什么

| 层 | 必须做 | 绝对不做 |
|---|---|---|
| **Controller** | 参数绑定、格式转换、协议响应（JSON/纯文本/XML）、异常兜底 | 业务判断、SQL、发 HTTP 给第三方 |
| **Service** | 业务编排、状态流转、幂等控制、事务边界 | 依赖 `HttpServletRequest`、直接拼 SQL |
| **DAO** | CRUD、条件查询 | 业务状态判断（如「能否关单」） |
| **Domain** | 字段承载 | 依赖任何框架 |
| **Config** | Bean 装配、属性绑定 | 业务逻辑 |

### 3.2 本项目的实际边界（对照表）

| 行为 | 落在哪 | 是否合理 |
|---|---|---|
| `trade_status` 判断 | Controller | 🟠 偏上：判断属于业务，应下移到 Service |
| 验签 | Controller | ✅ 合理：这是协议层安全 |
| 三种订单分支（复用/补单/新建） | Service | ✅ 合理 |
| 金额取自 `ProductRPC` | Service | ✅ 合理 |
| 状态置为 `PAY_SUCCESS` | Service → DAO | ✅ 合理 |
| 发事件 | Service | 🟠 可接受：也可上移到 Controller 或落 MQ |
| 定时任务 | web/job | ✅ 合理：任务属于调度层 |
| 事件订阅者 | web/listener | ✅ 合理 |

### 3.3 一个典型调用链的分层穿透

```
HTTP 请求
  → AliPayController#createPayOrder          表现层：接参、包装 Response
    → OrderServiceImpl#createOrder           业务层：分支、编排
      → IOrderDao#queryUnPayOrder            持久层：查库
        → pay_order_mapper.xml               映射
          → MySQL                            存储
      → ProductRPC#queryProductByProductId   业务层：查商品
      → AlipayClient#pageExecute             出站：调支付宝
```

每一层只认识下一层的**接口**，不认识实现。这就是依赖倒置在中小项目里的最小实践。

---

## 4. MVC 在 Spring MVC 中的真实落位

先纠正一个常见误解：**MVC 是表现层的内部结构，不是整个工程的分层。**

```
整个工程：web → service → dao → domain
表现层内部：Controller（C） → Model（数据） → View（响应）
```

| MVC 角色 | 本项目的实现 | 说明 |
|---|---|---|
| **Controller** | 3 个 `@RestController` | 只做协议适配 |
| **Model** | PO / DTO / VO / Req / Res | 五种模型各司其职 |
| **View** | JSON、纯文本、XML、HTML 字符串 | **没有模板引擎** |

### 4.1 为什么说本项目「没有 View 层」

```java
@RestController   // = @Controller + @ResponseBody
public class AliPayController { ... }
```

`@ResponseBody` 让返回值绕过 `ViewResolver`，直接由 `HttpMessageConverter` 写进响应体。所以：

| 方法返回 | `produces` | 转换器 | 客户端收到 |
|---|---|---|---|
| `Response<String>` | 默认 JSON | Jackson | `{"code":"0000","data":"..."}` |
| `String` | `text/plain;charset=utf-8` | `StringHttpMessageConverter` | 原样文本（微信 echostr） |
| `String` | `application/xml;charset=UTF-8` | `StringHttpMessageConverter` | 原样 XML（微信回包） |
| `String` | 默认 | `StringHttpMessageConverter` | `success`（支付宝） |

**这就是三个 Controller 返回类型各不相同的根本原因**：它们服务的客户端（前端 / 微信 / 支付宝）要求的响应格式不同。

### 4.2 三种客户端，三种契约

| Controller | 面向 | 认证 | 响应格式 |
|---|---|---|---|
| `WeixinPortalController` | 微信服务器 | 共享 Token + SHA-1 验签 | 纯文本 / XML |
| `LoginController` | 自家前端 | 无（ticket 即凭证） | JSON |
| `AliPayController` | 前端 + 支付宝 | 前端无 / 回调 RSA2 | JSON / 纯文本 |

`AliPayController` 同时服务两类调用方，是支付类项目的典型形态。

---

## 5. 一次请求的生命周期

以 `POST /api/v1/alipay/create_pay_order` 为例：

```
① Tomcat 接收 TCP 连接，构造 HttpServletRequest / Response
② Filter 链（编码、CORS、日志……）
③ DispatcherServlet 接管
④ HandlerMapping（RequestMappingHandlerMapping）
      → 按 method + path + produces 匹配到 createPayOrder
      → 返回 HandlerExecutionChain（含拦截器）
⑤ HandlerAdapter（RequestMappingHandlerAdapter）
      → 选择参数解析器：
          @RequestBody  → RequestResponseBodyMethodProcessor → Jackson
          @RequestParam → RequestParamMethodArgumentResolver
          HttpServletRequest → ServletRequestMethodArgumentResolver
⑥ 反射调用 Controller 方法
⑦ HandlerMethodReturnValueHandler
      → RequestResponseBodyMethodProcessor
      → 按 produces 选 HttpMessageConverter → 写响应
⑧ Interceptor#afterCompletion、Filter 收尾
```

**七步里最容易出问题的地方：**

| 步骤 | 常见坑 |
|---|---|
| ③ | 启动类不在 `cn.bugstack` 根包下 → Scanner 扫不到 Controller |
| ④ | 类级与方法级路径拼接错误；两个方法同 method + path |
| ④ | `produces` 不匹配 → 406 |
| ⑤ | `@RequestParam` 默认 `required=true` → 缺参直接 400，进不了方法体 |
| ⑤ | `@RequestBody` 只能读一次流；若被 Filter 提前读取会空 |
| ⑦ | 声明 `text/plain` 但返回对象 → 无转换器可用 → 500 |

**本项目的特殊点：**

- **没有 `@RestControllerAdvice`**：所有异常靠 Controller 内 `try/catch` 兜底，这是架构缺口（见第 12 节）。
- **`@CrossOrigin("*")`**：跨域在 Filter/Interceptor 阶段处理，允许所有来源。
- **静态资源不在 Spring 里**：`html/images` 由 Nginx 托管，Spring Boot 只提供 `/api`。

---

## 6. Bean 装配全景

这是理解「项目怎么跑起来」的关键一图：

```
┌─────────────────────────┐
│ Retrofit2Config         │
│  retrofit()             │──> Retrofit
│  weixinApiService()     │──> IWeixinApiService（动态代理）
└─────────────────────────┘            │
                                       │
┌─────────────────────────┐            │
│ AliPayConfigProperties  │            │
│  @ConfigurationProperties│           │
└───────────┬─────────────┘            │
            ▼                          │
┌─────────────────────────┐            │
│ AliPayConfig            │            │
│  alipayClient()         │──> AlipayClient
└─────────────────────────┘            │
                                       │
┌─────────────────────────┐            │
│ GuavaConfig             │            │
│  weixinAccessToken()    │──> Cache<String,String>
│  openidToken()          │──> Cache<String,String>
│  eventBusListener()     │──> EventBus ──> OrderPaySuccessListener
└─────────────────────────┘            │
                                       │
              ┌────────────────────────┴──────────────────────┐
              ▼                                               ▼
┌──────────────────────────┐                    ┌──────────────────────────┐
│ WeixinLoginServiceImpl   │                    │ OrderServiceImpl         │
│  @Resource               │                    │  @Resource               │
│   weixinApiService       │                    │   orderDao               │
│   weixinAccessToken      │                    │   productRPC             │
│   openidToken            │                    │   eventBus               │
│   appId / appSecret ...  │                    │   notifyUrl / returnUrl  │
└──────────────────────────┘                    │   alipayClient           │
                                                └──────────────────────────┘
```

**Bean 装配的四个要点：**

| 要点 | 说明 |
|---|---|
| **`@Resource` 按名字优先** | 字段名 `weixinAccessToken` 必须等于 `@Bean(name="weixinAccessToken")`，否则两个 `Cache` 会冲突 |
| **`@Configuration` + `@Bean`** | 用于装配第三方对象（`Retrofit`、`AlipayClient`、`EventBus`） |
| **`@EnableConfigurationProperties`** | 必须加，否则 `AliPayConfigProperties` 不会成为 Bean |
| **`@Mapper`** | MyBatis 自动扫描，生成 DAO 代理，无需 `@Bean` |

**为什么配置类都在 web 模块？**
因为它们需要 Spring 容器，而 service 模块要保持「纯业务」。配置类在启动模块做「组装」，service 只声明「我需要什么」。

---

## 7. 数据模型的分层设计

| 后缀 | 代表类 | 使用边界 | 变化原因 |
|---|---|---|---|
| **PO** | `PayOrder` | DAO ↔ DB | 表结构变化 |
| **DTO** | `CreatePayRequestDTO` | 前端 → Controller | 前端契约变化 |
| **VO** | `ProductVO` | 内部展示 | 展示需求变化 |
| **Req** | `ShopCartReq` | Controller → Service | 业务入参变化 |
| **Res** | `PayOrderRes` | Service → Controller | 业务出参变化 |
| **通用** | `Response<T>` | 所有 JSON 接口 | 统一响应契约 |

```
前端 JSON
   │  Jackson 反序列化
   ▼
CreatePayRequestDTO（外部契约，可被任意构造）
   │  Controller 转换
   ▼
ShopCartReq（内部契约，Service 可信）
   │  Service 组装
   ▼
PayOrder（持久对象）
   │  MyBatis
   ▼
MySQL
```

**为什么要隔这么多层？**

1. **防腐**：前端加字段不会污染 Service；
2. **安全**：`CreatePayRequestDTO` 里的 `userId` 可以在转换时被登录态覆盖；
3. **演进自由**：`PayOrder` 加字段不影响接口契约。

**当前实现的不足：**
`PayOrderRes` 直接带出 `payUrl`（HTML），又没有把 `orderStatusEnum` 用上；`ShopCartReq` 与 `CreatePayRequestDTO` 字段几乎相同，转换成本高但收益暂不明显——在小型项目里可以接受，但要有意识地知道「这是为了隔离，不是为了好看」。

---

## 8. 出站与入站通信架构

### 8.1 出站：我们调别人

| 目标 | 客户端 | 装配类 | 协议 |
|---|---|---|---|
| 微信 API | Retrofit + Jackson | `Retrofit2Config` | REST/JSON，`access_token` 鉴权 |
| 支付宝 | `AlipayClient` | `AliPayConfig` | 表单/JSON，RSA2 签名 |

**两者的设计差异：**

| 对比 | Retrofit | AlipayClient |
|---|---|---|
| 声明方式 | 注解接口（`@GET/@POST`） | 请求对象（`AlipayTradePagePayRequest`） |
| 鉴权 | `@Query("access_token")` | 内部自动 RSA2 签名 |
| 返回 | `Call<T>`，需 `execute()` | 直接返回响应对象 |
| 适合 | 通用 REST | 支付宝专用协议 |

### 8.2 入站：别人调我们

| 来源 | 接口 | 认证 | 响应 |
|---|---|---|---|
| 微信 | `GET/POST /api/v1/weixin/portal/receive` | 共享 Token + SHA-1 | `echostr` / XML |
| 支付宝 | `POST /api/v1/alipay/alipay_notify_url` | RSA2 公钥验签 | `success` |

```
                    ┌──── 入站（被动）────┐
微信服务器 ─────────>│ 验签 → 解析 XML     │
支付宝    ─────────>│ 验签 → 解析 form    │
                    └─────────────────────┘

                    ┌──── 出站（主动）────┐
                    │ Retrofit / SDK      │──> 微信 / 支付宝
                    └─────────────────────┘
```

**这是整套架构的核心特征：** 同一个系统，既要能**主动发起**支付请求，又要能**被动接收**支付结果。两条链路使用完全不同的认证机制，绝不能混用。

---

## 9. 横切能力

### 9.1 日志

```java
@Slf4j   // Lombok，等价于 private static final Logger log = LoggerFactory.getLogger(X.class);
```

配置：[`logback-spring.xml`](../s-pay-mall-web/src/main/resources/logback-spring.xml)，级别 `info`。

### 9.2 定时任务

| 任务 | 频率 | 作用 |
|---|---|---|
| `NoPayNotifyOrderJob` | 每 3 秒 | 扫描 1 分钟前仍 `PAY_WAIT` 的订单，主动查询支付宝 |
| `TimeoutCloseOrderJob` | 每 10 分钟 | 扫描 30 分钟未支付订单，置 `CLOSE` |

依赖启动类上的 `@EnableScheduling`。**多实例部署时必须加分布式锁**，否则同一订单会被多个实例同时处理。

### 9.3 事件

```
OrderServiceImpl#changeOrderPaySuccess
    → eventBus.post(JSON)
        → OrderPaySuccessListener#handleEvent
            → 发货 / 充值 / 开通会员 / 返利
```

- Guava `EventBus` **同步**执行；
- 订阅者异常会传回 `post` 调用方；
- 进程重启会丢消息 → 生产应换 MQ + 本地消息表。

### 9.4 缓存

| 缓存 | 内容 | TTL | 用途 |
|---|---|---|---|
| `weixinAccessToken` | 微信 access_token | 2h | 避免频繁取 token |
| `openidToken` | ticket → openid | 1h | 扫码登录中转 |

均为**进程内缓存**，多实例不共享 → 生产换 Redis。

### 9.5 异常处理

```
Controller 内 try/catch → Response(code=UN_ERROR) / 返回 "" / 返回 null
```

**没有全局异常处理器**，这是明确的架构缺口：

- 好处：微信/支付宝回调不会因异常返回 500；
- 坏处：每个 Controller 都要重复写 `try/catch`，容易漏。

### 9.6 事务

**当前没有任何 `@Transactional`。**

下单流程「insert 订单 → update 支付信息」如果中途失败，会留下 `CREATE` 状态的半成品。代码用「补单分支」兜底，但这是**用业务逻辑弥补架构缺失**，不是正解。

---

## 10. 部署架构

```
                          公网
                            │
        ┌───────────────────┴────────────────────┐
        │                                        │
   ┌────▼─────┐                            ┌─────▼─────┐
   │  Nginx   │  :80                       │  natapp   │ 内网穿透
   │  静态页   │                            │  公网域名   │
   │  /api 反代│                            └─────┬─────┘
   └────┬─────┘                                  │
        │                                        │
        └──────────────┬─────────────────────────┘
                       ▼
                ┌──────────────┐
                │ Spring Boot  │ :8080
                └──┬────────┬──┘
                   │        │
              ┌────▼──┐  ┌──▼────────────┐
              │ MySQL │  │ Guava/EventBus│
              └───────┘  └───────────────┘
                   ▲
        ┌──────────┴──────────┐
        │ 微信服务器 / 支付宝   │ （通过 natapp 回调）
        └─────────────────────┘
```

**部署文件：**

| 文件 | 作用 |
|---|---|
| [`docker-compose-environment.yml`](../docs/dev-ops/docker-compose-environment.yml) | MySQL、Redis、Nginx 等环境 |
| [`docker-compose-app.yml`](../docs/dev-ops/docker-compose-app.yml) | 应用容器 |
| [`natapp/内网穿透说明.md`](../docs/dev-ops/natapp/内网穿透说明.md) | 公网回调必备 |
| [`nginx/html`](../docs/dev-ops/nginx/html) | 静态页面 |

**三个必须理解的部署事实：**

1. 静态页由 Nginx 托管，**不经过 Spring Boot**；
2. 微信/支付宝回调**必须公网可达**，本地开发只能靠 natapp；
3. 项目当前用**进程内缓存**，所以只能单实例部署（否则登录态/access_token 不共享）。

---

## 11. 全链路时序

把所有模块串起来看一次：

```
【阶段 1｜登录：web → service → 微信】
前端 login.html
  └─ GET weixin_qrcode_ticket
       └─ LoginController
            └─ WeixinLoginServiceImpl.createQrCodeTicket()
                 ├─ Guava 取 access_token（没有则 Retrofit 调微信）
                 └─ Retrofit createQrCode → ticket
  └─ 展示二维码、每 3s 轮询

微信服务器
  └─ POST receive?openid=xxx（XML 含 Ticket）
       └─ WeixinPortalController
            ├─ XmlUtil.xmlToBean
            └─ saveLoginState(ticket, openid)
                 ├─ openidToken.put(ticket, openid)
                 └─ Retrofit sendMessage（模板消息）

前端轮询命中 → openid → Cookie → 跳首页

【阶段 2｜下单：web → service → dao → domain】
前端 index.html
  └─ POST create_pay_order
       └─ AliPayController
            └─ OrderServiceImpl.createOrder()
                 ├─ IOrderDao.queryUnPayOrder（复用/补单/新建）
                 ├─ ProductRPC.queryProductByProductId（金额以服务端为准）
                 ├─ IOrderDao.insert（status=CREATE）
                 ├─ AlipayClient.pageExecute（表单 HTML）
                 └─ IOrderDao.updateOrderPayInfo（pay_url + PAY_WAIT）

【阶段 3｜支付：浏览器 → 支付宝】
前端写入表单 → POST 网关 → 用户付款

【阶段 4｜回调：支付宝 → web → service → dao → EventBus】
支付宝
  └─ POST alipay_notify_url
       └─ AliPayController#payNotify
            ├─ 收集 params
            ├─ AlipaySignature.rsa256CheckContent（验签）
            └─ OrderServiceImpl.changeOrderPaySuccess(orderId)
                 ├─ IOrderDao 更新 status=PAY_SUCCESS、pay_time
                 └─ EventBus.post
                      └─ OrderPaySuccessListener（发货/充值/返利）
       └─ return "success"

【阶段 5｜兜底：job → service】
NoPayNotifyOrderJob（3s）：查 PAY_WAIT ≥1min → 主动查询支付宝 → 补状态
TimeoutCloseOrderJob（10min）：查 PAY_WAIT ≥30min → CLOSE
```

**链路上的四个凭证：**

```
ticket   →   openid   →   orderId   →   trade_no
二维码      用户身份      商户订单      支付宝交易
```

每一环只把「下一环所需的最小信息」交出去，这是这套设计最值得迁移到其他项目的地方。

---

## 12. 架构评估与演进

### 12.1 做得好的地方

| 优点 | 体现 |
|---|---|
| **分层清晰** | Controller 极薄，业务集中在 Service |
| **外部依赖接口化** | `IWeixinApiService` 用动态代理，替换渠道成本低 |
| **事件解耦** | 支付成功后做什么由订阅者决定，符合开闭原则 |
| **幂等意识** | 下单有三种已存在订单分支 |
| **最终一致性兜底** | 「回调 + 主动查询 + 超时关单」三件套 |
| **协议适配到位** | 三个 Controller 各自输出了正确的格式与编码 |

### 12.2 架构级缺口

| # | 缺口 | 影响 | 优先级 |
|---|---|---|---|
| 1 | **无事务边界** | 下单/改单可能产生中间态 | 🔴 P0 |
| 2 | **无统一异常处理** | 每个 Controller 重复 `try/catch`，易漏 | 🟠 P1 |
| 3 | **无鉴权层** | `userId` 信前端，可越权下单 | 🔴 P0 |
| 4 | **缓存进程内** | 无法多实例部署 | 🟠 P1 |
| 5 | **无状态 CAS** | 回调/关单不幂等，可能重复发货或错关单 | 🔴 P0 |
| 6 | **无分布式锁** | 定时任务多实例重复执行 | 🟠 P1 |
| 7 | **同步外部调用无超时/熔断** | 支付宝抖动会拖垮线程池 | 🟠 P1 |
| 8 | **密钥硬编码** | 安全风险 | 🔴 P0 |
| 9 | **EventBus 无持久化** | 重启丢事件，业务漏做 | 🟠 P1 |

### 12.3 演进路线（建议顺序）

```
P0（正确性 / 安全）
  ① 引入 @Transactional，明确事务边界
  ② 状态更新全部改为 CAS：where status = 原状态
  ③ 增加登录鉴权层（JWT + 拦截器），userId 从上下文取
  ④ 密钥迁移到环境变量 / 配置中心

P1（可扩展 / 稳定性）
  ⑤ Guava Cache → Redis（登录态、access_token、nonce 去重）
  ⑥ 定时任务加分布式锁（ShedLock / Redis）
  ⑦ EventBus → MQ + 本地消息表
  ⑧ 全局异常处理 @RestControllerAdvice
  ⑨ 外部调用加超时、重试、熔断

P2（工程化）
  ⑩ 拆分用户中心、订单中心、支付中心
  ⑪ 引入 API 网关与限流
  ⑫ 链路追踪（traceId 贯穿日志）
```

### 12.4 如果重写一次，架构会怎么变

```
web        Controller（只做协议转换）+ 全局异常 + 拦截器
           ↓
service    领域服务（OrderService/PaymentService/LoginService）
           ↓
infra      DAO / 缓存 / MQ / 外部客户端（Retrofit、Alipay SDK）
           ↓
domain     实体 + 值对象（PO/VO 合并为领域模型）
common     统一响应、工具、常量
```

差异不在「多分了几层」，而在三件事：

1. **事务边界明确**：每个用例一个事务；
2. **状态迁移有守卫**：CAS + 状态机；
3. **外部依赖可替换**：接口 + 实现分离，方便换渠道。

---

## 13. 自测清单

**分层与依赖**

- [ ] 说出五个模块的职责与依赖方向，为什么 service 不能依赖 Spring Web？
- [ ] 为什么 DAO 只有接口没有实现类？
- [ ] `mapper xml` 放在 web 模块有什么问题？

**Spring MVC**

- [ ] `@RestController` 与 `@Controller` 的区别？本项目为什么「没有 View 层」？
- [ ] 完整复述一次请求的 7 个步骤。
- [ ] 三个 Controller 的响应格式为什么各不相同？
- [ ] `produces` 影响哪两个环节？

**装配**

- [ ] 说出 `@Resource` 按名字注入在本项目的两个 `Cache` 上的影响。
- [ ] 配置类为什么都放在 web 模块？
- [ ] `@EnableConfigurationProperties` 不加会怎样？

**模型**

- [ ] PO / DTO / VO / Req / Res 分别用在哪、为什么不能合并？
- [ ] `CreatePayRequestDTO` 与 `ShopCartReq` 字段几乎一样，为什么要分两个？

**通信**

- [ ] 出站两条链路（Retrofit / AlipayClient）的鉴权方式有什么不同？
- [ ] 入站两条链路（微信 / 支付宝）在协议、认证、响应上的差异。

**横切与部署**

- [ ] 三个定时任务/补偿机制分别在解决什么问题？
- [ ] 为什么当前只能单实例部署？要改哪三处？
- [ ] 项目缺事务会带来什么后果？用哪个分支在兜底？

**整体**

- [ ] 画出从扫码到支付成功的五阶段全链路。
- [ ] 说出四个凭证 `ticket / openid / orderId / trade_no` 的流转。
- [ ] 如果给你一周做加固，P0 的四件事是什么？

---

> **系列文档地图**
> | 文档 | 关注点 |
> |---|---|
> | 《微信扫码登录鉴权全流程》 | 身份获取：ticket → openid |
> | 《商品下单全流程》 | 订单生成：CREATE → PAY_WAIT |
> | 《支付宝支付对接全流程》 | 支付发起：表单 → 收银台 |
> | 《支付回调处理全流程》 | 结果确认：验签 → PAY_SUCCESS → 事件 |
> | 《前端页面全流程》 | 用户视角：两个页面怎么串起来 |
> | **《整体 MVC 架构全流程》** | **总纲：分层、装配、通信、演进** |
