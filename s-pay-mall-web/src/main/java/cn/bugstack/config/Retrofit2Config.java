package cn.bugstack.config;

import cn.bugstack.service.weixin.IWeixinApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description Retrofit2 配置类，用于装配微信 API 的 HTTP 调用客户端
 * <p>
 * 作用：把 Retrofit 实例交给 Spring 容器管理，并通过动态代理生成 {@link IWeixinApiService} 的实现类。
 * 业务层只需调用接口方法，无需手写 HTTP 请求代码，注解（@GET/@POST/@Query/@Body）会被
 * Retrofit 自动翻译成真实的请求报文。
 * @create 2024-09-28 13:30
 */
@Slf4j
@Configuration
public class Retrofit2Config {

    /**
     * 微信 API 的根地址，接口中只需写相对路径（如 cgi-bin/token），最终会自动拼接为完整 URL
     */
    private static final String BASE_URL = "https://api.weixin.qq.com/";

    /**
     * 构建 Retrofit 单例
     * <p>
     * 1. baseUrl：指定微信 API 的服务地址；
     * 2. JacksonConverterFactory：负责 JSON 与 Java 对象的相互转换，请求体序列化、响应体反序列化。
     *
     * @return Retrofit 实例
     */
    @Bean
    public Retrofit retrofit() {
        return new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(JacksonConverterFactory.create()).build();
    }

    /**
     * 生成微信 API 服务的代理对象
     * <p>
     * retrofit.create 基于 JDK 动态代理，把接口上标注的请求注解解析成实际的 HTTP 调用，
     * 因此无需编写接口实现类，即可直接注入使用。
     *
     * @param retrofit 上一步构建的 Retrofit 实例
     * @return 微信 API 服务的代理实现
     */
    @Bean
    public IWeixinApiService weixinApiService(Retrofit retrofit) {
        return retrofit.create(IWeixinApiService.class);
    }

}
