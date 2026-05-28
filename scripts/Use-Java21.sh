#!/bin/bash
set -e

JDK_HOME="${1:-/usr/lib/jvm/java-21-openjdk}"
QUIET="${2:-false}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

function write_error() {
    echo -e "\n${RED}❌ ERROR: $1${NC}"
    if [ -n "$2" ]; then
        echo -e "\n${CYAN}💡 解决方案：${NC}"
        echo -e "${YELLOW}$2${NC}"
    fi
    echo ""
    exit 1
}

function write_info() {
    echo -e "\n${CYAN}ℹ️ $1${NC}"
}

function write_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

write_info "正在配置 Java 21 环境..."

if [ ! -d "$JDK_HOME" ]; then
    write_error "Java 21 安装目录不存在: $JDK_HOME" "\
1. 请先安装 Java 21：
   - Ubuntu/Debian: sudo apt install openjdk-21-jdk
   - macOS (Homebrew): brew install openjdk@21
   - 或使用 SDKMAN (推荐): https://sdkman.io/

2. 安装完成后，您有以下选择：
   - 选项A: 使用默认路径安装（推荐）
     Ubuntu: /usr/lib/jvm/java-21-openjdk
     macOS (Homebrew): /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   
   - 选项B: 指定自定义路径
     使用命令: source scripts/Use-Java21.sh /path/to/jdk-21

3. 验证安装：
   运行: java -version
   应显示: openjdk version \"21.x.x\""
fi

JAVA_EXE="$JDK_HOME/bin/java"
if [ ! -f "$JAVA_EXE" ]; then
    write_error "Java 可执行文件不存在: $JAVA_EXE" "\
JDK 目录存在，但缺少 java 文件。
可能是安装不完整或路径不正确。

建议：
1. 重新安装 Java 21
2. 确认 JDK_HOME 参数指向正确的 JDK 安装目录（不是 JRE）"
fi

export JAVA_HOME="$JDK_HOME"
JDK_BIN="$JDK_HOME/bin"

# 从 PATH 中移除旧的 Java 路径，然后在最前面添加新路径
export PATH=$(echo "$PATH" | tr ':' '\n' | grep -v "$JDK_BIN" | tr '\n' ':' | sed 's/:$//')
export PATH="$JDK_BIN:$PATH"

# 清理 CLASSPATH 避免冲突
if [ -n "$CLASSPATH" ]; then
    export AGENTCLOUD_PREVIOUS_CLASSPATH="$CLASSPATH"
    unset CLASSPATH
fi

if [ "$QUIET" != "true" ]; then
    write_success "JAVA_HOME 已设置为: $JAVA_HOME"
    
    if JAVA_VERSION=$($JAVA_EXE -version 2>&1 | head -1); then
        write_success "Java 版本: $JAVA_VERSION"
    else
        write_info "已设置 Java 环境，但版本检测失败"
    fi
    
    if [ -n "$AGENTCLOUD_PREVIOUS_CLASSPATH" ]; then
        write_info "已清理继承的 CLASSPATH，避免旧 JDK/runtime 冲突"
    fi
    
    echo -e "\n📖 使用提示：
- 要在当前 shell 中保持此环境，请使用 source 命令：
  source scripts/Use-Java21.sh
  
- 如需指定自定义 JDK 路径：
  source scripts/Use-Java21.sh /path/to/jdk-21

- 验证环境是否正确：
  java -version"
fi
