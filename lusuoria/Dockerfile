# 第一阶段：构建 (使用包含Maven和JDK8的镜像)
FROM maven:3.6.3-openjdk-8 AS build

# 复制项目文件
COPY . .

# 执行打包命令，跳过测试
RUN mvn clean package -DskipTests

# 第二阶段：运行 (使用Eclipse Temurin的JRE8镜像，基于轻量级Alpine Linux)
FROM eclipse-temurin:8-jre

# 从构建阶段复制生成的jar包
COPY --from=build /target/*.jar app.jar

# 暴露应用端口
EXPOSE 8080

# 设置启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]