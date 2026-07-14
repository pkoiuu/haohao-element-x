# 修复 plugins 模块编译失败问题的完整诊断报告

## 🔍 问题分析

### 错误信息
```
e: Source file or directory not found:
...plugins\build\generated-sources\kotlin-dsl-external-plugin-spec-builders\
kotlin\gradle\kotlin\dsl\plugins\_0fedb9fc7c9839ee4edcd591d8128b6a\PluginSpecBuilders.kt
```

### 根本原因
1. **哈希不匹配**: `generateExternalPluginSpecBuilders` 任务生成新哈希目录，
   但 `compilePluginsBlocks` 任务仍引用旧哈希目录
2. **AGP 兼容性**: Android Gradle Plugin 9.2.1 与 Kotlin DSL 预编译脚本插件存在兼容性问题
3. **依赖残留**: dependency-analysis 插件 (3.14.1) 的依赖仍在 plugins/build.gradle.kts 中

## 🛠️ 解决方案

### 方案 1: 降级 AGP（推荐）
修改 gradle/libs.versions.toml 第 6 行：
```toml
android_gradle_plugin = "8.7.3"  # 从 9.2.1 降级
```

### 方案 2: 移除 dependency-analysis 依赖
修改 plugins/build.gradle.kts 第 25 行：
```kotlin
// implementation(libs.autonomousapps.dependencyanalysis.plugin)  # 注释掉
```

### 方案 3: 完全清理重建
```powershell
./gradlew.bat --stop
Remove-Item -Recurse -Force .gradle
Get-ChildItem -Directory -Recurse -Filter "build" | Remove-Item -Recurse -Force
./gradlew.bat clean assembleFdroidDebug --no-build-cache --no-configuration-cache --rerun-tasks
```

## 📊 环境信息
- Gradle: 9.5.1
- Java: 21.0.1 LTS
- AGP: 9.2.1 (不兼容)
- Kotlin: 2.4.0
- OS: Windows

## 📝 相关文件
- gradle/libs.versions.toml (AGP 版本配置)
- plugins/build.gradle.kts (plugins 模块依赖)
- build-debug.log (错误日志)
- build-complete-clean.log (完全清理后的错误日志)

## 🔗 参考资料
- Gradle Issue #30679: compilePluginsBlocks 随机失败问题
- Kotlin Slack: Kotlin 2.1 + Gradle 8.10 预编译脚本插件兼容性讨论
