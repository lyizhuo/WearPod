package site.whitezaak.wearpod.util

object NetworkConfig {
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 8_000
    const val FEED_CONNECT_TIMEOUT_MS = 15_000
    const val FEED_READ_TIMEOUT_MS = 30_000

    // 音频下载走蓝牙共享网络时，建连和短时抖动都比 Wi-Fi 慢；8 秒读超时会频繁
    // 把仍在传输的连接判死并触发续传。下载单独放宽，不影响订阅/Feed 请求。
    const val DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000
    const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
}
