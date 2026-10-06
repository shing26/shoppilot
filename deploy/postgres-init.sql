-- ShopPilot 持久档的第二个库（round28 票 102 / ADR 0061）
--
-- POSTGRES_DB 环境变量只建 bizmock；ticket 库在这里补。Postgres 镜像的 init 脚本
-- 只在数据目录为空的首次初始化时执行，之后的容器重启/重建不会再跑——
-- 所以这里是「两库的出生证明」，不是每次启动的保障。
CREATE DATABASE ticket;
