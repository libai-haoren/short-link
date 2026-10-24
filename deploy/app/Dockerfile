# =====================================================================
#  应用镜像（admin / redirect 共用）
#
#  构建上下文是仓库根目录，通过 MODULE 参数决定拷哪个 jar：
#    docker build -f deploy/app/Dockerfile --build-arg MODULE=shortlink-admin .
#
#  前置条件：宿主机已执行 mvn -DskipTests package
#  不用多阶段构建的原因：多阶段每次都要重新下载 Maven 依赖，
#  本地反复构建时慢得离谱。CI 里可以另写一份多阶段版本。
# =====================================================================
FROM eclipse-temurin:17-jre-alpine

ARG MODULE
ENV TZ=Asia/Shanghai

# 非 root 运行。容器被攻破时，能不能写文件系统是两回事。
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

# 用通配符避免把 1.0.0-SNAPSHOT 这种版本号写死在 Dockerfile 里
COPY ${MODULE}/target/*.jar /app/app.jar

RUN chown -R app:app /app
USER app

# 跳转服务是延迟敏感型，默认开 ZGC；可通过 JAVA_OPTS 覆盖
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseZGC"

# 用 sh -c 是为了让 $JAVA_OPTS 能展开；exec 保证 java 是 1 号进程，能收到 SIGTERM
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
