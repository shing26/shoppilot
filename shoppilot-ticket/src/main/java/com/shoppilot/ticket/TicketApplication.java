package com.shoppilot.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 工单与坐席服务（round23 票 72 / ADR 0053）。
 *
 * <p>它是四个域服务里的第四个：对话网关、知识服务、业务与工具服务之外，**人工这一侧独立成进程**。
 * 理由是负载形态不同——实时对话是毫秒级、人工处理是分钟到小时级，
 * 塞在同一进程里会让 SLA 计时和坐席领取与实时链路抢资源。
 *
 * <p>{@code tickets} 与 {@code routing_rules} 两张表随数据搬到这里（所有者裁定 B）：
 * H2 内存库是**进程内**的，两个进程物理上无法共库，所以「工单数据归谁」只能是「归这个服务」。
 */
@SpringBootApplication
public class TicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketApplication.class, args);
    }
}