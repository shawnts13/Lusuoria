# 第一阶段：构建（使用 Java 8）
FROM maven:3.6.3-openjdk-8 AS build
COPY . .
RUN mvn clean package -DskipTests

# 第二阶段：运行（使用 Java 8 运行时）
FROM openjdk:8-jdk-alpine
COPY --from=build /target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]