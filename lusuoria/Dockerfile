# 第一阶段：构建
FROM maven:3.6.3-openjdk-8 AS build
COPY . .
RUN mvn clean package -DskipTests

# 第二阶段：运行（使用 eclipse-temurin，官方推荐的 Java 8 镜像）
FROM eclipse-temurin:8-jre-alpine
COPY --from=build /target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]