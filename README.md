# 旦知（Danzhi）workspace

复旦校园小助手：信匣、课表、生活码、空教室、笔记。给下一轮对话用的完整工程快照。

## 下一对话请发这些链接

- 仓库：https://github.com/trolleydogedl/danzhi-workspace
- GitHub zip：https://github.com/trolleydogedl/danzhi-workspace/archive/refs/heads/main.zip
- Litterbox 直链 zip（72 小时）：见本轮聊天最后一条

把 zip 解到 `/workspace` 后即可继续改代码、打安装包。**不要把 UIS 密码提交进仓库。**

## 当前版本

Android 安装包 **1.31.0**。相对 1.30 的修复：

- 生活码：官方页按普通网页拉（iPhone Safari，不再误加 Ajax 头），并走 workflow1 一卡通入口；仍然不用隐藏网页
- 课表：`/course-table` 索引 400 时改走 `/info` 和 print-data，不再把整源报成 HTTP 400
- 课堂：断流重试加到 5 次，并沿异常链识别 unexpected end of stream

网页预览只是看板。登录、巡检、闸机请用安装包。
