# tea-mall-backend

茶叶商城的后端服务，为 `tea-mall-admin` 管理后台和 `tea-mall-app` 用户端提供统一 API、业务规则、数据库访问和安全控制。

## 项目简介

本项目基于若依后端扩展，包含用户会话、商品与 SKU、订单与售后、优惠券、积分兑换、合伙人/直属佣金、茶友邀请、内容社区、客服工单、提现审核及微信身份绑定等服务。数据库脚本统一位于 `sql/`：`schema/` 用于初始化，`migrations/` 按日期顺序执行增量变更，`tests/` 存放测试夹具。带 `.local.sql` 的文件仅供本地使用，不应提交。

## 技术栈

- Java 8、Maven、Spring Boot 2.5、Spring Security
- MyBatis / Spring JDBC、若依 3.9
- MySQL、Redis、Quartz（按部署配置启用）
- 微信小程序身份绑定、测试支付/短信适配层

## 关联仓库

| 项目 | 说明 | GitHub |
|---|---|---|
| tea-mall-backend | 后端服务（当前仓库） | [tea-mall-backend](https://github.com/sickelgroupe-create/tea-mall-backend) |
| tea-mall-admin | 管理后台 | [tea-mall-admin](https://github.com/sickelgroupe-create/tea-mall-admin) |
| tea-mall-app | 用户端 | [tea-mall-app](https://github.com/sickelgroupe-create/tea-mall-app) |

## 快速启动

1. 准备 Java 8、Maven、MySQL 和 Redis。
2. 复制应用配置示例，填写数据库、Redis、微信及第三方服务配置；密钥只放在本地环境或部署平台，不提交到 Git。
3. 新数据库先按 `sql/SETUP.md` 执行 `sql/schema/bootstrap.example.sql`（替换 BCrypt 占位符），再按顺序执行 `sql/migrations/`；已有数据库只执行缺失迁移。
4. 构建并运行：

```sh
mvn clean package
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

## 项目结构

- `ruoyi-admin/`：Web 启动模块、控制器、商城业务服务和资源配置
- `ruoyi-common/`：公共模型、工具和配置
- `ruoyi-framework/`：安全、异常、日志和 Web 框架配置
- `ruoyi-system/`：若依系统管理模块
- `sql/schema/`：数据库基础结构和初始化数据
- `sql/migrations/`：按日期排序的增量迁移及回滚说明
- `sql/tests/`：测试夹具

## 简历描述示例

参与茶叶商城后端建设，基于 Spring Boot 和 MySQL 完成商品、订单、积分、优惠券、直属佣金、邀请奖励及售后等核心业务，并为管理后台和用户端提供统一安全 API。
