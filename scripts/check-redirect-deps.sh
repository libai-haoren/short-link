#!/usr/bin/env bash
#
# redirect 依赖红线检查：Servlet 栈、数据库驱动、公共 Servlet 模块都不许进依赖树。
#
# "redirect 无 DB 依赖"是架构约束，不能靠自觉。只要有人加了 spring-boot-starter-web /
# mybatis / mysql-connector，依赖树里就会出现对应条目，这里立刻失败。
#
# 为什么用 ":artifact:" 而不是裸字符串匹配：spring-boot-starter-web 是
# spring-boot-starter-webflux 的前缀，裸匹配会把 WebFlux 自己判成违规。
#
# 用法：bash scripts/check-redirect-deps.sh
#       MVN_OFFLINE=1 bash scripts/check-redirect-deps.sh   # 本地离线
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="shortlink-redirect"
REPORT="$ROOT/$MODULE/target/dependency-tree.txt"

echo "==> 解析 $MODULE 的依赖树 ..."
cd "$ROOT"
if [ "${MVN_OFFLINE:-0}" = "1" ]; then
  mvn -o -B -q -pl "$MODULE" -am dependency:tree -DoutputFile=target/dependency-tree.txt
else
  mvn -B -q -pl "$MODULE" -am dependency:tree -DoutputFile=target/dependency-tree.txt
fi

if [ ! -f "$REPORT" ]; then
  echo "没有生成依赖树文件：$REPORT" >&2
  exit 2
fi

violations=0
while IFS='|' read -r token why; do
  [ -z "$token" ] && continue
  case "$token" in \#*) continue ;; esac
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    echo "  $line"
    echo "    原因：$why"
    violations=$((violations + 1))
  done <<EOF
$(grep -F -- "$token" "$REPORT" || true)
EOF
done <<'RULES'
:spring-boot-starter-web:|Servlet 栈：会同时引入 Tomcat 与 MVC，和 WebFlux 冲突
:spring-webmvc:|Servlet 栈：MVC 的 DispatcherServlet
:tomcat-|Servlet 容器：两个 Web 栈并存会导致启动失败
:mybatis|持久层：redirect 不允许连 DB
:mysql-connector|JDBC 驱动：redirect 不允许连 DB
:spring-boot-starter-jdbc:|JDBC 自动配置：说明有 DB 依赖
:HikariCP:|连接池：说明有 DB 依赖
:shortlink-common-web:|公共 Servlet 模块：它带 starter-web
RULES

if [ "$violations" -gt 0 ]; then
  echo ""
  echo "FAIL: redirect 的依赖树里出现了红线依赖（$violations 处）" >&2
  echo "这条约束写在 $MODULE/pom.xml 的注释里，也写在设计文档 3.2。" >&2
  echo "一旦破掉，redirect 就不再是「可以无脑扩副本的纯读服务」。" >&2
  exit 1
fi

echo "PASS: $MODULE 依赖树干净（$(wc -l < "$REPORT" | tr -d ' ') 行，无 Servlet / DB 依赖）"
