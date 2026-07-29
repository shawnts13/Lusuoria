FROM maven:3.6.3-openjdk-8 AS build

WORKDIR /app

# 使用Render提供的缓存目录（免费版也支持）
ENV MAVEN_OPTS="-Dmaven.repo.local=$XDG_CACHE_HOME/maven/repository"

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:8-jre
# 数据库每日全量备份需要 pg_dump（DbBackupService，2026-07-29 新增）：eclipse-temurin 基础镜像
# 自带的 Ubuntu 默认仓库版本可能比 Supabase 的 Postgres 版本旧（pg_dump 不支持"客户端版本低于
# 服务端版本"这种组合），这里加 PGDG 官方仓库装一个较新的 17 版客户端，兼容范围更宽
RUN apt-get update && apt-get install -y --no-install-recommends \
      wget gnupg ca-certificates \
    && wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor -o /usr/share/keyrings/postgresql.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/postgresql.gpg] http://apt.postgresql.org/pub/repos/apt $(cat /etc/os-release | grep -oP '(?<=VERSION_CODENAME=).+')-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update && apt-get install -y --no-install-recommends postgresql-client-17 \
    && apt-get purge -y wget gnupg \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# Render 免费版容器总内存只有 512MB：不显式限制的话 JVM 有时会把堆/元空间/代码缓存
# 撑到超过容器上限，被 OOM Killer 直接杀掉（exit 137）。这里给堆/元空间/代码缓存都
# 留一个明确的、留有余量的上限，线程栈也调小一点，把整体内存占用压在 512MB 以内。
ENTRYPOINT ["java", \
  "-Xms128m", "-Xmx320m", \
  "-XX:MaxMetaspaceSize=128m", \
  "-XX:ReservedCodeCacheSize=64m", \
  "-XX:+UseSerialGC", \
  "-Xss256k", \
  "-jar", "app.jar"]