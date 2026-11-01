-- round43 票 185（ADR 0078）：知识修订（round19 登记第 8 项「知识反向沉淀」转执行）。
--
-- 修订稿由人工撰写、人工发布（ADR 0039 红线：不做自动改写）；本表是那条链的落库端。
-- 复核前置在服务层钉住：只有 REVIEWED 的反馈才允许附稿。
--
-- 列名避坑：domain / scope 在 H2 2.x 是关键字，列名用 knowledge_domain / revision_scope，
-- 实体字段名保持领域叫法（@Column 显式映射）。
-- body 是有界 varchar（服务层限 20000 字）而不是大对象：ingest 拉取口走 native 投影，
-- CLOB/oid 在投影里要专门解引用（round28 的 @Lob/oid 矩阵正是为实体加载调的），
-- 有界文本两侧方言一致、投影直读，不值得为它背 LOB 的账。
create table knowledge_revisions (
    id varchar(40) not null,
    tenant_id varchar(32) not null,
    feedback_id varchar(40) not null,
    title varchar(200) not null,
    body varchar(20000) not null,
    intent varchar(32) not null,
    knowledge_domain varchar(16) not null,
    revision_scope varchar(16) not null,
    status varchar(16) not null,
    created_by varchar(64),
    actor_authenticated boolean not null default false,
    created_at timestamp(6) with time zone not null,
    published_at timestamp(6) with time zone,
    rejected_at timestamp(6) with time zone,
    primary key (id)
);
create index idx_knowledge_revision_status on knowledge_revisions (status);
