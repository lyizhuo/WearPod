# Changelog

## [1.5.0](https://github.com/lyizhuo/WearPod/compare/v1.4.9...v1.5.0) (2026-09-17)


### Features

* **media:** OPPO 圆表媒体 Indicator 接入 + Wear OS Ongoing Activity 确认 ([cbb7014](https://github.com/lyizhuo/WearPod/commit/cbb7014cd11a7d7cd7b08d0c19d0e10252d9c44e))
* 添加 CI/CD 工作流和版本管理配置 ([0fec988](https://github.com/lyizhuo/WearPod/commit/0fec98831b54848eec972941646f1688a8d051f0))


### Bug Fixes

* **back-gesture:** OPPO 圆表 windowSwipeToDismiss 兼容 Android 14 ([89f6e4e](https://github.com/lyizhuo/WearPod/commit/89f6e4ec5b82ef1dce63572902b063f17dd4ab0c))
* **ci:** mark gradlew as executable (100644 -&gt; 100755) ([e55388c](https://github.com/lyizhuo/WearPod/commit/e55388cf55ad650114737d7f5d433593c716127d))
* kotlin.math 无 toRadians/toDegrees，角度换算改用 java.lang.Math ([4f8cb8c](https://github.com/lyizhuo/WearPod/commit/4f8cb8cd761098b35f55df65a060961cb83b0a8d))
* **lint:** 就地消费 UnstableApi opt-in，修复 UnsafeOptInUsageError ([144e7d0](https://github.com/lyizhuo/WearPod/commit/144e7d04575ed2a837293f49adadca0181b96f15))
* **media:** 按 media3 1.5.0 真实 API 重写 PlaybackController ([f3a1653](https://github.com/lyizhuo/WearPod/commit/f3a1653b083a40311eac9649620fc490ed833190))
* **queue:** 离线队列播完暂停并保留在离线列表，不再跳回在线队列 ([8409e60](https://github.com/lyizhuo/WearPod/commit/8409e60ec8f0f8c8930736a84883bc980bdac35a))
* **ui:** 影子 ViewModel 改为 Activity 级收集下发等交互修复 ([9a0f65f](https://github.com/lyizhuo/WearPod/commit/9a0f65fa40daaa7d8cfff62c1adf5f90e4b71e3f))
* 时长严格解析、RSS 时长 Locale.US、Android 13+ 通知权限 ([2d1ec55](https://github.com/lyizhuo/WearPod/commit/2d1ec55b0c9df8f89aacd57a0e59b55898b2a2f6))


### Performance Improvements

* **media:** 位置轮询间隔自适应——播放页可见 500ms，不可见 1s ([9003199](https://github.com/lyizhuo/WearPod/commit/90031994be9385f2c6bfc21bf0d0d95047808961))
