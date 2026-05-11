# Security Policy

## Supported Scope

当前仓库主要面向本地或受控环境使用。

已知边界：

- 默认无认证、无授权
- 无多租户隔离
- 默认使用本地 SQLite
- 内置工具层可访问本地文件与受控命令

因此在未补齐安全控制前，不建议直接暴露到公网。

## Reporting a Vulnerability

如果你发现安全问题，请不要直接公开提交利用细节。

建议在 issue 中仅描述影响范围，并避免附带可直接复现的敏感 payload；如需私下沟通，可先通过仓库维护者提供的渠道联系。

## Current Security Priorities

当前更优先的安全改进方向包括：

- 最小认证层
- API Key / access control
- 更严格的输入校验
- 审计日志与限流
- 工具层更细粒度权限约束
