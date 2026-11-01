package cn.bugstack.config;

import cn.bugstack.listener.OrderPaySuccessListener;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.eventbus.EventBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 进程内基础组件装配：两个 Guava 本地缓存 + 一个事件总线。
 * <p>
 * 1）weixinAccessToken：缓存微信接口调用凭证 access_token；
 * 2）openidToken：缓存「扫码 ticket → openid」的登录态；
 * 3）EventBus：进程内发布-订阅，用于支付成功后的后续动作解耦。
 * <p>
 * 注意：缓存和事件总线都只存在于当前 JVM，多实例部署时彼此不共享。
 */
@Configuration
public class GuavaConfig {

    /**
     * 微信 access_token 缓存。
     * <p>
     * key：公众号 appid（当前只对接一个公众号，因此相当于固定 key）；
     * value：微信返回的 access_token。
     * expireAfterWrite(2, HOURS)：写入 2 小时后失效，与微信侧 expires_in=7200 秒对齐；
     * 只读不续期，因此到期后需重新调用微信接口获取。
     */
    @Bean(name = "weixinAccessToken")
    public Cache<String, String> weixinAccessToken() {
        return CacheBuilder.newBuilder()
                .expireAfterWrite(2, TimeUnit.HOURS)
                .build();
    }

    /**
     * 扫码登录态缓存。
     * <p>
     * key：创建二维码时返回的 ticket；value：扫码用户的 openid。
     * expireAfterWrite(1, HOURS)：登录扫码的有效窗口为 1 小时；
     * 前端轮询 check_login 时按 ticket 读取，读到即视为登录成功。
     */
    @Bean(name = "openidToken")
    public Cache<String, String> openidToken() {
        return CacheBuilder.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    /**
     * 进程内事件总线，并在创建时注册订阅者。
     * <p>
     * 支付成功后 OrderServiceImpl 调用 eventBus.post(...) 发布消息，
     * 由 OrderPaySuccessListener 的 @Subscribe 方法消费（同步执行）。
     *
     * @param listener 支付成功事件订阅者，由 Spring 自动注入
     */
    @Bean
    public EventBus eventBusListener(OrderPaySuccessListener listener){
        EventBus eventBus = new EventBus();
        eventBus.register(listener);
        return eventBus;
    }

}
