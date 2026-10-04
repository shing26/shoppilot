package com.shoppilot.ticket.domain;

import com.shoppilot.tool.workitem.TicketSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.annotations.TenantId;

/**
 * 人工工单 —— **需要人工介入的统一实体**（ADR 0009 + ADR 0055，round23 票 69/70/72）。
 *
 * <p>转人工必须有可查证落点，否则是假功能；四种来路（降级 / 反馈复核 / 退款审批 / 渠道回执）
 * 共用这张表，所以分流规则表才有单一分母。
 *
 * <p><b>这张表随数据搬到本服务</b>（票 72，所有者裁定 B）。它原先在 biz-mock，
 * 而 H2 内存库在进程内——两个进程无法共库，所以「工单数据归谁」只能是「归这个服务」。
 *
 * <p><b>领取用条件更新而不是乐观锁版本号</b>：领取的语义是「把还没被领走的、且没结的单收下」，
 * 一条 {@code update ... where assignee is null and status <> 'RESOLVED'} 正好是这个语义，
 * 而 {@code @Version} 会把「谁先改谁赢」泛化到所有字段上——那对别的字段是多余的约束。
 */
@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @Column(name = "id", length = 40)
    private String id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "customer_id", nullable = false, length = 32)
    private String customerId;

    @Column(name = "reason", nullable = false, length = 40)
    private String reason;

    @Column(name = "user_query", nullable = false, length = 512)
    private String userQuery;

    @Lob
    @Column(name = "transcript", nullable = false, length = 8000)
    private String transcript;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 存储字面量 high/money/normal；领域名见 {@link TicketPriority}。 */
    @Column(name = "priority", length = 10)
    private String priority;

    /** 工单来源，非空；取值见 {@link TicketSource}。 */
    @Column(name = "source", nullable = false, length = 24)
    private String source;

    /** 分派到的队列；null = 尚未分派。 */
    @Column(name = "queue", length = 32)
    private String queue;

    /** 领取这张工单的坐席；null = 无人领取。 */
    @Column(name = "assignee", length = 32)
    private String assignee;

    /** SLA 截止时间；只用于计时与超时升级标记，不承诺解决时限。 */
    @Column(name = "sla_deadline")
    private Instant slaDeadline;

    /** 超时打戳时刻；非空即代表已被观测到超时，**不改工单状态**。 */
    @Column(name = "escalated_at")
    private Instant escalatedAt;

    /** 有上游记录时（退款审批）指回上游 id 的 JSON；自包含来源为 null。 */
    @Lob
    @Column(name = "payload", length = 2000)
    private String payload;

    /**
     * 买家当初问的渠道（round26 票 86 / ADR 0059）。
     *
     * <p>与 {@link #queue} 是两件事：队列是**系统内**分派到哪个技能组（买家看不见），
     * 渠道是**买家从哪儿进来**（决定结论送回哪儿）。null = 这张单没有渠道（复核单、退款审批单）。
     */
    @Column(name = "channel", length = 16)
    private String channel;

    /**
     * 投递目标：邮箱地址或 webhook 回调地址（round26 票 86 / ADR 0059）。
     *
     * <p><b>它是投递提示，不是隔离依据、更不是身份</b>：ADR 0005 的三条防线一条都不看它，
     * 它也不进缓存键、不参与会话归属。它只是「结论要送到哪儿去」。
     */
    @Column(name = "contact", length = 255)
    private String contact;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Ticket() {
    }

    public Ticket(String id, String tenantId, String customerId, String reason, String userQuery,
                  String transcript, String status, Instant createdAt, String priority, TicketSource source,
                  String queue, String assignee, Instant slaDeadline, String payload) {
        this.id = id;
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.reason = reason;
        this.userQuery = userQuery;
        this.transcript = transcript;
        this.status = status;
        this.createdAt = createdAt;
        this.priority = priority;
        this.source = source.name();
        this.queue = queue;
        this.assignee = assignee;
        this.slaDeadline = slaDeadline;
        this.payload = payload;
    }

    /**
     * 工单号：毫秒保证跨时间有序，进程内序列保证同一毫秒内不撞主键。
     *
     * <p>原来只用 "T + 毫秒 + 内容哈希"：大促压测里同一店铺同一句话在同一毫秒内落几十张单，
     * 内容一样、时间戳一样，工单号就撞在一张表的主键上，返回 500，降级链路直接断在终点
     * （2026-09-08 mix80 压测暴露，见 {@code TicketIdTest}）。
     *
     * <p>它住在实体上而不是某个服务里，是为了让「建一张工单」在四个服务之间只有一个实现。
     */
    private static final AtomicLong TICKET_SEQ = new AtomicLong();

    public static String nextId(Instant now) {
        return "T" + now.toEpochMilli() + "-" + Long.toUnsignedString(TICKET_SEQ.getAndIncrement(), 36);
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getReason() {
        return reason;
    }

    public String getUserQuery() {
        return userQuery;
    }

    public String getTranscript() {
        return transcript;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getPriority() {
        return priority;
    }

    public String getSource() {
        return source;
    }

    public String getQueue() {
        return queue;
    }

    public String getAssignee() {
        return assignee;
    }

    public Instant getSlaDeadline() {
        return slaDeadline;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public void setEscalatedAt(Instant escalatedAt) {
        this.escalatedAt = escalatedAt;
    }

    /**
     * 挂上渠道与投递目标，返回本对象（round26 票 86）。
     *
     * <p><b>为什么是方法而不是构造器的两个参数</b>：构造器已经有 13 个位置参数，
     * 再加两个都是 {@code String} 的相邻参数时，{@code channel} 与 {@code contact} 传反
     * **编译器不会报错**，而那等于把结论发到错误的地址去。命名方法让这个错误在读代码时就能看见。
     *
     * <p>两格都允许 null——没有渠道的工单（复核单、退款审批单）不填就是了。
     */
    public Ticket attachDelivery(String channel, String contact) {
        this.channel = channel == null || channel.isBlank() ? null : channel.trim();
        this.contact = contact == null || contact.isBlank() ? null : contact.trim();
        return this;
    }

    public String getPayload() {
        return payload;
    }

    public String getChannel() {
        return channel;
    }

    public String getContact() {
        return contact;
    }
}
