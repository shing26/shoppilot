package com.shoppilot.gateway.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 坐席工作台与买家中心的入口转发（round23 票 75 清场日实测补的；round27 票 93 扩到两处）。
 *
 * <p>为什么需要它：产物落在 {@code static/workspace/} 与 {@code static/buyer/}，
 * 而 Spring Boot 只在**根路径**解析 welcome page——{@code /} 能自动落到 {@code index.html}，
 * {@code /workspace/} 与 {@code /buyer/} 不能，直接 404。所以人得知道那个 {@code index.html}
 * 的文件名才能打开页面，而「打开工作台」不该要求人记得文件名。
 *
 * <p>两处放在一起而不是两个类：它们是同一条规则的两个实例，分成两个类只会让下一个入口
 * 再长出一个类，而那正是清场日抓到过的那类洞的温床（改一处忘一处）。
 *
 * <p>刻意只做转发、不做模板或重写：产物是 Vite 编译出来的静态文件，转发过去之后
 * 资源解析、缓存头、base 路径全都仍由 Spring 的静态资源处理负责，这一层不插手。
 */
@Controller
public class StaticEntryController {

    /** 两个路径都收：`/workspace`（浏览器常把尾斜杠去掉）与 `/workspace/`。 */
    @GetMapping({"/workspace", "/workspace/"})
    public String openWorkspace() {
        return "forward:/workspace/index.html";
    }

    /** 买家中心，同一套理由（round27 票 93）。 */
    @GetMapping({"/buyer", "/buyer/"})
    public String openBuyer() {
        return "forward:/buyer/index.html";
    }
}