# PolyTable —— 多阶段构建：Maven 构建 + JRE 运行
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/polytable-*.jar app.jar

# 所有凭据通过环境变量注入（见 README 关键环境变量表）
ENV DB_URL=jdbc:mysql://mysql:3306/polytable?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
EXPOSE 8080
# 备注不用 wget/curl（temurin 新基础镜像已不再自带），bash /dev/tcp 做 TCP 探测
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
  CMD bash -c '(exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
