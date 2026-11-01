package cn.bugstack.job;

import cn.bugstack.service.IOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * 超时关单定时任务：兜底处理「一直挂在 PAY_WAIT 的订单」。
 * <p>
 * 每隔 10 分钟扫描一次，把创建超过 30 分钟仍未支付的订单批量置为 CLOSE，
 * 避免库存/优惠被长期占用。真正的超时判断在 SQL 里（order_time < now() - 30min）。
 *
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @create 2024-09-30 09:59
 */
@Slf4j
@Component()
public class TimeoutCloseOrderJob {

    /** 订单服务：负责查询超时订单与执行关单 */
    @Resource
    private IOrderService orderService;

    /**
     * 每 10 分钟执行一次（cron：秒 分 时 日 月 周）。
     * 0 0/10 * * * ? 表示第 0 秒，每 10 分钟触发，如 00:00、00:10、00:20……
     */
    @Scheduled(cron = "0 0/10 * * * ?")
    public void exec() {
        try {
            log.info("任务；超时30分钟订单关闭");
            // 查询「下单超过 30 分钟且仍未支付」的订单号，最多 50 条
            List<String> orderIds = orderService.queryTimeoutCloseOrderList();
            if (null == orderIds || orderIds.isEmpty()) {
                log.info("定时任务，超时30分钟订单关闭，暂无超时未支付订单 orderIds is null");
                return;
            }
            // 逐条关单：单条失败不影响其他订单（异常由外层统一捕获）
            for (String orderId : orderIds) {
                boolean status = orderService.changeOrderClose(orderId);
                log.info("定时任务，超时30分钟订单关闭 orderId: {} status：{}", orderId, status);
            }
        } catch (Exception e) {
            log.error("定时任务，超时30分钟订单关闭失败", e);
        }
    }

}
