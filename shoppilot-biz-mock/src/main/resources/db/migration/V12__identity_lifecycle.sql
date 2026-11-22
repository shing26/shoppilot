-- round56 票 224 / ADR 0086：身份生命周期
--
-- 三列落在 users 上而不是另起一张计数表：锁定判定与口令校验读的是同一行，
-- 拆表就多一次往返，还会让「口令改了但计数没清」这类不一致有地方藏。
-- password_version 进 access token，改密后旧令牌最迟在短 TTL 内失效。
alter table users add column password_version integer not null default 0;
alter table users add column failed_attempts integer not null default 0;
alter table users add column locked_until timestamp(6) with time zone;
alter table users add column last_failed_at timestamp(6) with time zone;

-- refresh token 只存 SHA-256 哈希：库被读走也换不出可用凭证。
-- family_id 把一次登录派生出的整条轮换链绑在一起，复用检测撤销的是整族而不是单张。
create table refresh_tokens (
    id varchar(40) not null,
    tenant_id varchar(32) not null,
    family_id varchar(40) not null,
    account_id varchar(32) not null,
    token_hash varchar(64) not null,
    issued_at timestamp(6) with time zone not null,
    expires_at timestamp(6) with time zone not null,
    used_at timestamp(6) with time zone,
    revoked_at timestamp(6) with time zone,
    replaced_by varchar(40),
    password_version integer not null default 0,
    role varchar(16) not null default 'BUYER',
    primary key (id),
    constraint uk_refresh_token_hash unique (token_hash)
);

create index idx_refresh_family on refresh_tokens (tenant_id, family_id);
create index idx_refresh_account on refresh_tokens (tenant_id, account_id);
