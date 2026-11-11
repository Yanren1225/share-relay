# 文案审核记录

审核工具：dsh-copy-review 插件（`copy_review`），模型 `copy-review-gemini/gemini-4-argon`。
审核范围：应用名、设置页、弹窗、Toast、分享记录页的全部用户可见文案（共 58 条），以及英文 / 繁体中文翻译。

## 结论

- 评分 7.5 / 10；主要问题是说明文案偏长、术语不统一、存在绝对化用语。
- 应用名最终定为「接力分享」/ **ShareRelay**（包名 `ren.imyan.sharerelay`）。初版曾用「分享桥 / ShareBridge」，因为 ShareBridge 与 Play 上的跨设备传文件应用撞名、且"桥"容易误解为 VPN/跨设备工具，故改名。

## 已修改

| 条目 | 修改前 | 修改后 |
| --- | --- | --- |
| mode_copy_desc | 复制到本应用后再分享，最稳但大文件较慢 | 先生成副本再分享，兼容性好但大文件慢（去掉绝对化用语「最稳」） |
| banner_degraded_root | 未授权 Root（su 不可用），本次将降级为透传 | 未获 Root 权限，本次改为透传（去掉技术黑话 su） |
| banner_degraded_auto | 未检测到 Root，自动模式将降级为透传 | 未检测到 Root，自动模式已改为透传 |
| mode_auto_desc | 有 Root 时直读原文件，否则透传并等待回执 | 有 Root 则直读，否则透传并等回执 |
| mode_root_desc | 用 su 读取原文件，零拷贝、无需等待回执（需 Root） | 需 Root：直接读取原文件，免副本、免等回执 |
| mode_passthrough_desc | 原样转发来源 URI，需保持页面直到系统分享面板关闭 | 直接转发，需等待系统分享面板关闭 |
| error_read_failed | 读取分享内容失败，可在设置中切换为「复制」模式 | 读取失败，请到设置切换为「复制」 |
| pref_cleanup_title | 清理副本文件 | 清理副本 |
| pref_cleanup_summary_count | 当前有 %1$d 个副本文件 | 当前有 %1$d 个副本 |
| pref_cleanup_summary_leftover | 上次分享遗留 %1$d 个副本文件 | 上次遗留 %1$d 个副本 |
| toast_cleaned | 已清理 %1$d 个文件 | 已清理 %1$d 个副本 |
| error_chooser_failed | 调起系统分享失败：%1$s | 调起分享面板失败：%1$s |
| pref_exclude_self_title | 排除自己 | 排除本应用 |
| pref_exclude_self_summary | 系统分享面板里不再出现「接力分享」自己 | 不在系统分享面板中显示「接力分享」 |
| pref_exclude_qq_summary | 防止转发回 QQ，造成来回循环 | 避免再次转发回 QQ 造成死循环 |
| pref_keep_log_title | 记录分享历史 | 保存分享记录 |

## 翻译

- 英文（`values-en`）：术语统一为 share sheet / pass-through / result / copy / Root direct；设置项标题 Title Case，句子 Sentence case。
- 繁体中文（`values-zh-rTW`）：按台湾用语（設定 / 檔案 / 紀錄 / 複製 / 傳輸 / 無限迴圈）。
- 所有 `%1$s`、`%1$d` 等占位符及其顺序均已校验一致。


