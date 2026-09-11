# v2 更新步骤

只更新 Open Camera 名单版；不要用于其他相机或 list photo。

## 1. 覆盖补丁文件

解压更新包，将里面的 `src`、`tools`、`tests`、`.github`、`docs` 文件夹和根目录 `README.md`、`UPGRADE-v2.md` 复制到：

```text
H:\temp\Open-Camera-\
```

与原文件夹合并，替换同名文件。不要多套一层更新包文件夹，也不要把子目录文件散放到仓库根目录。

## 2. 提交

在 PowerShell 依次执行；某条报错就先停止，不要忽略错误继续：

```powershell
cd "H:\temp\Open-Camera-"
git add -- src tools tests .github/workflows docs/v2-ui-preview.html docs/v2-verification.txt README.md UPGRADE-v2.md
git commit -m "Support multiple photos per ID and manual selection"
git push
```

此更新不需要重新配置 Git 用户名或删除现有仓库。

## 3. 新建一次构建

进入 GitHub：Actions → Build Open Camera Name Queue → Run workflow → 分支 main → Run workflow。

不要在旧运行页面点击 Re-run jobs，否则仍使用旧提交。

成功后下载 `OpenCamera-NameQueue-v2-test-build`，解压其中的 APK。

## 4. 安装和验收

安装后的应用名称为「Open Camera 名单版 2」。无需卸载 v1；v2 使用不同的测试应用标识，名单需要重新粘贴，旧版计数不会自动迁移。

先用 A、B、C 测试：A 拍 3 张 → 选择 C 拍一张 → 选回 A 再拍，应生成 A_001～A_004 和 C_001，默认不会自己跳号。

水平辅助默认关闭，在设置中手动开启后看细线与小十字效果。完整行为与限制见 README.md。

当前已通过本地逻辑、合成源码和静态约束测试；还需要这次 GitHub Android 编译、安装和实机验收。此更新包本身不含新 APK。
