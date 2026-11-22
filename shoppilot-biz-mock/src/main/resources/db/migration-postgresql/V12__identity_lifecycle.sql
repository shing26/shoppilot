-- round56 票 224 / ADR 0086：身份生命周期（PostgreSQL 侧）
--
-- 与 H2 侧逐字同义；两侧版本钟由 PostgresMigrationParityTest 钉住。
alter table users add column password_version integer not null default 0;
alter table users add column failed_attempts integer not null default 0;
alter table users add column locked_until timestamp(6) with time zone;
alter table users add column last_failed_at timestamp(6) with time zone;

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
    primary key (id),
    constraint uk_refresh_token_hash unique (token_hash)
);

create index idx_refresh_family on refresh_tokens (tenant_id, family_id);
create index idx_refresh_account on refresh_tokens (tenant_id, account_id);
