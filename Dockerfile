# ==============================================================================
# MindForge AI Java 后端 Dockerfile
# 多阶段构建：Eclipse Temurin JDK 21 构建 -> Eclipse Temurin JRE 21 运行
# ==============================================================================

# ----------------- 阶段 1：构建应用 -----------------
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# 复制 Maven 包装器与项目描述符
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# 预拉取依赖缓存（网络失败不中断，后续 compile 会重试）
RUN ./mvnw dependency:go-offline -B || true

# 复制源码并构建（跳过测试）
COPY src/ src/
RUN ./mvnw clean package -DskipTests

# ----------------- 阶段 2：最小化运行环境 -----------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 设置时区与基础工具
RUN apk add --no-cache curl tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 从构建阶段复制 jar
COPY --from=builder /build/target/mindforge-ai-*.jar app.jar

# JVM 内存与时区参数
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -Duser.timezone=Asia/Shanghai"

EXPOSE 8080

# 容器健康检查（基于 Knife4j/Swagger 探测）
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=30s \
  CMD curl -f http://localhost:8080/api/swagger-ui.html || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
