# Spring AI Alibaba Lynxe
<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)
[![GitHub Stars](https://img.shields.io/github/stars/alibaba/spring-ai-alibaba.svg)](https://github.com/alibaba/spring-ai-alibaba/stargazers)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/spring-ai-alibaba/Lynxe)


🌍 [English](./README.md) | [中文](./README-zh.md)

[About](#-about) • [Quick Start](#-quick-start) • [Contributing](#-contributing)

[Use Cases](https://github.com/Lynxe-public/Lynxe-public-prompts)

</div>

![image](https://github.com/user-attachments/assets/b06988e4-4091-48aa-9469-ed72d8a7179c)

---

## ✨ About Lynxe(Original name: JManus）

Lynxe is a Java implementation of Manus, currently used in many applications within Alibaba Group. It is primarily used for handling exploratory tasks that require a certain degree of determinism, such as quickly finding data from massive datasets and converting it into a single row in a database, or analyzing logs and issuing alerts.


You can find some recommended Func-Agent implementations we've prepared at [Use Cases](https://github.com/Lynxe-public/Lynxe-public-prompts).

Lynxe also provides HTTP service invocation capabilities, making it suitable for integration into existing projects. For details, please refer to the [developer quick start guide](./README-dev-en.md).

## 🎯 Lynxe Product Features

### 🤖 **Pure Java Manus Implementation**:

A pure Java multi-agent collaboration implementation that provides a complete set of HTTP call interfaces, suitable for secondary integration by Java developers.

### 🛠️ **Func-Agent Mode**:

Allows you to precisely control every execution detail, providing extremely high execution determinism and completing complex repetitive processes and functions. For specific examples, see [Lynxe Use Cases - FuncAgent Use Case](https://github.com/Lynxe-public/Lynxe-public-prompts/blob/main/eng/query-plan.md)

![Image](https://github.com/user-attachments/assets/0075b210-feed-4c67-97e1-e79756493e4e)

### 🔗 **MCP Integration**:

Natively supports the Model Context Protocol (MCP) for seamless integration with external services and tools.

![Image](https://github.com/user-attachments/assets/231b05e5-9c72-43ac-85b2-fd7d0b500be8)

## 🚀 Quick Start

Get Lynxe up and running in under 5 minutes:

### Prerequisites

- 🌐 **DashScope API Key** (or alternative AI model provider)
  > 💡 **Get your DashScope API Key**: Visit [Alibaba Cloud Console](https://bailian.console.aliyun.com/?tab=model#/api-key), create an API Key in the key management page and copy the key. New users can enjoy 1 million input tokens and 1 million output tokens free quota (valid for 90 days).
- ☕ **Java 17+** (for running JAR files or source code execution) or 🐳 **Docker** (for containerized deployment)

### Method 1: Using GitHub Release (Recommended)

The release provides a **fat JAR** (single runnable JAR with all dependencies). Download and run it with Java 17+.

#### 📦 Download and Run the Fat JAR

```bash
# Download the latest executable fat JAR (release asset is named lynxe.jar; same kind as target/lynxe-exec-fat-jar.jar from Maven)
wget https://github.com/spring-ai-alibaba/Lynxe/releases/latest/download/lynxe.jar

# Or using curl
curl -L -o lynxe.jar https://github.com/spring-ai-alibaba/Lynxe/releases/latest/download/lynxe.jar

# Run (Java 17+ required)
java -jar lynxe.jar
```

> **File names**: GitHub Releases publish the runnable JAR as **`lynxe.jar`** (and **`lynxe-<version>.jar`**). A local `mvn package` produces the same executable under **`target/lynxe-exec-fat-jar.jar`** (`classifier` in `pom.xml`). Same artifact type, different filenames.

The fat JAR is the main artifact: run it directly with `java -jar`. No extra classpath or dependency setup is needed.

> 💡 **Manual Download**: Visit the [Lynxe Releases page](https://github.com/spring-ai-alibaba/Lynxe/releases) to download the latest runnable JAR (often published as `lynxe.jar` or `lynxe-<version>.jar`). For embedding Lynxe as a library, use the **thin** JAR from the same release or from Maven (`lynxe-<version>.jar` without the executable repackage); see [Using Lynxe as a library](./docs/LYNXE-AS-LIBRARY.md).

#### 🌐 Access Application

After the application starts, navigate to `http://localhost:18080` in your browser.

> 💡 **Guided Setup**: After the application starts, it will automatically display a guided setup page. On the first page, select your language (English/Chinese), then on the second page, enter your DashScope API key to complete the configuration. New users can enjoy 1 million input tokens and 1 million output tokens free quota (valid for 90 days). Visit [Alibaba Cloud Console](https://bailian.console.aliyun.com/?tab=model#/api-key) to get your free API key.

🎉 **Congratulations!** Your multi-agent system has been quickly started. You can visit https://github.com/talk-flow/public-usecase to explore some effective practices we recommend.

---

### Method 2: Using Docker (Recommended for Production)

#### 🐳 Pull and Run Docker Image

```bash
# Pull the latest Lynxe Docker image
docker pull ghcr.io/spring-ai-alibaba/lynxe:v4.7.0

# Run the container
docker run -d \
  --name lynxe \
  -p 18080:18080 \
  ghcr.io/spring-ai-alibaba/lynxe:v4.7.0
```

#### 🔧 Advanced Docker Configuration

**Run with data persistence (recommended for production):**

```bash
# Create a directory for data persistence
mkdir -p ./lynxe-data

# Run with volume mounting
docker run -d \
  --name lynxe \
  -p 18080:18080 \
  -v $(pwd)/lynxe-data:/app/data \
  ghcr.io/spring-ai-alibaba/lynxe:v4.7.0
```

**Run with custom environment variables:**

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

#### 🌐 Access Application

After the container starts, navigate to `http://localhost:18080` in your browser.

> 💡 **Guided Setup**: After the application starts, it will automatically display a guided setup page. On the first page, select your language (English/Chinese), then on the second page, enter your DashScope API key to complete the configuration. New users can enjoy 1 million input tokens and 1 million output tokens free quota (valid for 90 days). Visit [Alibaba Cloud Console](https://bailian.console.aliyun.com/?tab=model#/api-key) to get your free API key.

#### 📋 Useful Docker Commands

```bash
# View container logs
docker logs -f lynxe

# Stop the container
docker stop lynxe

# Start the container
docker start lynxe

# Remove the container
docker rm lynxe
```

🎉 **Congratulations!** Your multi-agent system is now running in Docker. You can visit https://github.com/talk-flow/public-usecase to explore some effective practices we recommend.

---

### Method 3: Running from Source Code (Alternative)

#### 1. Clone and Build

```bash
git clone https://github.com/spring-ai-alibaba/Lynxe.git
cd Lynxe

# Build the project (produces thin JAR and executable fat JAR in target/)
mvn clean package -DskipTests
```

The **Spring Boot executable fat JAR** is defined in `pom.xml` (`spring-boot-maven-plugin` with `finalName` `lynxe` and classifier `exec-fat-jar`). After a successful build, run:

```bash
java -jar target/lynxe-exec-fat-jar.jar
```

The **thin** JAR (`target/lynxe.jar`) is the normal Maven artifact for use as a dependency; it is not suitable for `java -jar` on its own.

#### 2. Database Configuration (Optional)

> 💡 **Get your DashScope API Key**: Visit [Alibaba Cloud Console](https://bailian.console.aliyun.com/?tab=model#/api-key), create an API Key in the key management page and copy the key. After running the JAR file, access `http://localhost:18080` in your browser, and enter your DashScope API key on the guided setup page to complete the configuration. New users can enjoy 1 million input tokens and 1 million output tokens free quota (valid for 90 days).
>
> **Using other providers?** Update the configuration in `src/main/resources/application.yml` to use your preferred AI model platform.

Lynxe supports both H2 (default)、MySQL and PostgreSQL databases.

**How To Use MySQL/PostgreSQL**

1. **Configure Database Connection**:
   Update the database configuration and JPA database-platform in the application-mysql.yml/application-postgres.yml under 'src/main/resources/':

   ```yaml
   spring:
     datasource:
       url: your_url
       username: your_username
       password: your_password
     jpa:
       database-platform: org.hibernate.dialect.MySQLDialect/PostgreSQLDialect
   ```

2. **Activate MySQL/PostgreSQL Profile**:
   Update configuration in `src/main/resources/application.yml`:

   ```yaml
   spring:
     ...
     profiles:
       active: mysql/postgres
   ```

> 💡 **Note**: The application will automatically create required tables on first startup using JPA's `ddl-auto: update` configuration.

#### 3. Launch the Application

From the project root (same directory as `pom.xml`), either run the packaged fat JAR:

```bash
java -jar target/lynxe-exec-fat-jar.jar
```

Or start with Maven (no prior `package` required):

```bash
mvn spring-boot:run
```

#### 4. Access Your Multi-Agent Dashboard

Navigate to `http://localhost:18080` in your browser.

🎉 **Congratulations!** Your multi-agent system is now live and ready for action. You can visit https://github.com/Lynxe-public/Lynxe-public-prompts to explore some effective practices we recommend.

## Stable Release

you can find stable release from here:
[release](https://github.com/spring-ai-alibaba/Lynxe/releases)

## 🤝 Contributing

We enthusiastically welcome contributions from the developer community! Here's how you can make an impact:

### Contribution Opportunities

You can find available tasks on our [project board](https://github.com/orgs/spring-ai-alibaba/projects/1).

- 🐛 **Bug Reports**: [Submit detailed issue reports](https://github.com/spring-ai-alibaba/Lynxe/issues)
- 💡 **Feature Requests**: [Propose innovative enhancements](https://github.com/spring-ai-alibaba/Lynxe/issues)
- 📝 **Documentation**: Help us improve clarity and completeness
- 🔧 **Code Contributions**: [Submit pull requests](https://github.com/spring-ai-alibaba/Lynxe/pulls) with your improvements

### Development Environment Setup

```bash
# Fork and clone the repository
git clone git@github.com:spring-ai-alibaba/Lynxe.git
cd Lynxe

# Install project dependencies
mvn clean install

# Apply code formatting standards
mvn spotless:apply

# Start the development server
mvn spring-boot:run
```

### Development Guidelines

- Follow existing code style and conventions
- Write comprehensive tests for new features
- Update documentation for any API changes
- Ensure all tests pass before submitting PRs

---


**Crafted with ❤️ by the Spring AI Alibaba Team**

⭐ **Star us on GitHub** if Lynxe accelerated your development journey!

📚 Developer Docs: [Quick Start (EN)](./README-dev-en.md) | [开发者快速入门 (中文)](./README-dev.md)

</div>
