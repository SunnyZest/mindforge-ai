package com.zest.mindforgeai.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * @author crx
 * @since 2026-10-04
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // ① 分页插件
        //    必须注册：否则 page() 不会生成 LIMIT 子句，会「静默返回全部数据」
        //    —— 不报错、结果还"看起来正常"，是 MP 最容易踩的坑之一。
        //    指定 DbType 让方言更准确（否则 MP 每次去探测数据库类型）。
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        // ② 防全表更新 / 删除插件
        // 拦截没有 WHERE 条件的 UPDATE / DELETE，直接抛异常。
        // 本项目的表都没有外键、且大量用自定义 SQL，加一层兜底更安全。
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }
}
