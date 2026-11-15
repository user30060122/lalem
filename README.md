# 拉了么 · 新版

用 Android Studio 打开项目根目录，同步 Gradle 后运行 `app`。应用包名是 `com.example.lalem`，最低支持 Android 7.0。也可以运行 `./gradlew :app:assembleDebug`，Windows 上使用 `gradlew.bat :app:assembleDebug`。

## 使用

1. 用手机号和密码登录。在「我的」可查看并修改昵称和手机号。已有账号升级后，登录一次即可补齐手机号，或者在个人资料中补填。修改手机号后用新号码登录和添加好友，原有打卡及好友关系继续可用。
2. 打卡页记录状态、时间和可选重量。点月历选日查看记录或补记，点记录可编辑或删除。所有操作先写入本机 SQLite，已登录账号的待同步记录会在联网后由 Android 后台任务上传，再刷新好友共享数据。第一次使用也可选择「先离线打卡」，记录会归入随后登录的账号。旧版仅存在云端的历史首次联网同步后可离线查看。
3. 用两台设备或轮流登录两个账号。从「我的」查看好友码，在「朋友」用对方手机号和好友码发送申请，接受后刷新两边的朋友页。
4. 「统计」支持近 7 天和 30 天，可点柱状图查看单日次数，环形图显示状态分布。「我的」可以开启好友周榜和月榜，领奖台按打卡次数排列，同次数并列，零次不颁奖。

## Bmob 数据

联网功能需要在本机项目根目录的 `local.properties` 中添加以下一行，然后重新构建。

```properties
bmob.applicationId=你的 Bmob Application ID
```

该值是 Bmob 客户端应用标识，构建后的 APK 包含该标识。Bmob 的管理密钥留在服务器或控制台。缺少该配置时，可以通过「先离线打卡」体验本机记录功能。

| 表 | 字段与用途 |
| --- | --- |
| `_User` | `username` 是由手机号派生的登录标识。自定义字段 `nickname`、`loginPhone`、`friendCode`、`directoryId` 为字符串，`sharedLinkIds` 是字符串数组。`loginPhone` 随本人资料保存，ACL 仅授权本人。 |
| `PoopRecord` | `userId`、`poopType`、`clientId` 为字符串，`poopTime`、`weight` 为数值。`clientId` 用于识别重试的同一条记录。每条记录的 ACL 仅授权本人。 |
| `FriendDirectory` | `userId`、`lookup`、`nickname` 为字符串，用于按手机号与好友码的组合查找账号。目录条目允许查询，写权限给账号本人。 |
| `FriendLink` | `senderId`、`recipientId`、`senderName`、`recipientName`、`status`、`senderRecords`、`recipientRecords` 为字符串。链接 ACL 给双方，近期记录在双方同意后同步。 |

新库允许 Android SDK 首次写入创建表和字段时，可从空库开始。如果控制台禁止客户端新增结构，需要按上表预建，再保留必要的数据读写权限。首次真机联调应验证账号 A 无法读取账号 B 的 `PoopRecord`，双方接受申请后才能看到共享记录，删除关系后 `FriendLink` 消失。好友同步依赖应用联网刷新，置顶、榜单开关与周冠军弹窗记录保存在本机。

## 版本更新

App 进入前台时会检查新版的蒲公英安装页（https://www.pgyer.com/laleme-android-t），比较页面显示的数字版本名。用户可忽略单个版本，或打开该安装页下载。「我的」可手动检查更新。离线或安装页不可用时，自动检查会静默结束。

发布更新时递增 `versionCode` 和数字 `versionName`，使用与上一版相同的签名，把 APK 上传到现有的 `com.example.lalem` 蒲公英应用，并让新版本显示在安装页上。账户 API Key 只用于管理发布。

[Bmob Android SDK 快速入门](https://doc.bmobapp.com/data/android/) · [Bmob ACL 开发文档](https://doc.bmobapp.com/data/android/develop_doc/)
