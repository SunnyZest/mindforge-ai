package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.vo.ConfirmTokenVO;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 高危操作二次确认服务。
 * <p>
 * 保护高危操作（删除用户、删除角色、删除知识库、修改/删除模型配置、回滚提示词等），
 * 防范仅凭 Bearer Token 带来的误触、被借用或 Token 泄露即破坏系统的风险。
 *
 * @author crx
 * @since 2026-10-11
 */
public interface SecondaryConfirmService {

    /** 请求头名称：二次确认 Token */
    String HEADER_CONFIRM_TOKEN = "X-Confirm-Token";

    /** 请求头名称：直接传入当前用户密码（备选简易模式） */
    String HEADER_CONFIRM_PASSWORD = "X-Confirm-Password";

    /**
     * 校验当前登录用户密码，生成 5 分钟有效的单次确认凭证。
     *
     * @param password 当前操作者的登录密码
     * @return 包含 confirmToken 与有效期的响应
     */
    ConfirmTokenVO issueConfirmToken(String password);

    /**
     * 在高危操作前执行二次确认校验。
     * 优先检查 {@code X-Confirm-Token}，其次检查 {@code X-Confirm-Password}。
     *
     * @param request 当前 HTTP 请求
     */
    void validateAndConsume(HttpServletRequest request);
}
