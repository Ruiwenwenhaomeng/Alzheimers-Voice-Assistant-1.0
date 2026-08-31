# Local RDS seed

`db_dump.sql` 是从 RDS 导出 ZIP 中解压出的完整结构和数据，仅用于本地恢复。
该文件包含账号及健康相关信息，已通过项目根目录的 `.gitignore` 排除，禁止提交。

导入本机 MySQL 时按以下顺序执行：

1. 创建 `alz_system` 数据库。
2. `seed/db_dump.sql`：恢复原 RDS 结构和数据。
3. `migrations/V002__screening_audit.sql`：补齐当前后端使用的筛查审计字段。

如果需要从附件重新准备文件，请把 ZIP 内的 `db_dump.sql` 解压到本目录，并确保文件采用 UTF-8 编码。
