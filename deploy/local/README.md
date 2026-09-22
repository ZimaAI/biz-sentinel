# 本机部署说明

目标：`https://bizsentinel.zimagent.top` → `36.151.151.229`。
本目录为当前主机生成，不使用仓库中已过时的前端 Dockerfile。

## 部署结构

| 服务 | 监听 | 数据/配置 |
| --- | --- | --- |
| 现有 Nginx 的新增站点 | 80/443 | `/etc/nginx/sites-available/biz-sentinel` |
| Nuxt，独立 systemd 服务 | `127.0.0.1:13000` | `/opt/biz-sentinel/current/frontend` |
| Java，独立 systemd 服务 | `127.0.0.1:8065` | `/etc/biz-sentinel` |
| 独立 MySQL Docker 容器 | `127.0.0.1:13307` | Docker 卷 `biz-sentinel_mysql-data` |
| 上传/向量数据 | 无监听 | `/var/lib/biz-sentinel` |

既有 MySQL、PostgreSQL、MinIO、Docker daemon 配置及原 Nginx 站点不修改；不安装或升级全局依赖，不重置防火墙。
共享主机仍存在资源竞争，因此前后端、数据库和沙盒均限制资源。

## 构建

在仓库根目录执行后端构建，日志中的 Maven Checkstyle 会执行；部署构建跳过测试和自动格式化。

```bash
MAVEN_OPTS='-Xmx1024m -XX:ActiveProcessorCount=2' nice -n 10 ./mvnw \
  -B -ntp -s deploy/local/maven-settings.xml \
  -pl data-agent-management -am package -DskipTests -Dspotless.skip=true
```

在 `data-agent-frontend-nuxt` 目录执行：

```bash
pnpm install --frozen-lockfile
NODE_OPTIONS='--max-old-space-size=1536' nice -n 10 pnpm build
```

## 安装（需要管理员在本机终端执行）

先添加 DNS A 记录 `bizsentinel → 36.151.151.229`。不要添加无可用公网 IPv6 的 AAAA 记录。
80/443 必须能从公网到达现有 Nginx，应用的三个内部端口无需开放安全组。

首次部署（尚未存在 `/opt/biz-sentinel/current`）才使用旧版安装脚本：

```bash
sudo bash /home/zima/Develop/Projects/biz-sentinel/deploy/local/install.sh
```

当前 Commerce 部署更新使用仓库内脚本；它会切换新 release、移除阻挡游客入口的站点 Basic Auth、重启服务并运行匿名/管理员验证：

```bash
sudo bash /home/zima/Develop/Projects/biz-sentinel/deploy/local/update-commerce.sh
```

不要在已有 Commerce 部署上重复运行旧版 `install.sh`。

脚本检查路径、容器名和端口冲突，备份 Nginx，新建独立账号和数据库，生成随机凭据，安装前后端服务；服务正常后配置 HTTP 验证目录，用现有 Certbot 账号签发独立证书，启用 HTTPS，测试续期并检查原站点。商脉登录页由应用会话负责认证，游客入口无需 Basic Auth；管理员账号仍通过页面内的隐藏账号密码入口登录。
安装时需要能够拉取 `mysql:8.0`。实际镜像 digest 保存在部署备份目录中。
证书沿用本机已有 ACME 账号；若现有账号不可用，Certbot 会失败，需要另外配置账号，不自动修改现有账号。

脚本可能因网络、DNS、镜像拉取、应用启动等失败；失败不代表已上线。根据具体错误修复后可以重试。
HTTPS 申请前的新 HTTP 站点只开放 ACME 路径，其余返回 404。
整个过程不停止现有 Nginx，只在配置检查成功后 reload。

随机登录账号保存在仅当前用户可读的文件中，请在本机查看，不要贴到聊天或提交到 Git：

```bash
cat /home/zima/.local/state/biz-sentinel-deploy/admin-login.txt
```

数据库密码和应用环境文件仅 root 可读。前后端账号均不授予 Docker socket 权限。
检查项目依赖 `agentscope-runtime-sandbox-core:1.0.2` 的源码发现，`DockerClient.createContainers` 使用没有指定 host IP 的 `PortBinding.parse(hostPort + ":" + containerPort)`，会将沙盒控制端口发布到所有网卡。
因此本次仅部署基础管理及 SQL 分析能力，Python 沙盒执行暂不启用。需要先修正依赖集成、将沙盒端口限定到回环地址并验证后再启用，不能直接给后端添加 Docker 组权限。不要修改 Docker daemon 的全局网络设置来修复，以免影响已有容器。

## 验证与运维

```bash
python3 deploy/local/verify.py
sudo systemctl status biz-sentinel-backend biz-sentinel-frontend
sudo journalctl -u biz-sentinel-backend -n 100 --no-pager
sudo journalctl -u biz-sentinel-frontend -n 100 --no-pager
sudo certbot renew --cert-name bizsentinel.zimagent.top --dry-run
```

`verify.py` 检查登录页、游客会话、游客只读限制、管理员会话、证书和原站点，但从本机发起的请求不能证明其他网络能访问。
还应通过手机移动网络访问新域名，确认登录和页面正常。
用 `ss -lntp` 确认 8065、13000、13307 仅监听回环地址。已有数据库端口的安全组规则需独立审查，不能为了本项目直接关闭而影响旧服务。
Certbot 复用已有 `snap.certbot.renew.timer`，不新增重复定时任务。

登录后在模型配置中添加聊天模型及 Embedding 模型、API Key、服务地址，然后添加业务数据源。
业务数据源建议提供只读账号。没有真实模型凭据时只能验收基础部署，不能宣称 AI 分析已通过。
Python 功能还需修复上述端口绑定问题，并验证默认 AgentScope 沙盒镜像及 Python 依赖网络；配置中的默认并发已降为 1，但目前不授予执行权限。
Agent API Key 启用后，外部 API 客户端应以 `X-API-Key` 发送它。商脉的游客和管理员会话使用应用 Cookie/CSRF 机制。

## 数据备份

数据库使用独立持久卷；上传数据和向量数据不会随版本替换删除。
源码中的 SimpleVectorStore 在正常关闭时保存向量文件，异常退出前的新向量可能尚未落盘。
要获得包含最新向量的备份，应只停止本项目后端（等待正常关闭），导出本项目 MySQL，再备份 `/var/lib/biz-sentinel`，最后启动本项目后端。不要停止现有业务数据库。
已提供执行这些步骤的脚本：`sudo bash deploy/local/backup.sh`。它会短暂中断本项目后端，其他服务继续运行；备份包含密码，必须保密。
目前未自动创建周期备份任务，需按实际恢复要求安排并将备份复制到另一台机器。

## 回滚

```bash
sudo bash /home/zima/Develop/Projects/biz-sentinel/deploy/local/rollback.sh
```

回滚仅撤下新增站点并停止本项目服务和数据库容器。数据卷、文件、证书和配置保留，不执行 `docker compose down -v` 或全局 prune。
Nginx 原配置及部署前后检查位于 `/var/backups/biz-sentinel/<时间>/`。不要直接覆盖恢复整个目录，以免覆盖之后其他站点的变更。

参考：[Nuxt 生产部署](https://nuxt.com/docs/4.x/getting-started/deployment)、[Nginx 平滑重载](https://nginx.org/en/docs/control.html)、[Certbot webroot](https://eff-certbot.readthedocs.io/en/stable/using.html#webroot)。
