package com.agentcloud.judgment.model.shadow.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M4 Calibration CLI 入口：read-only 跑 DecisionEvent JSONL 摘要，写 JSON 到 stdout。
 * <p>
 * 用法：
 *   java com.agentcloud.judgment.model.shadow.stats.DecisionEventStatsCli &lt;jsonl-path&gt;
 *   java com.agentcloud.judgment.model.shadow.stats.DecisionEventStatsCli --pretty &lt;jsonl-path&gt;
 * <p>
 * 退出码：
 *   0 = 成功（含空文件 / missing 文件，输出 0 events 摘要）
 *   2 = 参数错误
 *   1 = 读取异常（仅在 IOException 时）
 * <p>
 * 主逻辑在 run(String[], PrintStream, PrintStream) → int，方便测试不触发 System.exit。
 */
public final class DecisionEventStatsCli {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private DecisionEventStatsCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length == 0) {
            err.println("Usage: DecisionEventStatsCli [--pretty] [--reasons] <jsonl-path>");
            return 2;
        }

        boolean pretty = false;
        boolean reasons = false;
        String pathArg = null;
        for (String a : args) {
            if ("--pretty".equals(a)) {
                pretty = true;
            } else if ("--reasons".equals(a)) {
                reasons = true;
            } else if (pathArg == null) {
                pathArg = a;
            }
        }
        if (pathArg == null) {
            err.println("Usage: DecisionEventStatsCli [--pretty] [--reasons] <jsonl-path>");
            return 2;
        }

        Path jsonlPath = Paths.get(pathArg);
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("path", jsonlPath.toString());
            envelope.put("summary", DecisionEventStats.compute(jsonlPath));
            if (reasons) {
                envelope.put("reasons", DecisionEventReasonStats.compute(jsonlPath));
            }
            String json = MAPPER.writeValueAsString(envelope);
            out.println(json);
            return 0;
        } catch (IOException e) {
            err.println("Failed to read JSONL: " + e.getMessage());
            return 1;
        }
    }
}