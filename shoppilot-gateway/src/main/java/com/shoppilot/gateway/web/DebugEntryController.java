package com.shoppilot.gateway.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 调试台的显式入口（round37）。
 *
 * <p>根路径现在是产品导航页；调试台本体仍是无框架的单文件控制台，
 * 从 {@code /console.html} 提供，并留 {@code /admin/debug} 这个“有权限标记”的别名。
 * **静态页本身在所有部署都可读**（与旧 `index.html` 同一暴露面）；真正的写动作
 * （故障注入、清缓存、退款）仍分别由 ops token 与 staff 令牌把门，控制台不是鉴权边界。
 */
@Controller
public class DebugEntryController {

    @GetMapping({"/admin/debug", "/admin/debug/"})
    public String debugConsole() {
        return "redirect:/console.html";
    }
}
