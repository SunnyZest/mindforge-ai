package com.zest.mindforgeai.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 内部接口配置，对应 application.yml 的 mindforge.internal.*
 * <p>
 * 这里的「内部接口」指 {@code /internal/**} —— 只给 Python AI 服务调的，
 * 浏览器永远不该访问到。
 *
 * @author crx
 * @since 2026-10-06
 */
@Data
@Component
@ConfigurationProperties(prefix = "mindforge.internal")
public class InternalApiProperties {

    /**
     * 服务间共享密钥，Python 请求时放在 {@code X-Service-Token} 头里。
     * <p>
     * ⚠️ 必须配置 —— 留空的话所有 {@code /internal/**} 请求都会被拒绝（fail-safe）。
     * 实际值放 application-dev.yml（已 gitignore），Java 和 Python 两边配成一样。
     * <p>
     * 生产环境应该再叠加网络隔离（Docker 内网 / 只允许内网访问），
     * 单靠一个静态 token 不够。
     */
    private String serviceToken;

    /** 单次最多认领多少个文档，防止 Python 一次拉太多把自己压垮 */
    private int claimLimit = 10;

    /**
     * 认领后多久没回写状态就认为处理方挂了（分钟），超时会被定时任务收回重派。
     * <p>
     * 要大于单个文档最长的处理时间，否则会出现「还在处理就被别人抢走」。
     */
    private int claimTimeoutMinutes = 30;
}
