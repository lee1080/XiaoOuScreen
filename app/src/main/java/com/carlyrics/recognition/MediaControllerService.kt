package com.carlyrics.recognition

import android.media.browse.MediaBrowser.MediaItem
import android.os.Bundle
import android.service.media.MediaBrowserService

/**
 * 用于注册为 MediaBrowserService 以获取 MediaSession 监听权限
 */
class MediaControllerService : MediaBrowserService() {
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot("carlyrics_root", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }
}
