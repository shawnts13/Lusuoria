# 第一阶段：构建
FROM maven:3.6.3-openjdk-8 AS build
COPY . .
RUN mvn clean package -DskipTests

# 第二阶段：运行
FROM openjdk:8-jre-slim
COPY --from=build /target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]