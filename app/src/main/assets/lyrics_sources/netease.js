/**
 * @name 网易云音乐
 * @id netease_official
 * @version 2.0.0
 * @author CarLyrics
 * @description 网易云音乐官方双模源：优先官方移动端 YRC 原生逐字卡拉OK打轴、翻译歌词与 500x500 超清封面，无缝降级 PC Web 接口。
 */

var NETEASE_MOBILE_UA = "NeteaseMusic/9.1.65 (Linux; U; Android 12; zh_CN)";
var NETEASE_WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

/**
 * 1. 媒体 ID 快速直查 (MediaSession 0ms 直连秒配)
 * 当网易云音乐播放时，MediaSession mediaId 通常携带纯数字歌曲 ID
 */
function match(title, artist, duration, mediaId) {
    if (!mediaId) return null;
    var str = String(mediaId);
    var m = str.match(/(?:^|\D)(\d{4,})(?:$|\D)/);
    if (m) {
        var res = getLyrics(m[1]);
        if (res && res.lrc) {
            if (res.lrc.indexOf("暂无歌词") !== -1 || res.lrc.indexOf("纯音乐") !== -1) {
                return null;
            }
        }
        return res;
    }
    return null;
}

/**
 * 2. 搜索候选歌曲列表
 */
function search(title, artist, duration) {
    var rawTitle = String(title || "").trim();
    var rawArtist = String(artist || "").trim();
    var keyword = (rawTitle + " " + rawArtist).trim();
    if (!keyword) keyword = rawTitle;
    if (!keyword) return [];

    var results = [];

    // 通道 1: 官方 Web/移动端综合搜索
    try {
        var url1 = "https://music.163.com/api/search/get/web?s=" + encodeURIComponent(keyword) + "&type=1&offset=0&total=true&limit=10";
        var resp1 = http.get(url1, {
            "User-Agent": NETEASE_WEB_UA,
            "Referer": "https://music.163.com"
        });
        if (resp1) {
            var data1 = JSON.parse(resp1);
            var songs1 = (data1 && data1.result && data1.result.songs) || [];
            for (var i = 0; i < songs1.length; i++) {
                var s = songs1[i];
                var artistNames = [];
                if (s.artists) {
                    for (var j = 0; j < s.artists.length; j++) {
                        if (s.artists[j].name) artistNames.push(s.artists[j].name);
                    }
                }
                results.push({
                    id: String(s.id),
                    title: s.name || "",
                    artist: artistNames.join(" / "),
                    duration: Math.round((s.dt || 0) / 1000)
                });
            }
        }
    } catch (e1) {
        console.error("网易云搜索通道1异常: " + e1);
    }

    return results.slice(0, 10);
}

/**
 * 3. 获取具体歌词与专辑封面
 * 核心升级: 优先提取移动端接口的原生 yrc 逐字卡拉OK打轴 + tlyric 中文翻译，无缝降级为 PC Web 接口
 */
function getLyrics(id) {
    if (!id) return null;
    var songId = String(id).trim();

    var finalLrc = "";
    var finalCover = null;

    // 优先模式: 移动端高级歌词通道 (yrc 逐字卡拉OK + 翻译 + 罗马音)
    try {
        var mobileLyricUrl = "https://interface.music.163.com/api/song/lyric?id=" + songId + "&cp=false&tv=0&lv=0&rv=0&kv=0&yv=0&ytv=0&yrv=0";
        var mobileResp = http.get(mobileLyricUrl, {
            "User-Agent": NETEASE_MOBILE_UA,
            "Referer": "https://music.163.com",
            "Cookie": "os=android; appver=9.1.65"
        });
        if (mobileResp) {
            var mData = JSON.parse(mobileResp);
            var yrcText = (mData.yrc && mData.yrc.lyric) || (mData.klyric && mData.klyric.lyric) || "";
            var lrcText = (mData.lrc && mData.lrc.lyric) || "";
            var transText = (mData.tlyric && mData.tlyric.lyric) || "";

            if (yrcText && yrcText.trim().length > 0) {
                finalLrc = yrcText.trim();
            } else if (lrcText && lrcText.trim().length > 0) {
                finalLrc = lrcText.trim();
            }

            // 附带翻译歌词追加为第二轨
            if (transText && transText.trim().length > 0 && transText.indexOf("[") !== -1) {
                finalLrc = (finalLrc ? finalLrc + "\n" : "") + transText.trim();
            }
        }
    } catch (eMobile) {
        console.error("网易云移动端歌词通道异常: " + eMobile);
    }

    // 降级模式: PC Web 接口兜底
    if (!finalLrc) {
        try {
            var pcLyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=" + songId + "&lv=-1&kv=-1&tv=-1&rv=1&yv=1";
            var pcResp = http.get(pcLyricUrl, {
                "User-Agent": NETEASE_WEB_UA,
                "Referer": "https://music.163.com",
                "Cookie": "os=pc"
            });
            if (pcResp) {
                var pcData = JSON.parse(pcResp);
                var pcYrc = (pcData.yrc && pcData.yrc.lyric) || (pcData.klyric && pcData.klyric.lyric) || "";
                var pcLrc = (pcData.lrc && pcData.lrc.lyric) || "";
                var pcTrans = (pcData.tlyric && pcData.tlyric.lyric) || "";

                if (pcYrc && pcYrc.trim().length > 0) {
                    finalLrc = pcYrc.trim();
                } else if (pcLrc && pcLrc.trim().length > 0) {
                    finalLrc = pcLrc.trim();
                }

                if (pcTrans && pcTrans.trim().length > 0 && pcTrans.indexOf("[") !== -1) {
                    finalLrc = (finalLrc ? finalLrc + "\n" : "") + pcTrans.trim();
                }
            }
        } catch (ePc) {
            console.error("网易云PC降级歌词异常: " + ePc);
        }
    }

    // 提取超清 500x500 封面图
    try {
        var detailUrl = "https://music.163.com/api/song/detail/?id=" + songId + "&ids=[" + songId + "]";
        var detailResp = http.get(detailUrl, {
            "User-Agent": NETEASE_WEB_UA,
            "Referer": "https://music.163.com"
        });
        if (detailResp) {
            var detailData = JSON.parse(detailResp);
            var album = detailData.songs && detailData.songs[0] && detailData.songs[0].album;
            if (album && album.picUrl) {
                var rawPic = album.picUrl;
                if (rawPic.indexOf("?") !== -1) {
                    finalCover = rawPic.split("?")[0] + "?param=500y500";
                } else {
                    finalCover = rawPic + "?param=500y500";
                }
            }
        }
    } catch (_) {}

    if (finalLrc && (finalLrc.indexOf("暂无歌词") !== -1 || finalLrc.indexOf("纯音乐，请欣赏") !== -1)) {
        finalLrc = "";
    }

    if (!finalLrc && !finalCover) return null;

    return {
        lrc: finalLrc,
        cover: finalCover
    };
}
