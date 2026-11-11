# 上游源码同步

本地 `upstream` 远程和 GitHub Actions 均跟踪原始项目 `shihabal3amri/DiPlay` 的 `main` 分支，不跟踪其他衍生仓库。`Sync upstream` 工作流每小时检查一次，也会在本仓库 `main` 有推送时检查，并支持从 **Actions → Sync upstream → Run workflow** 手动启动。

发现上游新提交后，工作流会先合并到临时运行目录中的本地 `main`，再运行 `.github/workflows/android.yml` 所列的单元测试、Lint 和 Debug APK 构建。全部检查通过后才推送合并结果到本仓库 `main`；不会自动创建或发布 Release APK。

如果上游改动与本地修改冲突，或完整检查失败，工作流会失败并且不推送合并结果。需要先人工处理冲突或失败原因，再重新运行同步。`docs/CONNECTION-BASELINE.md` 用于记录连接基线和集成决策，不再阻止上游同步。
