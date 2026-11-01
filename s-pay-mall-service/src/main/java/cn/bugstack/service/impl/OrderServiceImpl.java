package cn.bugstack.service.impl;

import cn.bugstack.common.constants.Constants;
import cn.bugstack.dao.IOrderDao;
import cn.bugstack.domain.po.PayOrder;
import cn.bugstack.domain.req.ShopCartReq;
import cn.bugstack.domain.res.PayOrderRes;
import cn.bugstack.domain.vo.ProductVO;
import cn.bugstack.service.IOrderService;
import cn.bugstack.service.rpc.ProductRPC;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.google.common.eventbus.EventBus;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 订单服务：负责下单主链路（创建订单 → 申请支付单）与订单状态流转。
 * <p>
 * 三条写入路径：
 * 1）createOrder：前端下单，落库 CREATE，再调支付宝拿到支付表单，置为 PAY_WAIT；
 * 2）changeOrderPaySuccess：支付宝回调确认支付后，置为 PAY_SUCCESS 并发布事件；
 * 3）changeOrderClose：定时任务关单，置为 CLOSE。
 * <p>
 * 本类只负责「改状态」，不负责发货、发放权益等后续动作，那些由事件订阅方处理。
 *
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @create 2024-09-29 09:47
 */
@Slf4j
@Service
public class OrderServiceImpl implements IOrderService {

    /**
     * 支付宝服务端异步通知地址；支付结果以它为准，必须公网可访问
     */
    @Value("${alipay.notify_url}")
    private String notifyUrl;

    /**
     * 支付宝同步跳转地址；只用于把用户浏览器带回站点，不能作为支付成功的依据
     */
    @Value("${alipay.return_url}")
    private String returnUrl;

    /** 订单表 DAO */
    @Resource
    private IOrderDao orderDao;

    /** 商品服务（当前为 mock 实现，商品名与价格均为固定值） */
    @Resource
    private ProductRPC productRPC;

    /** 支付宝 SDK 客户端，由 AlipayConfig 装配 */
    @Resource
    private AlipayClient alipayClient;

    /** 进程内事件总线：支付成功后发布事件，供发货 / 权益等下游订阅 */
    @Resource
    private EventBus eventBus;

    /**
     * 创建订单并申请支付单。
     * <p>
     * 先查同一用户、同一商品最近一笔订单，按幂等思路分三种情况处理；
     * 只有不存在可复用订单时，才真正新建一条。
     *
     * @param shopCartReq 下单请求（userId、productId）
     * @return 订单号 + 支付宝支付表单 HTML（前端用它渲染并提交）
     */
    @Override
    public PayOrderRes createOrder(ShopCartReq shopCartReq) throws Exception {
        // 1. 查询当前用户是否存在未支付订单或掉单订单
        //    只按 userId + productId 查最近一条，作为「是否重复下单」的判断依据
        PayOrder payOrderReq = new PayOrder();
        payOrderReq.setUserId(shopCartReq.getUserId());
        payOrderReq.setProductId(shopCartReq.getProductId());

        PayOrder unpaidOrder = orderDao.queryUnPayOrder(payOrderReq);

        // 情况一：已有 PAY_WAIT 订单 → 直接复用，避免重复下单、重复生成支付单
        if (null != unpaidOrder && Constants.OrderStatusEnum.PAY_WAIT.getCode().equals(unpaidOrder.getStatus())) {
            log.info("创建订单-存在，已存在未支付订单。userId:{} productId:{} orderId:{}", shopCartReq.getUserId(), shopCartReq.getProductId(), unpaidOrder.getOrderId());
            return PayOrderRes.builder()
                    .orderId(unpaidOrder.getOrderId())
                    .payUrl(unpaidOrder.getPayUrl())
                    .build();
        // 情况二：已有 CREATE 订单（上次创建支付单失败）→ 补单，只重做支付单，不新建订单
        } else if (null != unpaidOrder && Constants.OrderStatusEnum.CREATE.getCode().equals(unpaidOrder.getStatus())) {
            log.info("创建订单-存在，存在未创建支付单订单，创建支付单开始 userId:{} productId:{} orderId:{}", shopCartReq.getUserId(), shopCartReq.getProductId(), unpaidOrder.getOrderId());
            PayOrder payOrder = doPrepayOrder(unpaidOrder.getProductId(), unpaidOrder.getProductName(), unpaidOrder.getOrderId(), unpaidOrder.getTotalAmount());
            return PayOrderRes.builder()
                    .orderId(payOrder.getOrderId())
                    .payUrl(payOrder.getPayUrl())
                    .build();
        }

        // 2. 查询商品 & 创建订单
        //    金额必须由服务端查询商品后决定，不能采用前端传入的价格
        ProductVO productVO = productRPC.queryProductByProductId(shopCartReq.getProductId());
        // 业务订单号：16 位随机数字，同时作为支付宝的 out_trade_no
        String orderId = RandomStringUtils.randomNumeric(16);
        // 先落库，状态为 CREATE —— 此时支付单尚未生成
        orderDao.insert(PayOrder.builder()
                .userId(shopCartReq.getUserId())
                .productId(shopCartReq.getProductId())
                .productName(productVO.getProductName())
                .orderId(orderId)
                .totalAmount(productVO.getPrice())
                .orderTime(new Date())
                .status(Constants.OrderStatusEnum.CREATE.getCode())
                .build());

        // 3. 创建支付单
        //    调支付宝生成支付表单；成功后回写 pay_url 并把状态推进到 PAY_WAIT
        PayOrder payOrder = doPrepayOrder(productVO.getProductId(), productVO.getProductName(), orderId, productVO.getPrice());

        return PayOrderRes.builder()
                .orderId(orderId)
                .payUrl(payOrder.getPayUrl())
                .build();
    }

    /**
     * 支付成功后推进订单状态，并发布事件。
     * <p>
     * 由支付宝异步回调触发。注意：当前实现没有判断原状态（缺 CAS），
     * 重复回调会重复发布事件，下游必须自行保证幂等。
     *
     * @param orderId 业务订单号（对应支付宝 out_trade_no）
     */
    @Override
    public void changeOrderPaySuccess(String orderId) {
        PayOrder payOrderReq = new PayOrder();
        payOrderReq.setOrderId(orderId);
        payOrderReq.setStatus(Constants.OrderStatusEnum.PAY_SUCCESS.getCode());
        // 更新状态并写入支付时间
        orderDao.changeOrderPaySuccess(payOrderReq);

        // 进程内事件：把订单号交给订阅方做发货 / 发放权益等后续动作。
        // 注意 EventBus 是同步调用、无持久化；订阅方异常默认只记日志，既不回滚也不重试。
        eventBus.post(JSON.toJSONString(payOrderReq));
    }

    /**
     * 查询「已发起支付但超时未收到回调」的订单号，供定时补偿任务使用。
     *
     * @return 订单号列表（最多 10 条）
     */
    @Override
    public List<String> queryNoPayNotifyOrder() {
        return orderDao.queryNoPayNotifyOrder();
    }

    /**
     * 查询「超过 30 分钟仍未支付」的订单号，供定时关单任务使用。
     *
     * @return 订单号列表（最多 50 条）
     */
    @Override
    public List<String> queryTimeoutCloseOrderList() {
        return orderDao.queryTimeoutCloseOrderList();
    }

    /**
     * 关闭订单，把状态置为 CLOSE。
     *
     * @param orderId 业务订单号
     * @return 是否更新成功
     */
    @Override
    public boolean changeOrderClose(String orderId) {
        return orderDao.changeOrderClose(orderId);
    }

    /**
     * 向支付宝申请「电脑网站支付」，并回写支付信息。
     * <p>
     * pageExecute 返回的是一段自动提交的 HTML 表单（并非 URL），
     * 前端把它插入页面后 submit，即可跳转到支付宝收银台。
     *
     * @param productId   商品 ID
     * @param productName 商品名称，作为支付宝订单标题（subject）
     * @param orderId     业务订单号，作为 out_trade_no
     * @param totalAmount 订单金额（单位：元）
     * @return 已填充 pay_url、状态为 PAY_WAIT 的订单对象
     */
    private PayOrder doPrepayOrder(String productId, String productName, String orderId, BigDecimal totalAmount) throws AlipayApiException {
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        // 异步通知：支付结果以服务端通知为准
        request.setNotifyUrl(notifyUrl);
        // 同步跳转：用户支付完成后浏览器回跳的地址
        request.setReturnUrl(returnUrl);

        // 业务参数，字段名必须与支付宝接口文档保持一致（下划线命名）
        JSONObject bizContent = new JSONObject();
        bizContent.put("out_trade_no", orderId);
        bizContent.put("total_amount", totalAmount.toString());
        bizContent.put("subject", productName);
        // 电脑网站支付的固定产品码
        bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");
        request.setBizContent(bizContent.toString());

        // 同步调用支付宝，拿到自动提交的支付表单 HTML
        String form = alipayClient.pageExecute(request).getBody();

        // 支付表单与状态落库：CREATE → PAY_WAIT
        PayOrder payOrder = new PayOrder();
        payOrder.setOrderId(orderId);
        payOrder.setPayUrl(form);
        payOrder.setStatus(Constants.OrderStatusEnum.PAY_WAIT.getCode());

        orderDao.updateOrderPayInfo(payOrder);

        return payOrder;
    }

}
