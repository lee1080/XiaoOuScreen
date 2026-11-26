/**
 * @name 酷我音乐
 * @id kuwo_official
 * @version 2.0.0
 * @author CarLyrics
 * @description 酷我音乐官方极速直连源：支持 MediaSession 0ms 直查、毫秒级高精时间轴、500x500 超清封面与 ~40ms 极低延迟。
 */

var KUWO_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer": "https://www.kuwo.cn/"
};

/**
 * 1. 媒体 ID 快速直查 (MediaSession 0ms 直连秒配)
 * 适配酷我车机版/极简版上报的 MUSIC_{mid} 或纯数字 mid
 */
function match(title, artist, duration, mediaId) {
    if (!mediaId) return null;
    var str = String(mediaId);
    var m = str.match(/(?:MUSIC_)?(\d{5,})/);
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

    var url = "http://www.kuwo.cn/search/searchMusicBykeyWord?vipver=1&client=kt&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&mobi=1&issubtitle=1&pn=0&rn=10&all=" + encodeURIComponent(keyword);
    var resp = http.get(url, KUWO_HEADERS);
    if (!resp) return [];

    try {
        var data = JSON.parse(resp);
        var list = data.abslist || (data.data && data.data.list) || (data.data && data.data.abslist) || [];
        var results = [];
        for (var i = 0; i < list.length; i++) {
            var item = list[i];
            var musicId = String(item.MUSICRID || item.DC_TARGETID || "").replace("MUSIC_", "");
            if (!musicId) continue;
            var picShort = item.web_albumpic_short || item.web_artistpic_short || "";
            results.push({
                id: musicId + (picShort ? "|" + encodeURIComponent(picShort) : ""),
                title: item.NAME || item.SONGNAME || "",
                artist: item.ARTIST || "",
                duration: parseInt(item.DURATION || 0)
            });
        }
        return results.slice(0, 10);
    } catch (e) {
        console.error("酷我搜索解析异常: " + e);
        return [];
    }
}

/**
 * 3. 获取具体歌词与超清封面
 */
function getLyrics(id) {
    if (!id) return null;
    var parts = String(id).split("|");
    var musicId = parts[0];
    var picShort = parts.length > 1 ? decodeURIComponent(parts[1]) : "";

    var url = "http://www.kuwo.cn/openapi/v1/www/lyric/getlyric?musicId=" + encodeURIComponent(musicId);
    var resp = http.get(url, KUWO_HEADERS);
    if (!resp) return null;

    try {
        var data = JSON.parse(resp);
        var lrclist = (data && data.data && data.data.lrclist) || [];
        if (!lrclist.length) return null;

        var lrcLines = [];
        for (var i = 0; i < lrclist.length; i++) {
            var item = lrclist[i];
            var totalSec = parseFloat(item.time || 0);
            var min = Math.floor(totalSec / 60);
            var sec = Math.floor(totalSec % 60);
            var ms = Math.floor((totalSec % 1) * 100);
            var minStr = (min < 10 ? "0" : "") + min;
            var secStr = (sec < 10 ? "0" : "") + sec;
            var msStr = (ms < 10 ? "0" : "") + ms;
            lrcLines.push("[" + minStr + ":" + secStr + "." + msStr + "]" + (item.lineLyric || ""));
        }

        // 提取 500x500 超清封面大图
        var cover = null;
        if (picShort) {
            var cleanPath = picShort.replace(/^[0-9]+\//, "");
            cover = "https://img1.kuwo.cn/star/albumcover/500/" + cleanPath;
        } else {
            try {
                var infoResp = http.get("https://wapi.kuwo.cn/api/www/music/musicInfo?mid=" + musicId + "&httpsStatus=1", KUWO_HEADERS);
                if (infoResp) {
                    var info = JSON.parse(infoResp);
                    cover = (info && info.data && (info.data.pic500 || info.data.pic)) || null;
                }
            } catch (_) {}
        }

        return {
            lrc: lrcLines.join("\n"),
            cover: cover
        };
    } catch (e) {
        console.error("酷我歌词解析异常: " + e);
        return null;
    }
}
