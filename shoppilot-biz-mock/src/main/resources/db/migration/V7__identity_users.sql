-- round25 票 80：身份域的账号表（ADR 0056 / ADR 0058）
--
-- 身份域由本服务承载（ADR 0058 第 1 条），所以 users 表在这里而不在网关——
-- 网关目前没有数据源，给全栈最重、延迟最敏感的那个进程挂一个数据库栈不划算。
--
-- 唯一约束按 (tenant_id, username) 而不是全局 username：跨店同名是正常业务，
-- 全局唯一等于逼所有店共用一个命名空间。登录因此是**租户级**路径（租户走请求头），
-- 换来的是这张表能照常挂 @TenantId 租户过滤器，不必给 ADR 0005 开例外。
create table users (
    id varchar(32) not null,
    tenant_id varchar(32) not null,
    username varchar(64) not null,
    -- BCrypt 输出固定 60 字符（$2a$10$...），给到 100 是为了将来换 cost 时不必再改列宽
    password_hash varchar(100) not null,
    role varchar(16) not null,
    -- 账号动作的对象：买家指向 customers.id，坐席与管理员为空
    subject_ref varchar(32),
    display_name varchar(64),
    status varchar(16) not null,
    created_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_users_tenant_username unique (tenant_id, username)
);

-- 查询面一：登录按 (tenant_id, username) 命中唯一约束即可，不另建索引。
-- 查询面二：按角色列账号（管理面看「这家店有几个坐席」），走这条。
create index idx_users_tenant_role on users (tenant_id, role);