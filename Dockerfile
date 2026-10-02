# 四个域服务的镜像（round23 票 76 / ADR 0053、ADR 0020）
#
# **一份 Dockerfile + 一个 build arg**，不是四份：四个服务是同构的（同一 JDK、同一 Spring Boot、
# 同一份 logback 配置），复制四份只会在某一次改动里漏掉另外三个。
#
# 构建走**仓库自己的 ./mvnw**：wrapper 版本因此被钉住，与本仓「用 wrapper 不用本机 Maven」
# 的一致性同源。runtime 用 JRE 镜像，`.env`/`logs/`/`target/` 由 .dockerignore 挡在外面。
#
# 用法：
#   docker build --build-arg MODULE=shoppilot-gateway -t shoppilot/gateway:local .
#   docker build --build-arg MODULE=shoppilot-biz-mock -t shoppilot/biz-mock:local .

# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

# 先只拷 wrapper 与 pom，让依赖层能被缓存：只改 Java 源码时这一层不失效
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline -DexcludeArtifactIds=shoppilot-gateway || true

# 再拷源码并打 fat jar。MODULE 决定打哪个模块。
ARG MODULE
ENV MODULE=${MODULE}
COPY . /workspace
RUN ./mvnw -B -ntp -pl ${MODULE} -am -DskipTests package \
    && ls -l /workspace/${MODULE}/target/*.jar \
    && cp "$(ls /workspace/${MODULE}/target/*.jar | grep -v original | head -1)" /workspace/app.jar

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre

# 堆上限在 compose 里按容器的 mem_limit 给（MaxRAMPercentage），
# 这里只给兜底：**别让一个没配上限的容器把宿主内存吃光**——那正是这一档要解决的失败模式。
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

WORKDIR /workspace
COPY --from=build /workspace/app.jar /workspace/app.jar

# 不用 root 跑服务；容器日志走 stdout，由 compose 收
RUN useradd --system --uid 10001 app && chown -R app:app /workspace
USER app

EXPOSE 8082 8091 8092
ENTRYPOINT ["java", "-jar", "/workspace/app.jar"]