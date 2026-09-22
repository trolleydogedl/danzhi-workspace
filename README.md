# 旦知（Danzhi）workspace

复旦校园小助手：信匣、课表、生活码、空教室、笔记。给下一轮对话用的完整工程快照。

## 下一对话请发这些链接

- 仓库：https://github.com/trolleydogedl/danzhi-workspace
- GitHub zip：https://github.com/trolleydogedl/danzhi-workspace/archive/refs/heads/main.zip
- Litterbox 直链 zip（72 小时）：见本轮聊天最后一条

把 zip 解到 `/workspace` 后即可继续改代码、打安装包。**不要把 UIS 密码提交进仓库。**

## 当前版本

Android 安装包 **1.29.0**。相对 1.28 的修复：

- 版本号继续往上，不再回退
- 生活码彻底不再开隐藏网页（闪退/卡死来源），只走 HTTP + 微信 UA
- 即将到期：作业提醒和同一条作业会合成一条，不再画两遍
- 手机号要按「确认手机号」才会保存
- 课堂 Canvas 不再要 gzip，断流会重试
- 课表 print-data 不再带 `Accept: application/json`（这会 400）；400 也不再整源报死
- 后台巡检：登录后约 20–25 秒首轮；设置页能看到上次后台巡检时间
- 新增笔记：添加、编辑、删除、置顶，安装包可附照片和文件

网页预览只是看板。登录、巡检、闸机请用安装包。
