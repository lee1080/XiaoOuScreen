/**
 * @name 酷狗音乐
 * @id kugou_official
 * @version 2.0.0
 * @author CarLyrics
 * @description 酷狗音乐双模高精源：优先 MediaSession Hash 直连秒查、高分候选过滤与高清封面，无缝降级多端曲库搜索。
 */

var KUGOU_MOBILE_UA = "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
var KUGOU_PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

/**
 * 1. 媒体 ID 快速直查 (MediaSession 0ms 直连秒配)
 * 酷狗播放时若 MediaSession 上报包含 32 位十六进制 FileHash，直接秒开
 */
function match(title, artist, duration, mediaId) {
    if (!mediaId) return null;
    var str = String(mediaId);
    var m = str.match(/([0-9a-fA-F]{32})/);
    if (m) {
        var hash = m[1].toUpperCase();
        var dur = parseInt(duration || 0);
        var res = getLyrics(hash + "|" + dur + "|" + encodeURIComponent(title || "") + "|" + encodeURIComponent(artist || ""));
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
 * 优先采用移动端正版曲库 (过滤嘈杂翻唱)，降级采用 PC 综合曲库
 */
function search(title, artist, duration) {
    var rawTitle = String(title || "").trim();
    var rawArtist = String(artist || "").trim();
    var keyword = (rawTitle + " " + rawArtist).trim();
    if (!keyword) keyword = rawTitle;
    if (!keyword) return [];

    var results = [];

    // 通道 1: 酷狗移动端综合搜索接口 (高权重正版曲库)
    try {
        var mobileUrl = "http://mobilecdn.kugou.com/api/v3/search/song?keyword=" + encodeURIComponent(keyword) + "&page=1&pagesize=15";
        var mobileResp = http.get(mobileUrl, {
            "User-Agent": KUGOU_MOBILE_UA
        });
        if (mobileResp) {
            var data = JSON.parse(mobileResp);
            var list = (data && data.data && data.data.info) || [];
            for (var i = 0; i < list.length; i++) {
                var item = list[i];
                var sName = item.songname || item.songname_original || "";
                var aName = item.singername || "";
                var dur = parseInt(item.duration || 0);
                if (item.hash) {
                    results.push({
                        id: item.hash + "|" + dur + "|" + encodeURIComponent(sName) + "|" + encodeURIComponent(aName),
                        title: sName,
                        artist: aName,
                        duration: dur
                    });
                }
            }
        }
    } catch (e1) {
        console.error("酷狗通道1移动端搜索异常: " + e1);
    }

    // 通道 2 降级: PC 检索兜底 (收录网络热歌、伴奏最全)
    if (results.length === 0) {
        try {
            var backupUrl = "http://songsearch.kugou.com/song_search_v2?keyword=" + encodeURIComponent(keyword) + "&page=1&pagesize=10&filter=0&bitrate=0&isfreeze=0&feedback=0";
            var backupResp = http.get(backupUrl, {
                "User-Agent": KUGOU_PC_UA
            });
            if (backupResp) {
                var bData = JSON.parse(backupResp);
                var bList = (bData && bData.data && bData.data.lists) || [];
                for (var j = 0; j < bList.length; j++) {
                    var bItem = bList[j];
                    var bsName = bItem.SongName || bItem.AudioName || "";
                    var baName = bItem.SingerName || "";
                    var bDur = parseInt(bItem.Duration || 0);
                    if (bItem.FileHash) {
                        results.push({
                            id: bItem.FileHash + "|" + bDur + "|" + encodeURIComponent(bsName) + "|" + encodeURIComponent(baName),
                            title: bsName,
                            artist: baName,
                            duration: bDur
                        });
                    }
                }
            }
        } catch (e2) {
            console.error("酷狗通道2兜底搜索异常: " + e2);
        }
    }

    return results.slice(0, 10);
}

/**
 * 3. 获取具体歌词与专辑封面
 * 采用高分候选智能甄别与高清封面反查
 */
function getLyrics(id) {
    if (!id) return null;
    var parts = String(id).split("|");
    var hash = parts[0];
    var duration = parts.length > 1 ? parseInt(parts[1]) : 0;
    var title = parts.length > 2 ? decodeURIComponent(parts[2]) : "";
    var artist = parts.length > 3 ? decodeURIComponent(parts[3]) : "";

    var keyword = (title + " " + artist).trim();
    if (!keyword) keyword = title;

    var finalLrc = "";
    var finalCover = null;

    // 通道 1: 歌词候选智能甄选
    try {
        var searchUrl = "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=" + encodeURIComponent(keyword) + "&duration=" + (duration * 1000) + "&hash=" + encodeURIComponent(hash);
        var searchResp = http.get(searchUrl, {
            "User-Agent": KUGOU_MOBILE_UA
        });
        if (searchResp) {
            var searchData = JSON.parse(searchResp);
            var candidates = searchData.candidates || [];
            if (candidates.length > 0) {
                // 优先筛选官方推荐歌词或评分最高的候选
                var cand = candidates[0];
                for (var i = 0; i < candidates.length; i++) {
                    var c = candidates[i];
                    if (c.product_from === "官方推荐歌词" || (c.score && c.score >= 50)) {
                        cand = c;
                        break;
                    }
                }

                if (cand && cand.id && cand.accesskey) {
                    var downUrl = "http://krcs.kugou.com/download?ver=1&client=mobi&id=" + cand.id + "&accesskey=" + cand.accesskey + "&fmt=lrc&charset=utf8";
                    var downResp = http.get(downUrl, {
                        "User-Agent": KUGOU_MOBILE_UA
                    });
                    if (downResp) {
                        var downData = JSON.parse(downResp);
                        var base64Lrc = downData.content || "";
                        if (base64Lrc) {
                            finalLrc = crypto.base64Decode(base64Lrc);
                        }
                    }
                }
            }
        }
    } catch (e1) {
        console.error("酷狗歌词通道1获取异常: " + e1);
    }

    // 提取 400x400 / 500x500 高清专辑封面
    try {
        var infoUrl = "http://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=" + encodeURIComponent(hash);
        var infoResp = http.get(infoUrl, {
            "User-Agent": KUGOU_MOBILE_UA
        });
        if (infoResp) {
            var infoData = JSON.parse(infoResp);
            var rawCover = infoData.album_img || infoData.imgUrl || "";
            if (rawCover) {
                finalCover = rawCover.replace("{size}", "400");
            }
        }
    } catch (_) {}

    if (!finalLrc && !finalCover) return null;

    return {
        lrc: finalLrc,
        cover: finalCover
    };
}
