package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.PromptCreateDTO;
import com.zest.mindforgeai.pojo.dto.PromptUpdateDTO;
import com.zest.mindforgeai.pojo.vo.PromptGroupVO;
import com.zest.mindforgeai.pojo.vo.PromptVO;

import java.util.List;

/**
 * 管理端：提示词管理。
 *
 * <p>★ 三层结构：{@code scene + name} 是<b>变体</b>（用户切换的单位），
 * {@code version} 是<b>历史版本</b>（发布/回滚的单位）。
 * 同一个 (scene, name) 再新建就是加版本，想加变体就换个 name。
 *
 * @author crx
 * @since 2026-10-10
 */
public interface AdminPromptService {

    /** 按变体分组列出（含每个变体的全部历史版本）。{@code scene} 为空则列全部。 */
    List<PromptGroupVO> listGroups(String scene);

    /** 新建一个版本（同一变体自动 version + 1），初始状态是 DRAFT。 */
    PromptVO create(PromptCreateDTO dto);

    /** 改一个草稿。已发布的拒绝 —— 要改就新建版本。 */
    PromptVO update(Long id, PromptUpdateDTO dto);

    /** 发布：DRAFT → PUBLISHED，同一变体下原来那版 → ARCHIVED。 */
    PromptVO publish(Long id);

    /** 回滚：ARCHIVED → PUBLISHED，当前那版 → ARCHIVED。 */
    PromptVO rollback(Long id);

    /** 删除。已发布的拒绝 —— 先回滚到别的版本再删。 */
    void delete(Long id);
}
