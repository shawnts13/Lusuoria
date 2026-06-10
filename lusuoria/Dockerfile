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
ENTRYPOINT ["java", "-jar", "app.jar"]