package cn.bugstack.job;

import cn.bugstack.service.IOrderService;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * 支付回调补偿定时任务：处理「支付宝通知丢失 / 处理失败」导致的漏单。
 * <p>
 * 扫描已发起支付（PAY_WAIT）但迟迟没收到异步通知的订单，
 * 主动调用支付宝「交易查询」接口核对真实结果，查出已付款的就补一次状态变更。
 * 与超时关单任务一进一退，共同保证订单最终一致。
 *
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @create 2024-09-30 09:59
 */
@Slf4j
@Component()
public class NoPayNotifyOrderJob {

    /** 订单服务：查询待补偿订单、补写支付成功状态 */
    @Resource
    private IOrderService orderService;

    /** 支付宝 SDK 客户端，用于主动查询交易状态 */
    @Resource
    private AlipayClient alipayClient;

    /**
     * 每分钟执行一次（cron：秒 分 时 日 月 周）。
     * 0 0/1 * * * ? 表示第 0 秒，每分钟触发一次。
     * 多实例部署时需加分布式锁，避免重复查询、重复补偿。
     */
    @Scheduled(cron = "0 0/1 * * * ?")
    public void exec() {
        try {
            log.info("任务；检测未接收到或未正确处理的支付回调通知");
            // 查询「已发起支付但超时未回调」的订单号，最多 10 条
            List<String> orderIds = orderService.queryNoPayNotifyOrder();
            if (null == orderIds || orderIds.isEmpty()) return;

            for (String orderId : orderIds) {
                // 单笔隔离：某一个订单查询失败，不影响其余订单继续补偿
                try {
                    // 用业务订单号（out_trade_no）反查支付宝侧的交易记录
                    AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
                    AlipayTradeQueryModel bizModel = new AlipayTradeQueryModel();
                    bizModel.setOutTradeNo(orderId);
                    request.setBizModel(bizModel);

                    AlipayTradeQueryResponse response = alipayClient.execute(request);

                    // 接口调用失败（网络异常、无权限、交易不存在等），跳过并记录原因
                    if (response == null || !response.isSuccess()) {
                        log.warn("支付宝交易查询失败 orderId:{} code:{} subMsg:{}", orderId,
                                response == null ? null : response.getCode(),
                                response == null ? null : response.getSubMsg());
                        continue;
                    }

                    // 只有交易状态为「支付成功」才补状态；
                    // 查询接口调用成功（code=10000）并不代表用户已付款，
                    // 例如 WAIT_BUYER_PAY 是「已下单未付款」，绝不能改为支付成功
                    String tradeStatus = response.getTradeStatus();
                    if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
                        orderService.changeOrderPaySuccess(orderId);
                        log.info("补偿支付成功 orderId:{} tradeStatus:{}", orderId, tradeStatus);
                    } else {
                        log.info("订单尚未支付，跳过补偿 orderId:{} tradeStatus:{}", orderId, tradeStatus);
                    }
                } catch (Exception e) {
                    log.error("单笔订单补偿失败 orderId:{}", orderId, e);
                }
            }
        } catch (Exception e) {
            log.error("检测未接收到或未正确处理的支付回调通知失败", e);
        }
    }

}
