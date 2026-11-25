# Jenkins Custom SVN Publish Plugin

将构建产物发布（提交）到 Subversion 仓库的 Jenkins 插件。
A Jenkins plugin that publishes build artifacts to a Subversion repository.

## 功能

- **Freestyle + Pipeline 双入口**：Pipeline 提供 `customSvnPublish` 步骤；自由风格任务提供 "Custom SVN Publish" 构建后操作
- **三种认证方式**（单选，切换即时展开对应输入项，无需保存后刷新）：
  - Jenkins 凭据：下拉列出当前任务有权读取的用户名/密码凭据，配置页即可选用
  - 直接输入用户名/密码（`Secret` 加密存储于任务配置）
  - 匿名：不向服务器发送任何凭据
- **目录自动补全**：目标目录输入框实时列出仓库已有子目录（输入 `trunk/` 可逐级浏览），避免手拼路径；凭据模式下用所选凭据浏览，私有仓库同样可列目录
- **灵活的目标定位**：Ant 通配符 source/exclude、目录名当源发布整棵子树、`target` 子目录发布
- **可选清理**：删除目标目录中已不在发布集合的文件，使目标与 workspace 完全一致；`targetExclude` 保护指定路径不被清理删除
- **纯 Java SVN 客户端**：内嵌 SVNKit 1.10.11，agent 无需安装 svn 命令行
- **单次原子提交**：所有 add/update/delete 在同一 SVN revision 完成

## Pipeline 用法

```groovy
// 方式一：Jenkins 凭据
customSvnPublish(
  url: 'https://svn.example.com/repos/releases',
  credentialsId: 'svn-creds',
  source: 'target/*.jar',
  target: 'v1.0',
  commitMessage: 'Publish from build #${BUILD_NUMBER}',
  cleanup: false
)

// 方式二：直接账号密码
customSvnPublish(
  url: 'https://svn.example.com/repos/releases',
  username: 'deploy', password: 'secret',
  source: 'target/*.jar'
)

// 方式三：匿名 + 目录源 + 清理时保留指定路径
customSvnPublish(
  url: 'svn://example.com/pub',
  source: 'dist',                   // 目录名当源：发布整棵子树
  cleanup: true,
  targetExclude: 'keep/**,**/*.tmp' // 清理时永不删除这些远端路径
)
```

可选参数：`exclude`（源排除模式）、`target`、`commitMessage`、`cleanup`、`targetExclude`（目标排除，清理时保留匹配路径）。

## Freestyle 用法

任务配置 → 构建后操作 → **Custom SVN Publish**：

| 字段 | 说明 |
|------|------|
| SVN 仓库地址 | 如 `https://svn.example.com/repos` |
| 认证 | 三选一：**Jenkins 凭据**（下拉选择，仅列任务可读的用户名/密码凭据）/ **用户名和密码**（手填，密码加密存储）/ **匿名**（不发送凭据）。切换即时生效 |
| 来源 | workspace 相对路径或 Ant 通配符，如 `target/*.jar`；纯目录名（如 `dist`）发布整棵子树 |
| 来源排除 | 可选，源排除，如 `**/*.tmp` |
| 目标 | 可选；下拉自动列出仓库子目录，逐级可浏览（字段名 `target`） |
| 目标排除 | 可选；清理时永不删除的远端路径模式，如 `keep/**` |
| 提交信息 | 支持 `${BUILD_NUMBER}` 环境变量展开 |
| **高级** → 清理目标目录 / Clean target | 删除不再存在的文件（尊重目标排除），默认关闭 |

主表单只呈现上表前 7 项；「清理目标目录」收在 **高级** 折叠区内，需要时展开即可。

## 构建插件

要求 JDK 17+ 与 Maven 3.9.6+（本仓库自带 `.tools-settings.xml` 华为云镜像配置）：

```bash
mvn -s .tools-settings.xml package
```

产物为 `target/custom-svn-publish.hpi`。

## 安装

Jenkins → 系统管理 → 插件管理 → Advanced → 上传插件，选择 `custom-svn-publish.hpi`。

## 工作原理

1. 展开环境变量后按 source/exclude 扫描 workspace 得到发布清单
2. 解析认证：credentialsId → Jenkins 凭据库；否则 username/password → 内联；否则匿名
3. 表单的目录下拉通过 `doFillTargetItems` 实时 `getDir` 列出仓库子目录
4. SVNKit commit editor 构造一次事务：新文件 add、已有文件 update、cleanup 时删除多余文件与空目录
5. 构建日志输出每个文件操作（A/U/D 前缀）与最终 revision

## License

MIT
