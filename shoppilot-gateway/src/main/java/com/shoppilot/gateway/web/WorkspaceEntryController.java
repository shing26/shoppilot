package com.shoppilot.gateway.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 坐席工作台的入口转发（round23 票 75，清场日实测补的）。
 *
 * <p>为什么需要它：产物落在 {@code static/workspace/}，Spring Boot 只在**根路径**
 * 解析 welcome page——{@code /} 能自动落到 {@code index.html}，{@code /workspace/} 不能，
 * 直接 404。所以人得知道那个 {@code index.html} 的文件名才能打开页面，
 * 而「打开工作台」不该要求人记得文件名。
 *
 * <p>刻意只做转发、不做模板或重写：产物是 Vite 编译出来的静态文件，转发过去之后
 * 资源解析、缓存头、base 路径全都仍由 Spring 的静态资源处理负责，这一层不插手。
 */
@Controller
public class WorkspaceEntryController {

    /** 两个路径都收：`/workspace`（浏览器常把尾斜杠去掉）与 `/workspace/`。 */
    @GetMapping({"/workspace", "/workspace/"})
    public String openWorkspace() {
        return "forward:/workspace/index.html";
    }
}