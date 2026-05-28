#!/bin/bash
set -e

JDK_HOME="${1:-/usr/lib/jvm/java-21-openjdk}"
SKIP_TESTS="${2:-false}"
QUIET_MAVEN="${3:-false}"

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

function write_warning() {
    echo -e "\n${YELLOW}⚠️ $1${NC}"
}

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)

write_info "开始构建 Agent Cloud Harness..."

write_info "步骤 1/3: 配置 Java 21 环境"
if ! source "$SCRIPT_DIR/Use-Java21.sh" "$JDK_HOME" "true"; then
    write_error "Java 环境配置失败" "\
1. 确保已安装 Java 21（必须启用 --enable-preview）
2. 检查 JDK_HOME 参数是否正确
3. 尝试手动运行: source scripts/Use-Java21.sh 查看详细错误"
fi
write_success "Java 环境配置完成"

write_info "步骤 2/3: 查找 Maven 可执行文件"
if ! command -v mvn &>/dev/null; then
    write_error "Maven 未找到" "\
1. 安装 Maven 3.9+:
   - Ubuntu/Debian: sudo apt install maven
   - macOS (Homebrew): brew install maven
   - 或从官网下载: https://maven.apache.org/download.cgi

2. 将 Maven 添加到系统 PATH"
fi
MAVEN_EXECUTABLE=$(command -v mvn)
write_success "找到 Maven: $MAVEN_EXECUTABLE"

write_info "步骤 3/3: 执行 Maven 构建"

MAVEN_ARGS=()
if [ "$QUIET_MAVEN" = "true" ]; then
    MAVEN_ARGS+=("-q")
fi
if [ "$SKIP_TESTS" = "true" ]; then
    MAVEN_ARGS+=("-DskipTests")
    write_warning "跳过测试模式已启用"
fi
MAVEN_ARGS+=("package")

echo -e "\n📦 执行命令: $MAVEN_EXECUTABLE ${MAVEN_ARGS[*]}"
echo "──────────────────────────────────────────────────────"

"$MAVEN_EXECUTABLE" "${MAVEN_ARGS[@]}"

if [ $? -ne 0 ]; then
    write_error "构建失败" "\
常见构建失败原因及解决方案：

1. **网络问题**
   - 检查网络连接
   - 配置 Maven 镜像（在 ~/.m2/settings.xml 中添加镜像）

2. **依赖冲突**
   - 运行: mvn clean package -DskipTests
   - 清理本地仓库: rm -rf ~/.m2/repository

3. **Java 版本问题**
   - 确保使用 Java 21（项目启用了 --enable-preview）
   - 运行: java -version 检查版本

4. **内存不足**
   - 设置环境变量: export MAVEN_OPTS=\"-Xmx2048m\"

5. **查看详细日志**
   - 重新运行并查看完整错误信息"
fi

echo "──────────────────────────────────────────────────────"
write_success "构建成功！"

OUTPUT_JAR="target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar"
if [ -f "$OUTPUT_JAR" ]; then
    write_success "构建产物: $OUTPUT_JAR"
else
    OUTPUT_JAR="target/agent-cloud-harness-0.1.0-SNAPSHOT.jar"
    if [ -f "$OUTPUT_JAR" ]; then
        write_success "构建产物: $OUTPUT_JAR"
    else
        write_warning "未找到预期的 JAR 文件"
    fi
fi

echo -e "\n📖 下一步操作：
- 启动服务: bash scripts/Run-HarnessWithJava21.sh
- 或使用快捷命令: bash scripts/Run-HarnessWithJava21.sh -p 8080

访问地址:
- Dialogue: http://localhost:8080/dialogue/
- Console: http://localhost:8080/console/
- Health: http://localhost:8080/api/v1/health"
