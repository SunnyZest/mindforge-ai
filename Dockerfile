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
# ⚠️ 堆上限 512M：4G 机器上还要同时跑 MySQL/Redis/Python，堆 1G 加元空间和
#    线程栈约 1.4G 常驻，会把机器拖进 swap（云盘 swap 很慢，GC 时尤其明显）。
#    需要调整时用环境变量覆盖，不要改这里：JAVA_OPTS="-Xmx768m"
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -Duser.timezone=Asia/Shanghai"

EXPOSE 8080

# 容器健康检查：探测认证接口的「未授权」响应（401）——
# 有响应就说明 Tomcat 和 Spring 都起来了。
# ⚠️ 刻意不探 swagger：生产 profile 把接口文档关了（避免暴露全部接口结构），
#    拿它当探针会在生产永远不健康。
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=40s \
  CMD curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/app/auth/me | grep -qE "^(200|401|403)$" || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
