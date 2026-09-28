# 二开版本云部署

基于官方 Metapi v1.3.0（提交 `63c435c90189d175ccdb533bbdc1cf52e3996b41`），将现有 Windows 桌面包中的服务端和管理界面改动迁回源码。原桌面包未修改。

## 云端功能

- NexaVlinks 未签到且网站今日实际消费不足目标的账号优先，默认目标 0.4；跨天使用 Asia/Shanghai，失败回退原路由。
- API Key 会话粘连；同会话首次选路串行，超时或执行异常时不自动换 Key 重放。
- 站点余额可选 GET /v1/usage，保留接口金额单位。
- 站点可固定 Responses 协议；保留 Responses 历史输入项目。
- 桌面内置签到窗口改为“原站签到”链接，用户自行核对原站登录账号、完成验证码和签到，再点击“刷新进度”。云端不会向浏览器导出登录 Cookie。
- 桌面托盘、便携 Windows 凭证及直接修改本机配置的能力不属于云端服务。

## 1. 准备账号和源码仓库

需要 GitHub、Render、TiDB Cloud 账号。建议将此源码目录上传到自己管理的私有仓库；Render 通过 GitHub 授权读取该仓库。

本地工作分支为 `cloud-custom`。`render.yaml` 不再指定官方仓库，因此 Blueprint 使用该文件所在仓库。手动部署时也必须选择自己的仓库和包含本次修改的分支。

不要上传原桌面目录、`profile/`、数据库、备份 JSON、`.env` 或任何真实密钥。此源码工程没有复制原账号数据。

如果用全新仓库而非 GitHub Fork，推送前在仓库 Actions 设置中关闭不需要的官方发布/文档工作流；Render 构建不依赖 GitHub Actions。不要推送官方版本标签来触发自己的 Release。

## 2. TiDB Cloud

选择提供免费额度的 Starter 方案（Metapi 上游文档称 Serverless），不要选择 Dedicated/付费方案。创建后从 Connect 面板取得 MySQL 主机、端口、用户名和密码，使用可建表的用户数据库（例如 `test`），不要使用系统库 `sys`。

连接串模板：

```text
mysql://USERNAME:PASSWORD@HOST:4000/test
```

用户名和密码中的 `@`、`:`、`/`、`#`、`%` 等字符必须按 URL 组件编码。密码只填到 Render 的环境变量，不写入代码、截图或聊天。

2026-09-28 核查的价格页：Starter 每组织包含 25 GiB 行存储、25 GiB 列存储和每月 2.5 亿 RU。注册时以控制台当前额度、支出限制和条款为准；避免启用超出免费额度的付费资源。

## 3. Render

可选择 New → Blueprint，连接自己的仓库并使用根目录 `render.yaml`；也可 New → Web Service 手动设置：

| 设置 | 值 |
| --- | --- |
| Repository / Branch | 自己的仓库 / 包含二开修改的分支 |
| Language / Runtime | Docker |
| Dockerfile Path | `./docker/Dockerfile` |
| Docker Build Context | `.` |
| Instance Type | Free |

环境变量：

| 变量 | 内容 |
| --- | --- |
| AUTH_TOKEN | 独立的强随机管理令牌 |
| PROXY_TOKEN | 独立的强随机代理令牌，建议以 `sk-` 开头 |
| ACCOUNT_CREDENTIAL_SECRET | 独立的持久加密密钥；迁移原账户加密内容时应保持原值 |
| DB_TYPE | `mysql` |
| DB_URL | TiDB 的 MySQL 连接串 |
| DB_SSL | `true` |
| TZ | `Asia/Shanghai` |
| PORT | `4000` |
| DATA_DIR | `/app/data`（临时目录，不能用于持久 SQLite） |
| NODE_ENV | `production` |

前三项及 DB_URL 在 Blueprint 中要求自行填写，避免部署时使用上游默认密码。加密密钥不能随每次重建改变。

部署会在 Render 上构建前后端和 Linux 原生依赖。不要上传 Windows 的 `node_modules` 或 exe。Dockerfile 沿用 v1.3.0 官方配置（Node 22）；上游 package.json 声明 Node >=25，两者本身不一致，本次未升级核心运行时，需以首次云构建结果继续核实。

## 4. 数据迁移

可以先使用空数据库启动，再手工添加站点和账户。需要保留已有数据时：

1. 在原电脑退出桌面程序后备份整个 `profile/`，保留原便携凭证文件。
2. 若要迁移加密保存的账户密码，自己从便携凭证中取得原账户加密密钥，填入 Render 的 `ACCOUNT_CREDENTIAL_SECRET`。不要把凭证文件加入仓库。
3. 在桌面版设置中导出完整账号与偏好备份；在云端确认目标为空或已备份后导入。
4. 导入账号会替换目标现有站点、账号、路由等数据，不应在有其他有效配置的实例上直接操作。偏好导入可能改变代理总 Key、定时任务和其他设置。
5. 移除指向原电脑 `127.0.0.1` 的站点/账号代理，或替换为云端实际可访问的代理；桌面浏览器会话不迁移。
6. 在云端重新确认站点协议、余额方式、模型权限、NexaVlinks 进度及下游 Key。全量备份包含敏感凭证，应私下保管。

此步骤使用已有应用备份接口，不直接把 SQLite 文件导入 TiDB。历史请求日志、浏览器登录缓存和内存会话粘连不视为可迁移备份内容。当前尚未进行真实数据迁移或跨数据库运行验证。

## 5. 免费实例的限制

- Render 每工作区每月共享 750 免费实例小时；15 分钟无入站流量会休眠，唤醒约一分钟。
- 可按 Metapi 文档使用 UptimeRobot 免费 HTTPS 监控访问服务首页（5 分钟一次），无需把管理员令牌给监控平台。但这不构成 24 小时在线保证。
- 免费实例可能重启，没有持久化磁盘；内存会话粘连和临时缓存会清空。数据库放在 TiDB。
- 免费带宽、构建额度和大量对外 API/数据库流量仍有限制；Render 可能暂停服务。绑定付款方式后应特别核对支出规则。
- 电脑关机不影响云端程序运行，前提是云服务和数据库仍可用。

## 6. 更新与验收

以后修改自己的源码分支再由 Render 部署，不能切回官方镜像或直接覆盖为上游最新版。合并官方更新前保留二开差异并备份数据库。

本地默认仅执行差异、TypeScript 无输出检查和仓库静态规则检查，不执行构建、单元测试、服务启动或真实接口调用。首次云构建和数据库初始化尚待平台账号就绪后完成；成功启动后还需实际确认登录、设置保存、流式请求、签到状态同步及实例重建后的数据保留。

参考：[Metapi 部署文档](https://metapi.cita777.me/deployment)、[Render 免费实例](https://render.com/docs/free)、[TiDB 价格](https://www.pingcap.com/pricing/)、[UptimeRobot 价格](https://uptimerobot.com/pricing/)。
