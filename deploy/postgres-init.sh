#!/bin/bash
# ShopPilot 持久档的第二个库 + 按库用户隔离（round29 票 105 / ADR 0062）
#
# Postgres 镜像的 init 脚本只在**数据目录为空的首次初始化**时执行——这是「两库与两个角色的出生证明」，
# 不是每次启动的保障。
#
# **升级 runbook（B1 时代已初始化的卷）**：init 不会再跑，二选一——
#   ① 重建卷（演练数据可弃时）：`docker compose rm -sf postgres && docker volume rm <project>_postgres-data`
#      然后 `docker compose up -d postgres`；
#   ② 对既有实例手工执行下面 EOSQL 里的每一段（角色是集群级的，ALTER/GRANT 幂等）。
#
# 角色模型（ADR 0062 §2）：bizmock_app / ticket_app **各自拥有**自己的库（Flyway 以 owner 身份跑迁移）；
# PUBLIC 的 CONNECT 被收走，按角色授回——隔离判据是**跨库连接被 FATAL 拒绝**，不是权限位看起来对。
# 两个内部用户刻意共享同一个口令（SHOPPILOT_DB_PASSWORD）：隔离的是权限不是口令唯一性，
# 残余风险登记在 ADR 0062 §2。口令里不要带单引号（会破坏下面的 SQL 字面量）。
set -e
: "${SHOPPILOT_DB_PASSWORD:?SHOPPILOT_DB_PASSWORD must be set (compose passes it into this container)}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
CREATE DATABASE ticket;
REVOKE CONNECT ON DATABASE bizmock FROM PUBLIC;
REVOKE CONNECT ON DATABASE ticket FROM PUBLIC;
CREATE ROLE bizmock_app LOGIN PASSWORD '${SHOPPILOT_DB_PASSWORD}';
CREATE ROLE ticket_app LOGIN PASSWORD '${SHOPPILOT_DB_PASSWORD}';
GRANT CONNECT ON DATABASE bizmock TO bizmock_app;
GRANT CONNECT ON DATABASE ticket TO ticket_app;
ALTER DATABASE bizmock OWNER TO bizmock_app;
ALTER DATABASE ticket OWNER TO ticket_app;
EOSQL
