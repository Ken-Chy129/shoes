#!/usr/bin/env bash
# 本机默认 JDK 为 26，与项目 <source>21</source> + --enable-preview 冲突
# （javac 26 只允许 --enable-preview 搭配 release 26）。
# 这里强制用本机已安装的 JDK 21 运行 Maven，免去每次手动 export JAVA_HOME。
# 注意：生产部署走 Docker(maven:3.9-eclipse-temurin-21)，构建容器内本就是 JDK21，不受影响；
#       所以 JDK 固化只放在本地脚本，不写进 pom，以免污染 Docker 构建。
set -euo pipefail

JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
if [[ -z "${JAVA_HOME}" ]]; then
  echo "未找到 JDK 21，请先安装（当前可用 JDK：）" >&2
  /usr/libexec/java_home -V >&2 || true
  exit 1
fi
export JAVA_HOME
echo "[build.sh] 使用 JAVA_HOME=${JAVA_HOME}" >&2
exec mvn "$@"
