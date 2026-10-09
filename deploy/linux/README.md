# Linux 内网部署（现有二开 Web 版）

适用于 `cloud-custom` 源码，不部署 Android APK 或 Windows Electron 包。保留既有 Web 二开功能；原站验证码和人工签到仍在浏览器中完成，桌面 Cookie 不会导出到 Web。

## 部署布局

- `192.168.3.3` 为 Debian 13 / x86_64 的 LXC 容器，已有 systemd、无 Docker。采用 Node.js + systemd，不修改宿主机嵌套容器配置。
- `/opt/metapi/releases/<版本>/`：源码、Linux 依赖、构建结果；由 root 管理，运行账户不可修改。
- `/opt/metapi/current`：当前发布目录链接。
- `/opt/metapi/node`：独立 Node.js 运行时链接；遵循源码 `.nvmrc` 的 25.0.0 基线，本部署不修改系统 Node.js。
- `/etc/metapi/metapi.env`：独立管理令牌、代理令牌、账户加密密钥，权限 `root:root 0600`。
- `/var/lib/metapi/`：持久数据目录，由 `metapi` 服务账户拥有，权限 `0700`。
- `/etc/systemd/system/metapi.service`：使用本目录的服务单元，开机启动、失败重启。

首次部署使用新数据库，只包含应用初始化的默认配置，不导入桌面版账号和 Key。更新时始终复用数据目录和账户加密密钥。

## 安装与构建约束

1. 准备非登录系统账户 `metapi`、独立目录、系统编译工具 `make` / `g++` / `python3`、CA 证书和下载工具。首次安装需管理员授权。
2. 从 Node.js 官方 HTTPS 地址取得指定 Linux x64 归档和 `SHASUMS256.txt`，核对 SHA-256 后解压至 `/opt/metapi/`。运行时升级应独立评估，不自动跟随最新版。
3. 从本地源码生成发布快照并经 SSH 传输；只包含受版本控制文件和本次部署适配。不上传 `.git`、`.env`、`profile/`、数据库、Windows `node_modules` 或 Android 工作区。服务器访问 GitHub 不稳定时无需在服务器拉取仓库。
4. 在新的版本目录安装锁定依赖并构建，不能在正在运行的版本目录原地构建：

   ```sh
   export PATH=/opt/metapi/node/bin:/usr/bin:/bin
   export ELECTRON_SKIP_BINARY_DOWNLOAD=1
   npm ci --ignore-scripts --no-audit --no-fund
   npm rebuild esbuild sharp --no-audit --no-fund
   npm_config_build_from_source=true npm rebuild better-sqlite3 --no-audit --no-fund
   npm run build:web
   npm run build:server
   npm prune --omit=dev --ignore-scripts --no-audit --no-fund
   ```

   沿用上游 Dockerfile 的依赖安装策略，仅构建 Web 和服务端；`better-sqlite3` 在 Linux 本机编译，避免依赖 GitHub 预编译下载。
5. 以 `metapi.env.example` 为模板在服务器生成环境文件，分别生成三个至少 32 字节的随机密钥。不要使用示例值启动，不在部署日志或聊天中打印真实值。
6. 确认端口空闲、版本链接和文件权限后安装 `metapi.service`，执行 `systemctl daemon-reload`、`systemctl enable --now metapi`。服务启动前执行项目现有迁移；只针对独立数据目录，不接触其他数据库。

## 访问与安全

- 管理界面：`http://192.168.3.3:4000`；兼容 API Base URL：`http://192.168.3.3:4000/v1`。
- 只监听该内网 IPv4，不绑定所有接口；这不替代网关访问控制，不要配置公网端口转发。
- 管理员可在 root SSH 会话中使用 `grep '^AUTH_TOKEN=' /etc/metapi/metapi.env` 查看初始登录令牌；不要转发输出。应用内修改管理令牌后，数据库保存的新值优先于环境变量。
- 直接 HTTP 不加密凭证和请求内容，仅适合可信内网；不可信 Wi-Fi / 远程访问应先配置受信任 HTTPS 或 VPN。当前模板不安装证书、不修改防火墙或现有 Tailscale。
- systemd 以非 root 账户运行，文件系统只允许写数据目录及私有临时目录。内置更新中心不用于覆盖此二开版本，后续使用受控源码发布。
- 不会修改用户在聊天中提供的 SSH 密码；建议部署后自行更换并改用密钥登录。

## 运维与更新

```sh
systemctl status metapi --no-pager
journalctl -u metapi -n 60 --no-pager
systemctl restart metapi
```

更新前短暂停止服务，私下备份 `/var/lib/metapi/` 及 `/etc/metapi/metapi.env`，再恢复服务；备份保存在非 Web 可读目录，勿加入 Git。新版本独立构建成功后再停止旧服务、切换 `current` 链接、启动并检查。启动可能升级数据库，不能认为只切回旧代码就能安全回退；必要时同时恢复匹配的数据备份。

首次验收检查 systemd 状态、监听地址、首页、管理接口鉴权以及空账号状态。不自动添加账号、不调用收费上游、不做业务写入测试。服务器、客户端或上游的网络条件变化可能影响后续真实请求。


## 本次部署记录（2026-10-08）

- 源码基线：本地 `cloud-custom` 分支提交 `1fe1caf`，加本次 Linux 部署文件；发布目录为 `/opt/metapi/releases/1fe1caf-lan-20261008`，未推送 Git 远程。
- 已完成 Linux 原生依赖构建、Web / 服务端生产构建、systemd 单元校验和首次数据库初始化；运行账户为 `metapi`，服务已启用开机启动。
- 已验证内网客户端首页 HTTP 200、无管理令牌 HTTP 401、错误令牌 HTTP 403、正确令牌可读取空账号列表及 NexaVlinks 设置；未进行浏览器端到端或真实上游计费请求验证。
- 初次核查时服务器能访问 Node.js、npm 和 NexaVlinks，访问 GitHub 超时；未来更新应重新确认网络，不保证内置在线更新可用。
- 构建存在上游依赖弃用、npm 构建参数兼容性及前端大文件警告；本次未进行无关依赖升级。
