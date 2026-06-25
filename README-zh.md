# Spring AI Alibaba Lynxe

<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)
[![GitHub Stars](https://img.shields.io/github/stars/alibaba/spring-ai-alibaba.svg)](https://github.com/alibaba/spring-ai-alibaba/stargazers)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/spring-ai-alibaba/Lynxe)

[English](./README.md) | 🌍 **中文**

[关于](#-关于) • [快速开始](#-快速开始) • [如何贡献](#-如何贡献)

[使用案例](https://github.com/Lynxe-public/Lynxe-public-prompts)

</div>

## ✨ Lynxe 简介（原名：JManus）

Lynxe 是 Manus 的一个 Java 实现，目前已经在阿里巴巴集团内的很多应用都有使用，主要用于处理需要有一定确定性要求的探索性任务，比如，快速从海量数据中找到数据并转换成数据库内的一行数据，或者分析日志并给出告警等。

你可以从这里看到我们推荐的一些已经实现好的 Func-Agent 的[使用案例](https://github.com/Lynxe-public/Lynxe-public-prompts)。

Lynxe 也提供了 http 的服务调用能力，适合被集成到既有的项目中。具体可以见[开发者快速入门 (中文)](./README-dev.md)

## 🎯 Lynxe 的产品特性

### 🤖 **纯 Java 的 Manus 实现**：

纯粹的 Java 多智能体协作实现，提供了完整的 http 调用接口，适合 Java 开发者做二次集成。

### 🛠️ **Func-Agent 模式**：

精确控制每一步执行细节，提供极高的执行确定性并完成复杂的重复流程和功能。具体可以见 [Lynxe 案例集-Func-Agent 案例](https://github.com/Lynxe-public/Lynxe-public-prompts/blob/main/chn/query-plan.md)。
![Image](https://github.com/user-attachments/assets/00c8c292-a2d2-4c33-bab8-c4d45d1fa641)

### 🔗 **MCP 集成**：

原生支持模型上下文协议（Model Context Protocol），实现与外部服务和工具的无缝集成。
![Image](https://github.com/user-attachments/assets/dc4df65b-40be-4a6c-8790-cc091d5aa1a1)

## 🚀 快速开始

在 5 分钟内启动并运行 Lynxe：

### 先决条件

- 🌐 **DashScope API 密钥** (或替代的 AI 模型提供商)
  > 💡 **获取您的 DashScope API 密钥**: 访问 [阿里云百炼控制台](https://bailian.console.aliyun.com/?tab=model#/api-key)，在密钥管理页面创建 API Key 并复制密钥。新用户可享受 100 万 Token 输入和 100 万 Token 输出的免费额度（有效期 90 天）。
- ☕ **Java 17+** (用于运行 JAR 文件或源码运行) 或 🐳 **Docker** (用于容器化部署)

### 方式一：使用 GitHub Release (推荐)

Release 提供的是 **Fat JAR**（单文件、包含全部依赖的可执行 JAR）。使用 Java 17+ 下载后直接运行即可。

#### 📦 下载并运行 Fat JAR

```bash
# 从 Release 下载可执行 Fat JAR（资产文件名为 lynxe.jar，与本地 mvn 生成的 lynxe-exec-fat-jar.jar 同类）
wget https://github.com/spring-ai-alibaba/Lynxe/releases/latest/download/lynxe.jar

# 或使用 curl
curl -L -o lynxe.jar https://github.com/spring-ai-alibaba/Lynxe/releases/latest/download/lynxe.jar

# 运行（需 Java 17+）
java -jar lynxe.jar
```

> **文件名说明**：GitHub Release 上为便于下载，可执行包固定以 **`lynxe.jar`**（以及带版本号的 **`lynxe-<version>.jar`**）发布。本地执行 `mvn package` 时，同一可执行制品在 `target/` 下的文件名为 **`lynxe-exec-fat-jar.jar`**（由 `pom.xml` 的 `classifier` 决定）。二者内容等价，只是命名不同。

Fat JAR 即主制品，使用 `java -jar` 即可运行，无需配置 classpath 或额外依赖。

> 💡 **手动下载**: 请访问 [Lynxe Releases 页面](https://github.com/spring-ai-alibaba/Lynxe/releases) 下载最新的可运行 JAR（Release 上常见文件名为 `lynxe.jar` 或 `lynxe-<version>.jar`）。若需将 Lynxe 作为库嵌入项目，请使用 **Thin JAR**（同一 Release 或 Maven 坐标中的普通 `lynxe-<version>.jar`，非 Spring Boot 可执行重打包产物），详见 [将 Lynxe 作为库使用](./docs/LYNXE-AS-LIBRARY.md)。

#### 🌐 访问应用

应用启动后，在浏览器中访问 `http://localhost:18080`。

> 💡 **引导式设置**: 应用启动后会自动显示引导页面。在第一个页面选择中英文语言，然后在第二个页面输入您的 DashScope API 密钥完成配置。新用户可享受 100 万 Token 输入和 100 万 Token 输出的免费额度（有效期 90 天）。访问 [阿里云百炼控制台](https://bailian.console.aliyun.com/?tab=model#/api-key) 获取免费 API 密钥。

🎉 **恭喜!** 您的多 Agent 系统现已快速启动完成。 你可以访问https://github.com/talk-flow/public-usecase 去做一些我们认为比较有效的实践。

---

### 方式二：使用 Docker (生产环境推荐)

#### 🐳 拉取并运行 Docker 镜像

```bash
# 拉取最新版本的 Lynxe Docker 镜像
docker pull ghcr.io/spring-ai-alibaba/lynxe:v4.7.0

# 运行容器
docker run -d \
  --name lynxe \
  -p 18080:18080 \
  ghcr.io/spring-ai-alibaba/lynxe:v4.7.0
```

#### 🔧 高级 Docker 配置

**运行并持久化数据（生产环境推荐）：**

```bash
# 创建数据持久化目录
mkdir -p ./lynxe-data

# 运行并挂载数据卷
docker run -d \
  --name lynxe \
  -p 18080:18080 \
  -v $(pwd)/lynxe-data:/app/data \
  ghcr.io/spring-ai-alibaba/lynxe:v4.7.0
```

**使用自定义环境变量运行：**

```bash
docker run -d \
  --name lynxe \
  -p 18080:18080 \
  -e SPRING_PROFILES_ACTIVE=mysql \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3306/lynxe \
  -e SPRING_DATASOURCE_USERNAME=your_username \
  -e SPRING_DATASOURCE_PASSWORD=your_password \
  ghcr.io/spring-ai-alibaba/lynxe:v4.7.0
```

#### 🌐 访问应用

容器启动后，在浏览器中访问 `http://localhost:18080`。

> 💡 **引导式设置**: 应用启动后会自动显示引导页面。在第一个页面选择中英文语言，然后在第二个页面输入您的 DashScope API 密钥完成配置。新用户可享受 100 万 Token 输入和 100 万 Token 输出的免费额度（有效期 90 天）。访问 [阿里云百炼控制台](https://bailian.console.aliyun.com/?tab=model#/api-key) 获取免费 API 密钥。

#### 📋 常用 Docker 命令

```bash
# 查看容器日志
docker logs -f lynxe

# 停止容器
docker stop lynxe

# 启动容器
docker start lynxe

# 删除容器
docker rm lynxe
```

🎉 **恭喜!** 您的多 Agent 系统现已通过 Docker 运行。 你可以访问https://github.com/talk-flow/public-usecase 去做一些我们认为比较有效的实践。

---

### 方式三：从源码运行 (次选方案)

#### 1. 克隆并构建

```bash
git clone https://github.com/spring-ai-alibaba/Lynxe.git
cd Lynxe

# 构建项目（在 target/ 下生成 Thin JAR 与可执行的 Fat JAR）
mvn clean package -DskipTests
```

**可执行的 Fat JAR** 由 `pom.xml` 中的 `spring-boot-maven-plugin` 配置：`finalName` 为 `lynxe`，`classifier` 为 `exec-fat-jar`。构建成功后运行：

```bash
java -jar target/lynxe-exec-fat-jar.jar
```

**Thin JAR**（`target/lynxe.jar`）为普通 Maven 依赖制品，不能单独用 `java -jar` 启动应用。

#### 2. 数据库配置（可选）

> 💡 **获取您的 DashScope API 密钥**: 访问 [阿里云百炼控制台](https://bailian.console.aliyun.com/?tab=model#/api-key)，在密钥管理页面创建 API Key 并复制密钥。运行 JAR 文件后，在浏览器中访问 `http://localhost:18080`，在引导页面输入您的 DashScope API 密钥完成配置。新用户可享受 100 万 Token 输入和 100 万 Token 输出的免费额度（有效期 90 天）。
>
> **使用其他提供商?** 在 `src/main/resources/application.yml` 中更新配置，以使用您偏好的 AI 模型平台。

Lynxe 支持 H2（默认）、MySQL 以及 PostgreSQL 数据库。

**如何使用 MySQL/PostgreSQL**

1. **配置数据库连接**：
   在 `src/main/resources/`下的 application-mysql.yml/application-postgres.yml 中更新数据库配置和 jpa 方言：

   ```yaml
   spring:
     datasource:
       url: your_url
       username: your_username
       password: your_password
     jpa:
       database-platform: org.hibernate.dialect.MySQLDialect/PostgreSQLDialect
   ```

2. **激活 MySQL/PostgreSQL 配置**：
   在 `src/main/resources/application.yml` 中更新配置：

   ```yaml
   spring:
     ...
     profiles:
       active: mysql/postgres
   ```

> 💡 **注意**：应用程序将在首次启动时自动创建所需的表，使用 JPA 的 `ddl-auto: update` 配置。

#### 3. 启动应用

在**项目根目录**（与 `pom.xml` 同级），可直接运行已打包的 Fat JAR：

```bash
java -jar target/lynxe-exec-fat-jar.jar
```

或使用 Maven 启动（无需先执行 `package`）：

```bash
mvn spring-boot:run
```

#### 4. 访问您的多 Agent 仪表盘

在您的浏览器中访问 `http://localhost:18080`。

🎉 **恭喜!** 您的多 Agent 系统现已上线并准备就绪。 你可以访问https://github.com/Lynxe-public/Lynxe-public-prompts 去做一些我们认为比较有效的实践。

## 稳定版本的 Release

如果你想要之前的稳定版本，可以在这里找到：
[稳定 release 版](https://github.com/spring-ai-alibaba/Lynxe/releases)

## 🤝 如何贡献

我们热烈欢迎来自开发者社区的贡献！以下是您可以产生影响的方式：

### 贡献机会

您可以在我们的 [项目看板](https://github.com/orgs/spring-ai-alibaba/projects/1) 上找到可用的任务。

- 🐛 **报告 Bug**: [提交详细的问题报告](https://github.com/spring-ai-alibaba/Lynxe/issues)
- 💡 **功能请求**: [提出创新的增强建议](https://github.com/spring-ai-alibaba/Lynxe/issues)
- 📝 **文档**: 帮助我们提高文档的清晰度和完整性
- 🔧 **代码贡献**: [提交包含您改进的拉取请求](https://github.com/spring-ai-alibaba/Lynxe/pulls)

### 开发环境设置

```bash
# Fork 并克隆仓库
git clone git@github.com:spring-ai-alibaba/Lynxe.git
cd Lynxe

# 安装项目依赖
mvn clean install

# 应用代码格式化标准
mvn spotless:apply

# 启动开发服务器
mvn spring-boot:run
```

### 开发指南

- 遵循现有的代码风格和约定
- 为新功能编写全面的测试
- 为任何 API 变更更新文档
- 在提交 PR 前确保所有测试都通过

---

<div align="center">

**由 Spring AI Alibaba 团队用心打造 ❤️**

⭐ 如果 Lynxe 加速了您的开发之旅，请在 **GitHub 上给我们点亮一颗星**！
📚 开发者文档: [Quick Start (EN)](./README-dev-en.md) | [开发者快速入门 (中文)](./README-dev.md)

</div>
