# 自定义 ContentProvider 由 Manifest 引用，AGP 通常会自动保留；
# 这里显式声明，避免以后改构建配置时被裁剪。
-keep class ren.imyan.sharebridge.RelayProvider { *; }

# 保留源文件名与行号，方便看崩溃堆栈
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*

# Compose / Miuix 的 consumer rules 已随 AAR 提供，这里不再额外保留
