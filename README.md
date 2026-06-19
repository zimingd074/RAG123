# Ragent

Ragent 是一个基于 Spring Boot 3 和 React 18 的 Agentic RAG 平台，覆盖文档入库、知识库管理、多路检索、重排序、会话记忆、模型路由、MCP 工具调用和链路追踪。

[![GitHub stars](https://img.shields.io/github/stars/zimingd074/ragent?style=flat-square&logo=github)](https://github.com/zimingd074/ragent/stargazers)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue?style=flat-square)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square)](pom.xml)
[![React](https://img.shields.io/badge/React-18-61DAFB?style=flat-square&logo=react)](frontend/package.json)

![Ragent](assets/ragent-ai-banner.png)

## 功能

- 文档解析、分块、向量化与可编排入库流水线
- 向量、关键词等多路检索及结果去重、重排序
- 意图识别、问题改写、多轮会话记忆与流式回答
- 多模型路由、健康检查、熔断和自动降级
- MCP 工具调用、管理后台和 RAG 全链路追踪
- 用户认证、限流、消息反馈和知识库管理

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3、MyBatis-Plus |
| 前端 | React 18、TypeScript、Vite、Tailwind CSS |
| 数据 | PostgreSQL、Redis、Milvus / pgvector |
| 基础设施 | RocketMQ、S3、Docker |

## 项目结构

```text
ragent
├── bootstrap      # Web 入口与业务模块
├── framework      # 通用基础设施
├── infra-ai       # 模型、Embedding 与 Rerank 适配
├── mcp-server     # MCP 示例服务
├── frontend       # React 管理端与问答界面
├── resources      # Docker、SQL 与格式化资源
└── docs           # 补充文档
```

## 本地开发

后端：

```bash
./mvnw clean package -DskipTests
./mvnw -pl bootstrap spring-boot:run
```

前端：

```bash
cd frontend
npm install
npm run dev
```

运行前请根据本地环境配置 PostgreSQL、Redis、向量数据库、对象存储和模型服务。示例配置位于 `bootstrap/src/main/resources`。

## 仓库

- GitHub: https://github.com/zimingd074/ragent
- Issues: https://github.com/zimingd074/ragent/issues

## 上游与许可

本仓库基于 [nageoffer/ragent](https://github.com/nageoffer/ragent) 进行维护和定制，已修改项目元信息、包命名、文档和界面链接。原项目及本仓库均按 [Apache License 2.0](LICENSE) 分发。
