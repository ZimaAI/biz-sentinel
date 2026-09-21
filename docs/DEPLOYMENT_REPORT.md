# Biz Sentinel 本机部署报告

更新时间：2026-09-21 15:40，Asia/Shanghai。安装完成时间：2026-09-21 15:36:52。

## 当前状态

**基础部署已完成，正式域名、HTTPS、访问认证及基础 API 验收通过。AI 业务功能待配置模型；Python 沙盒暂未启用。**

用户已授权本机部署，要求使用公网域名及 HTTPS，应用和本项目数据库端口不得直接对公网开放，保留已有服务。
用户已在本机终端通过 sudo 执行安装脚本，安装日志确认数据库健康、证书签发及续期模拟成功。随后已独立执行正式环境验证脚本。当前账号不具备系统日志、证书续期配置和 Docker daemon 的读取权限，因此这些管理员级结果以安装脚本输出为依据；未索取 sudo 密码。

访问地址：**https://bizsentinel.zimagent.top**。登录用户名：`admin`。

密码保存在仅当前用户可读的文件中，请在本机查看：

```bash
cat /home/zima/.local/state/biz-sentinel-deploy/admin-login.txt
```

不要把该文件内容贴到聊天或提交到 Git。

## 目标和主机

| 项目 | 值 |
| --- | --- |
| 域名 | `bizsentinel.zimagent.top` |
| 公网 IP | `36.151.151.229`，用户确认 |
| DNS | 用户已添加；本机和 Google 公共 DNS 均确认 A 记录正确，未发现 AAAA 记录 |
| 操作系统 | Ubuntu 24.04.4 LTS，x86_64 |
| CPU / 内存 | 4 核 / 7.8 GiB；初始可用约 5.8 GiB，无 swap |
| 磁盘 | 初始空闲约 143 GB |
| JDK | 21.0.11 |
| Node / pnpm | 24.17.0 / 11.8.0 |
| Nginx / Certbot | 1.24.0 / 5.8.0 |
| Docker / Compose | 客户端 29.6.2 / v5.3.1；daemon 运行中，当前账号无访问权限 |
| 源码版本 | `5cffda35589576b4a02d31f262e446d552c2a72a` |

## 部署设计

复用 Nginx 80/443，仅新增本项目虚拟主机；80 只提供 ACME 验证及 HTTPS 跳转。
通过 HTTPS Basic 认证保护整个站点。项目的默认安全配置只要求流式搜索接口认证，不能直接裸露管理 API。

| 服务 | 地址 | 隔离和限制 |
| --- | --- | --- |
| Nuxt | `127.0.0.1:13000` | 独立账号 `biz-sentinel-web`，512 MiB，0.5 CPU |
| Java | `127.0.0.1:8065` | 独立账号 `biz-sentinel`，堆 1536 MiB，进程 2300 MiB，1.5 CPU |
| 本项目 MySQL | `127.0.0.1:13307` | 独立容器、账号和卷，1 GiB，0.75 CPU |

不使用原仓库过时的前端 Dockerfile，不修改本机现有 MySQL、PostgreSQL、MinIO 或 Docker daemon 配置。
现有 MySQL 在所有 IPv4 网卡监听 3306；其公网可达性取决于现有防火墙和云安全组，不等同于本次新增数据库的暴露范围。本次不擅自修改旧数据库规则。

## 已完成检查

### 正式环境验收

| 检查 | 结果 |
| --- | --- |
| A 记录 | `bizsentinel.zimagent.top → 36.151.151.229` |
| HTTP → HTTPS | 301，保留请求路径 |
| 证书域名及信任链 | Python 默认信任库验证通过，SAN 包含目标域名 |
| 证书到期时间 | 2026-12-20 06:38:05 UTC，即北京时间 14:38:05 |
| 未认证的页面 / API | 均返回 401 |
| 认证后的页面 / JS / CSS | 均返回 200，资源 Content-Type 正确 |
| 正式后端智能体列表 | 返回 200，初始列表为空，数据库查询正常 |
| 模型就绪 API | 返回 200；聊天模型和 Embedding 模型均未配置 |
| 前端进程 | active/running，enabled，自动重启计数 0 |
| 后端进程 | active/running，enabled，自动重启计数 0 |
| 端口 13000 / 8065 / 13307 | 全部仅监听回环地址 |
| MySQL 容器 | 安装日志显示 Healthy；安装脚本校验 Docker 端口映射为 `127.0.0.1:13307` |
| 证书续期模拟 | 用户提供的执行日志显示成功 |
| 续期计划 | 现有 `snap.certbot.renew.timer` 已排期 |
| 原域名 | `algomotion.zimagent.top` 返回 200 |
| 既有服务 | Nginx、MySQL、PostgreSQL、Docker 均 active；MinIO `/minio/health/live` 返回 200 |
| 密码文件权限 | 后端环境文件 0600 root；站点密码哈希 0640 root:www-data；用户登录凭据 0600 zima |

后端监听在 `ss` 中显示为 `[::ffff:127.0.0.1]:8065`，这是 IPv4 回环地址的 IPv6 映射表示，并非对所有 IPv6 网卡开放。
进程占用快照：后端约 513 MiB，前端约 24 MiB；主机可用内存约 5.1 GiB，磁盘空闲约 140 GB。未进行负载或重启演练，开机启动仅验证了 enabled 配置。

正式验证结果：`/home/zima/.local/state/biz-sentinel-deploy/verification.json`；静态资源和模型就绪详情：同目录的 `acceptance-details.json`。
网络请求从本机发起；外部网页抓取工具未能访问站点，无法据此判断公网失败或成功。证书 HTTP-01 签发已成功，但还需用户从手机移动网络确认实际浏览器登录体验。

### 构建和安装前检查

- 前端 `pnpm install --frozen-lockfile` 和生产构建成功。
- 后端 Maven 生产打包成功，可执行 JAR 约 554 MiB；Checkstyle 无违规，主代码和测试代码均编译通过。
- 后端使用独立 H2 内存数据库在 `127.0.0.1:18065` 启动成功；智能体列表、数据源类型、模型就绪检查 API 均返回 200 和有效 JSON。该检查不替代正式 MySQL 验收。
- 现有 `WebFluxSecurityConfigurationTest` 共 5 项测试通过，0 失败、0 错误；未运行完整测试套件。
- 前端临时回环端口检查：`/` 跳转 `/agent/new`，页面与 JS/CSS 返回 200。
- Nginx 配置在独立高位回环端口、自签测试证书下完成语法及功能检查：未认证页面和 API 返回 401，认证页面返回 200。该测试证书不用于正式站点。
- Compose 配置校验通过。
- systemd 模板校验通过（校验时将尚未安装的 Node 路径替换为现有 Node 路径）。
- Bash/Python 语法检查通过。
- 临时前端、后端和 Nginx 测试进程已停止，测试端口已释放。
- 原站点 `algomotion.zimagent.top` 在本机及经公网地址请求均返回 200；本机发起的请求不等同于独立外网测试。
- Nginx、MySQL、PostgreSQL、Docker 系统服务仍为 active。
- 部署凭据已在安装时随机生成，未写入仓库。

首次默认仓库下载慢且出现校验警告，随后使用部署专用 Maven Central 配置构建成功；未修改全局 Maven 设置。

## Python 和 AI 功能限制

查阅 `agentscope-runtime-sandbox-core:1.0.2` 的源代码确认，默认沙盒控制端口未绑定特定 host IP，会发布到所有网卡。因此本次不授予后端 Docker socket 权限，**Python 沙盒执行暂不启用**。需先修正沙盒端口绑定并验证后再启用；不能直接放宽 socket 权限。

基础管理服务已可用。正式模型就绪 API 返回 `chatModelReady=false`、`embeddingModelReady=false`、`ready=false`。真实聊天模型、Embedding 模型及业务数据源尚未配置，AI/SQL 端到端分析尚未验收。用户应在认证页面填写模型服务地址、模型名、API Key 并测试激活，再添加业务数据源；业务数据源使用只读账号。Python 修复前优先使用“仅 NL2SQL”模式，包含 Python 步骤的流程不能正常完成。

## 文件和管理方式

部署说明和脚本：`deploy/local/README.md`、`install.sh`、`verify.py`、`backup.sh`、`rollback.sh`。

已安装位置：

- 程序版本：`/opt/biz-sentinel/releases/20260921-153346`，`current` 指向该版本。
- 配置和密码：`/etc/biz-sentinel`；数据库环境文件仅 root 可读。
- 用户可读的登录凭据：`/home/zima/.local/state/biz-sentinel-deploy/admin-login.txt`，权限 0600。
- 上传及向量数据：`/var/lib/biz-sentinel`。
- 数据库持久卷：`biz-sentinel_mysql-data`。
- 应用日志：`journalctl -u biz-sentinel-backend`、`journalctl -u biz-sentinel-frontend`。
- Nginx 日志：`/var/log/nginx/biz-sentinel.*.log`。
- 证书：`/etc/letsencrypt/live/bizsentinel.zimagent.top/`。
- 配置备份和部署前后证据：`/var/backups/biz-sentinel/20260921-153346/`。

## 续期、备份和回滚

已复用 `snap.certbot.renew.timer`，通过 webroot 验证，续期成功后执行 `nginx -t && systemctl reload nginx`。正式证书已签发，`certbot renew --cert-name bizsentinel.zimagent.top --dry-run` 成功。

安装输出中的 Docker cpuset / I/O 控制警告表示内核不支持那些控制功能，本次未配置这些控制项；CPU 配额和内存限制配置已设置，未做极限压力验证。启动初期的 curl 连接失败发生在 Java 就绪前，脚本随后通过就绪检查。Certbot 的 `error output` 段实际为 `nginx -t` 写向 stderr 的成功提示，不是证书签发失败。

`backup.sh` 仅短暂停止本项目后端以保存向量数据，导出独立数据库、打包文件和配置，然后恢复后端。备份包含秘密，需保护并复制到其他存储；尚未建立周期备份任务，也未进行恢复演练。

`rollback.sh` 仅撤下新增站点并停止本项目服务和数据库容器，保留数据、证书和配置。不执行全局清理或删除数据库卷。

## 后续手动操作及验收边界

1. 从手机移动网络打开正式域名，使用本机凭据文件中的账号密码登录。
2. 在模型配置中添加并测试激活聊天模型和 Embedding 模型，再配置业务数据源；无需在聊天中提供密钥。
3. 执行一条真实 SQL 分析，验证模型和数据源的端到端链路。
4. Python 沙盒需要先修正端口绑定并单独验证，不要直接添加 Docker 权限。
5. 根据数据重要性安排周期备份、异机保存和恢复演练；脚本已提供，但这些演练尚未执行。

本报告确认基础部署完成，不代表尚未配置的 AI 分析或暂未启用的 Python 功能已经验收。
