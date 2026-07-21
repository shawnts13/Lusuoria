FROM maven:3.6.3-openjdk-8 AS build

WORKDIR /app

# 使用Render提供的缓存目录（免费版也支持）
ENV MAVEN_OPTS="-Dmaven.repo.local=$XDG_CACHE_HOME/maven/repository"

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:8-jre
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