#!/bin/bash
set -e

JDK_HOME="/usr/lib/jvm/java-21-openjdk"
JAR_PATH=""
PORT=8080
BACKGROUND=false
STD_OUT_PATH=".tmp/server.out.log"
STD_ERR_PATH=".tmp/server.err.log"
JAVA_ARGS=()

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

function resolve_harness_jar() {
    local requested_path="$1"
    
    if [ -n "$requested_path" ]; then
        if [ ! -f "$requested_path" ]; then
            write_error "指定的 JAR 文件不存在: $requested_path" "\
请确认路径正确，或先执行构建：
bash scripts/Build-WithJava21.sh true"
        fi
        echo "$(realpath "$requested_path")"
        return
    fi

    local candidates=(
        "target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar"
        "target/agent-cloud-harness-0.1.0-SNAPSHOT.jar"
    )

    for candidate in "${candidates[@]}"; do
        if [ -f "$candidate" ]; then
            echo "$(realpath "$candidate")"
            return
        fi
    done

    write_error "未找到可运行的 Harness JAR 文件" "\
已检查以下位置：
- ${candidates[*]}

解决方案：
1. 先执行构建命令：
   bash scripts/Build-WithJava21.sh true

2. 或指定自定义 JAR 路径：
   bash scripts/Run-HarnessWithJava21.sh -j /path/to/your.jar"
}

function new_runtime_jar_copy() {
    local source_jar="$1"
    local port_number="$2"
    
    local runtime_dir=".tmp/runtime-jars"
    mkdir -p "$runtime_dir"
    
    local jar_name=$(basename "$source_jar" .jar)
    local timestamp=$(date +"%Y%m%d-%H%M%S")
    local runtime_jar="$runtime_dir/${jar_name}-port${port_number}-${timestamp}.jar"
    cp "$source_jar" "$runtime_jar"
    echo "$(realpath "$runtime_jar")"
}

function assert_port_available() {
    local port_number="$1"
    
    if lsof -Pi :"$port_number" -sTCP:LISTEN -t >/dev/null 2>&1; then
        local pid=$(lsof -Pi :"$port_number" -sTCP:LISTEN -t | head -1)
        local cmd=$(ps -p "$pid" -o command= 2>/dev/null || echo "<unavailable>")
        
        write_error "端口 $port_number 已被占用" "\
占用端口的进程信息：
- PID: $pid
- 命令行: $cmd

解决方案：
1. 终止占用进程：
   kill -9 $pid

2. 使用其他端口启动：
   bash scripts/Run-HarnessWithJava21.sh -p 8081

3. 查找并终止进程：
   lsof -Pi :$port_number
   kill -9 <PID>"
    fi
}

function print_usage() {
    echo -e "\n${CYAN}使用方法:${NC} $0 [选项]"
    echo -e "\n${YELLOW}选项:${NC}"
    echo "  -j, --jar <path>        指定 JAR 文件路径"
    echo "  -p, --port <number>     指定服务端口（默认: 8080）"
    echo "  -b, --background        后台模式运行"
    echo "  -o, --stdout <path>     标准输出日志路径（默认: .tmp/server.out.log）"
    echo "  -e, --stderr <path>     标准错误日志路径（默认: .tmp/server.err.log）"
    echo "  -a, --java-args <args>  额外的 Java 参数"
    echo "  -h, --help              显示帮助信息"
    echo ""
}

# 解析命令行参数
while [[ $# -gt 0 ]]; do
    case "$1" in
        -j|--jar)
            JAR_PATH="$2"
            shift 2
            ;;
        -p|--port)
            PORT="$2"
            shift 2
            ;;
        -b|--background)
            BACKGROUND=true
            shift
            ;;
        -o|--stdout)
            STD_OUT_PATH="$2"
            shift 2
            ;;
        -e|--stderr)
            STD_ERR_PATH="$2"
            shift 2
            ;;
        -a|--java-args)
            JAVA_ARGS+=("$2")
            shift 2
            ;;
        -h|--help)
            print_usage
            exit 0
            ;;
        *)
            write_error "未知参数: $1" "使用 -h 或 --help 查看帮助"
            ;;
    esac
done

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)

write_info "启动 Agent Cloud Harness..."

write_info "步骤 1/3: 配置 Java 21 环境"
if ! source "$SCRIPT_DIR/Use-Java21.sh" "$JDK_HOME" "true"; then
    write_error "Java 环境配置失败" "\
1. 确保已安装 Java 21
2. 使用 JDK_HOME 环境变量指定正确路径"
fi
write_success "Java 环境配置完成"

write_info "步骤 2/3: 解析 JAR 文件路径"
RESOLVED_JAR=$(resolve_harness_jar "$JAR_PATH")
write_success "找到 JAR: $RESOLVED_JAR"

JAVA_EXE="$JAVA_HOME/bin/java"

if [ "$BACKGROUND" = true ]; then
    write_info "步骤 3/3: 后台模式启动（端口检查 + 启动）"
    assert_port_available "$PORT"
    mkdir -p ".tmp"
    RUNTIME_JAR=$(new_runtime_jar_copy "$RESOLVED_JAR" "$PORT")
    
    ARGUMENT_LIST=("--enable-preview" "-Dserver.port=$PORT" "${JAVA_ARGS[@]}" "-jar" "$RUNTIME_JAR")

    nohup "$JAVA_EXE" "${ARGUMENT_LIST[@]}" > "$STD_OUT_PATH" 2> "$STD_ERR_PATH" &
    PID=$!
    
    echo -e "\n🚀 Agent Cloud Harness 已启动（后台模式）"
    echo "──────────────────────────────────────────────────────"
    echo "PID:        $PID"
    echo "Port:       $PORT"
    echo "Jar:        $RESOLVED_JAR"
    echo "RuntimeJar: $RUNTIME_JAR"
    echo "Stdout:     $STD_OUT_PATH"
    echo "Stderr:     $STD_ERR_PATH"
    echo "──────────────────────────────────────────────────────"
    echo -e "\n📖 访问地址："
    echo "- Dialogue:  http://localhost:$PORT/dialogue/"
    echo "- Console:   http://localhost:$PORT/console/"
    echo "- Health:    http://localhost:$PORT/api/v1/health"
    echo -e "\n📋 管理命令："
    echo "- 查看日志:  tail -f $STD_OUT_PATH"
    echo "- 终止服务:  kill -9 $PID"
    echo "- 检查端口:  lsof -Pi :$PORT"
    
    # 保存 PID 到文件以便后续管理
    echo "$PID" > ".tmp/harness.pid"
else
    write_info "步骤 3/3: 前台模式启动"
    
    ARGUMENT_LIST=("--enable-preview" "-Dserver.port=$PORT" "${JAVA_ARGS[@]}" "-jar" "$RESOLVED_JAR")

    echo -e "\n🚀 启动 Agent Cloud Harness（前台模式）"
    echo "──────────────────────────────────────────────────────"
    echo "命令: $JAVA_EXE ${ARGUMENT_LIST[*]}"
    echo "──────────────────────────────────────────────────────"
    echo -e "\n按 Ctrl+C 停止服务\n"

    "$JAVA_EXE" "${ARGUMENT_LIST[@]}"
    
    if [ $? -ne 0 ]; then
        write_error "服务启动失败" "\
常见启动失败原因：
1. 端口被占用 - 使用 -p 参数指定其他端口
2. JAR 文件损坏 - 重新执行构建
3. Java 版本不兼容 - 确保使用 Java 21
4. 查看错误日志获取详细信息"
    fi
fi
