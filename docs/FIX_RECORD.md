# 修复记录

本文件汇总 AICryptoWallet 已完成的缺陷修复。每个条目包含：问题描述、根因、修复方案、涉及文件、验证结果。

---

## 修复 1：软键盘在 Tab 切换后残留，遮挡底部导航栏

- **日期**：2026-08-29
- **版本**：v3.0.5 → 后续版本
- **端**：Android（iOS 同步修复，见下）

### 问题现象

在「发现」页点击 DApp 搜索框弹出软键盘后，直接切换到其他主 Tab，软键盘仍停留在屏幕上，
遮挡底部 5 个主 Tab 导航栏，用户无法正常点按其它 Tab，需手动返回发现页触发收起，体验异常。

### 根因

`HomeActivity#switchTab(int)` 在切换主 Tab 时只更新了内容区域的可见性（`setVisibility`），
**未处理软键盘状态**：既没有调用 `InputMethodManager` 收起键盘，也没有释放仍持有焦点的
发现页搜索框 `etDappSearch`。因此键盘与输入法焦点在切 Tab 后残留。

### 修复方案

1. **`HomeActivity#switchTab(int)`**：切换 Tab 前先调用 `hideKeyboard()` 收起软键盘；
   当离开「发现」页（`index != 3`）时额外对 `etDappSearch` 调用 `clearFocus()` 释放焦点。
2. **新增 `HomeActivity#hideKeyboard()`**：通过 `InputMethodManager.hideSoftInputFromWindow()`
   强制收起键盘；当 `getCurrentFocus()` 为空时回退到窗口 `DecorView` 的 windowToken 兜底，
   保证即使焦点已丢失也能把键盘收起。
3. **`AndroidManifest.xml`**：为 `HomeActivity` 配置
   `android:windowSoftInputMode="adjustResize|stateHidden"`：
   - `adjustResize`：键盘弹出时窗口内容自动上移（底部导航随窗口上移），不遮挡导航栏；
   - `stateHidden`：Activity 启动时默认隐藏软键盘，避免从其它页面返回时键盘自动弹出。

### 涉及文件

- `app/src/main/java/com/aicryptowallet/app/HomeActivity.java`
- `app/src/main/AndroidManifest.xml`

### iOS 端同步修复

iOS 端同类问题采用等价逻辑修复（iOS 目录为独立工程，不在本仓库提交范围内）：
`AICryptoWallet/Views/MainTabView.swift` 的 Tab 点击改走 `switchTab(_:)`，在切换时通过
`UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), ...)` 全局收起软键盘。

### 构建与验证

- 依赖 GitHub Packages 私有仓库（Trust Wallet Core），认证凭据位于 `local.properties`
  （`gpr.user` / `gpr.key`，需 `read:packages` 权限）。
- Java 环境：Temurin JDK 17。
- 构建命令：`gradlew assembleDebug`，产物
  `app/build/outputs/apk/debug/AICryptoWallet-v3.0.5-debug.apk`。
- 真机/模拟器验证（无需重签名）：
  1. 安装新 debug 包后进入「发现」页，点击搜索框 → 软键盘弹出（`mInputShown=true`）。
  2. 切换到「资产」Tab → 软键盘收起（`mInputShown=false`）。
  3. 截图确认键盘完全消失，资产页正常显示，底部导航无遮挡。