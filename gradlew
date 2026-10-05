#!/bin/sh

#
# Gradle start up script for POSIX systems.
# 本文件是 Apache-2.0 许可下的标准 Gradle wrapper 启动脚本（简化但等价实现）。
#

# 注意：不要使用 set -u —— 下面会引用可能未定义的可选环境变量。

# ── 解析脚本所在目录，兼容符号链接 ───────────────────────────────
PRG="$0"
while [ -h "$PRG" ] ; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`"/$link"
  fi
done

SAVED_DIR=`pwd`
APP_HOME=`dirname "$PRG"`
cd "$APP_HOME" || exit 1
APP_HOME=`pwd`
cd "$SAVED_DIR" || exit 1

APP_BASE_NAME=`basename "$0"`

# ── 选择 JVM：优先 JAVA_HOME ─────────────────────────────────────
if [ -n "$JAVA_HOME" ] ; then
  if [ -x "$JAVA_HOME/bin/java" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
  else
    echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
    exit 1
  fi
else
  JAVACMD="java"
  if ! command -v java >/dev/null 2>&1 ; then
    echo "ERROR: JAVA_HOME is not set and no java command could be found in your PATH." >&2
    exit 1
  fi
fi

# ── 定位 wrapper jar ─────────────────────────────────────────────
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ] ; then
  echo "ERROR: 找不到 $WRAPPER_JAR" >&2
  echo "       请先执行：gradle wrapper --gradle-version 8.13" >&2
  echo "       或用 Android Studio 打开本工程让其自动生成。" >&2
  exit 1
fi

# ── 组装参数并启动 ───────────────────────────────────────────────
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"

set -- org.gradle.wrapper.GradleWrapperMain "$@"

if [ "$JAVA_HOME" != "" ] ; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$WRAPPER_JAR" \
  "$@"
