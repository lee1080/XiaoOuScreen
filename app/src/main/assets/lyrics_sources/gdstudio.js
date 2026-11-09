/**
 * @name GD Studio (聚合源)
 * @id gdstudio_proxy
 * @version 1.0.5
 * @author Community
 * @description 第三方聚合 API 备用源，覆盖网易聚合检索，优先反代加速，失败自动回落原站。
 */

// 优先走 Cloudflare 反代；失败/超时（http.get 返回空或报错）再回落原站
var GD_ORIGIN = "https://music-api.gdstudio.xyz/api.php";
var GD_PROXY = "https://gd.av8d.eu.org/https://music-api.gdstudio.xyz/api.php";
var GD_UA = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
};

function gdGet(query) {
    var q = String(query || "").replace(/^\?/, "");
    var resp = http.get(GD_PROXY + "?" + q, GD_UA);
    if (resp && resp.indexOf("error code:") < 0 && resp.indexOf("bad_path") < 0) {
        return resp;
    }
    return http.get(GD_ORIGIN + "?" + q, GD_UA);
}

function doSearch(keyword) {
    if (!keyword) return [];
    var encName = encodeURIComponent(keyword);
    // 优先加 source=netease 直指网易镜像，毫秒级快速响应，避免海外全源并发检索超时
    var resp = gdGet("types=search&count=5&source=netease&name=" + encName);
    if (!resp) {
        // 兜底尝试全源聚合
        resp = gdGet("types=search&count=5&name=" + encName);
    }
    if (!resp) return [];

    try {
        var list = JSON.parse(resp);
        if (!Array.isArray(list)) return [];
        var results = [];
        for (var i = 0; i < list.length; i++) {
            var item = list[i];
            var artistStr = Array.isArray(item.artist) ? item.artist.join(" / ") : (item.artist || "");
            var dur = item.duration || item.interval || item.length || 0;
            var id = item.lyric_id || item.id;
            if (!id) continue;
            results.push({
                id: id + "|" + (item.pic_id || ""),
                title: item.name || "",
                artist: artistStr,
                duration: parseInt(dur || 0)
            });
        }
        return results;
    } catch (e) {
        console.error("GD Studio 搜索解析异常: " + e);
        return [];
    }
}

function search(title, artist, duration) {
    var t = String(title || "").trim();
    var a = String(artist || "").trim();

    // 优先搜索 "歌名 歌手"，若无结果则回落单搜 "歌名"
    var keyword = (t + " " + a).trim() || t;
    var results = doSearch(keyword);

    if (results.length === 0 && a && t) {
        results = doSearch(t);
    }
    return results.slice(0, 10);
}

function getLyrics(id) {
    var parts = id.split("|");
    var lyricId = parts[0];
    var picId = parts.length > 1 ? parts[1] : "";

    var resp = gdGet("types=lyric&id=" + encodeURIComponent(lyricId));
    if (!resp) return null;

    try {
        var data = JSON.parse(resp);
        var lrc = data.lyric || "";
        if (!lrc) return null;

        var cover = null;
        if (picId) {
            try {
                var picResp = gdGet("types=pic&id=" + encodeURIComponent(picId) + "&size=500");
                if (picResp) {
                    var picData = JSON.parse(picResp);
                    cover = picData.url || null;
                }
            } catch (_) {}
        }

        return {
            lrc: lrc,
            cover: cover
        };
    } catch (e) {
        console.error("GD Studio 歌词解析异常: " + e);
        return null;
    }
}
