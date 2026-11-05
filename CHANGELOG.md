# Changelog

## [1.6.2](https://github.com/lyizhuo/WearPod/compare/v1.6.0...v1.6.2) (2026-11-05)


### Features

* **offline-web:** 当前订阅支持多选批量删除 ([fbdcf7a](https://github.com/lyizhuo/WearPod/commit/fbdcf7ad4e4b347dfdc97d83f71ebfc921f66ca1))
* **offline:** 网页显式完成后才结束本地上传会话 ([1832696](https://github.com/lyizhuo/WearPod/commit/1832696ccc7b11bd9f75650a3994fe496524e219))
* **ui:** 收紧收件箱日期头并统一本地上传页 ([d1e957f](https://github.com/lyizhuo/WearPod/commit/d1e957fdea36e8522f712a1ad3c86886e5a58312))


### Bug Fixes

* **downloads:** 滑动删除改为二次确认并收敛进度重组 ([d197516](https://github.com/lyizhuo/WearPod/commit/d197516fa47f94fb10887b3de443d5f4da15a2b8))
* **notifications:** 下载通知改用 WearPod 单色标记 ([6e86f58](https://github.com/lyizhuo/WearPod/commit/6e86f589de7ea20b08a70fb84442cde997c0b163))


### Performance Improvements

* **downloads:** 放大下载缓冲并品牌化通知图标 ([68b06ef](https://github.com/lyizhuo/WearPod/commit/68b06efc34dc53cc676e6fcb2f5f238038786c32))


### Continuous Integration

* **release:** derive versionCode from semver ([bd1b40c](https://github.com/lyizhuo/WearPod/commit/bd1b40c4d7160ef5b036f18b09de23e8e35b9bf1))

## [1.6.0](https://github.com/lyizhuo/WearPod/compare/v1.5.0...v1.6.0) (2026-11-03)


### Features

* **about:** 按 Show Notes 的排版渲染 About 页 ([5395a3b](https://github.com/lyizhuo/WearPod/commit/5395a3b8907d9176301cbe5f6f263910f52baa08))
* **media:** 补全媒体元数据与媒体按键接收 ([53278d4](https://github.com/lyizhuo/WearPod/commit/53278d4ca04ac903acb97e128128462c5fa372d0))
* **notification:** 媒体通知补 ongoing、Wear 持续活动与 OPPO Indicator ([d9ab5e9](https://github.com/lyizhuo/WearPod/commit/d9ab5e996702b9b0fe91b71b52c4141c1725708e))
* **offline:** 手表热点 + 本地网页上传，订阅可存手表本地 ([73de406](https://github.com/lyizhuo/WearPod/commit/73de406c26c80e879d0bcba9c55d71b32af797e1))
* **offline:** 按设计稿重做引导页与结束页 ([7a8c621](https://github.com/lyizhuo/WearPod/commit/7a8c621396d2ac68984d8d3371d5206d1728a55a))
* **rotary:** 适配小米表冠旋转触感 ([1275b94](https://github.com/lyizhuo/WearPod/commit/1275b94a6788c481fa8725111e3b818f9ce9ec57))


### Bug Fixes

* **main:** 回跳注册无动画过渡，压制小米表盘点击的系统启动动画 ([e369de4](https://github.com/lyizhuo/WearPod/commit/e369de457714516868fe7a09b3505716f5a8e09a))
* **main:** 用 singleTask 稳定通知回跳与任务关联 ([c1e33b3](https://github.com/lyizhuo/WearPod/commit/c1e33b3defd92021326d13709f7e08b1ee5f8d89))
* **media:** 真 Wear 保持非 ongoing 通知，缓冲期不再被任务移除终止 ([759a191](https://github.com/lyizhuo/WearPod/commit/759a1917b50bb5257426cf4dd1c345b1dbe610c4))
* **notifications:** 下载也触发通知权限，清理 Kotlin 2.4 警告 ([41e9786](https://github.com/lyizhuo/WearPod/commit/41e97861d0d93a15fcd03ce67c62a71db27fb916))
* **notification:** 修正异步通知覆盖与暂停后 ongoing 状态 ([a69459c](https://github.com/lyizhuo/WearPod/commit/a69459cbb8ec8ae1f6ae4f60441089863075a9d7))
* **notification:** 小米持续活动图标改用应用图标 ([0ac854a](https://github.com/lyizhuo/WearPod/commit/0ac854a4c61ed993287fd0a58bf885a47c3b0b80))
* **offline:** 串行化订阅写入并给变更接口加同源校验 ([349a97b](https://github.com/lyizhuo/WearPod/commit/349a97bf49daae7b7ac2792217f31cb6cf39f1f1))
* **offline:** 删引导页动画、按 M3 重排间距、删除也进结束页、网页中英双语 ([d3f9e9a](https://github.com/lyizhuo/WearPod/commit/d3f9e9aa3885927dc51e133126adba2998795408))
* **offline:** 局域网判定改为网络感知，并给 Running 期加网络看门狗 ([b39b2ab](https://github.com/lyizhuo/WearPod/commit/b39b2ab7949f3a0418403f81bdfc5326f339ced1))
* **platform:** 适配按应用语言、通知权限、备份与包可见性 ([6869042](https://github.com/lyizhuo/WearPod/commit/6869042e75cd9fd5e3970c0c685d0b7564d55060))
* **power:** 降低后台写盘与导入页亮屏，下载增加前台保活 ([f0b9852](https://github.com/lyizhuo/WearPod/commit/f0b9852b0cdb68f583049d27d1600b044956c2a9))
* **ui:** 修订阅源切换、应用语言与暂停位置的状态一致性 ([64ea0ac](https://github.com/lyizhuo/WearPod/commit/64ea0ac3fa17795a2d1e581e78f94b5597ec348b))

## [1.5.0](https://github.com/lyizhuo/WearPod/compare/v1.4.9...v1.5.0) (2026-10-12)


### Features

* **media:** OPPO 圆表媒体 Indicator 接入 + Wear OS Ongoing Activity 确认 ([cbb7014](https://github.com/lyizhuo/WearPod/commit/cbb7014cd11a7d7cd7b08d0c19d0e10252d9c44e))
* 添加 CI/CD 工作流和版本管理配置 ([0fec988](https://github.com/lyizhuo/WearPod/commit/0fec98831b54848eec972941646f1688a8d051f0))


### Bug Fixes

* **back-gesture:** OPPO 圆表 windowSwipeToDismiss 兼容 Android 14 ([89f6e4e](https://github.com/lyizhuo/WearPod/commit/89f6e4ec5b82ef1dce63572902b063f17dd4ab0c))
* **ci:** mark gradlew as executable (100644 -&gt; 100755) ([e55388c](https://github.com/lyizhuo/WearPod/commit/e55388cf55ad650114737d7f5d433593c716127d))
* **ci:** 校验步骤兼容新版 build-tools 的 apksigner 输出格式 ([980b86b](https://github.com/lyizhuo/WearPod/commit/980b86bf00b93e8b39d3303d7fb36589840a2fe7))
* kotlin.math 无 toRadians/toDegrees，角度换算改用 java.lang.Math ([4f8cb8c](https://github.com/lyizhuo/WearPod/commit/4f8cb8cd761098b35f55df65a060961cb83b0a8d))
* **lint:** 就地消费 UnstableApi opt-in，修复 UnsafeOptInUsageError ([144e7d0](https://github.com/lyizhuo/WearPod/commit/144e7d04575ed2a837293f49adadca0181b96f15))
* **media:** 按 media3 1.5.0 真实 API 重写 PlaybackController ([f3a1653](https://github.com/lyizhuo/WearPod/commit/f3a1653b083a40311eac9649620fc490ed833190))
* **queue:** 离线队列播完暂停并保留在离线列表，不再跳回在线队列 ([8409e60](https://github.com/lyizhuo/WearPod/commit/8409e60ec8f0f8c8930736a84883bc980bdac35a))
* **release:** 用既有 release key 签名 CI 产物，存量用户可覆盖安装 ([1bfd3dc](https://github.com/lyizhuo/WearPod/commit/1bfd3dc7eea2aa2442d753d0c3e5116558a6f2d6))
* **ui:** 影子 ViewModel 改为 Activity 级收集下发等交互修复 ([9a0f65f](https://github.com/lyizhuo/WearPod/commit/9a0f65fa40daaa7d8cfff62c1adf5f90e4b71e3f))
* 时长严格解析、RSS 时长 Locale.US、Android 13+ 通知权限 ([2d1ec55](https://github.com/lyizhuo/WearPod/commit/2d1ec55b0c9df8f89aacd57a0e59b55898b2a2f6))


### Performance Improvements

* **media:** 位置轮询间隔自适应——播放页可见 500ms，不可见 1s ([9003199](https://github.com/lyizhuo/WearPod/commit/90031994be9385f2c6bfc21bf0d0d95047808961))
