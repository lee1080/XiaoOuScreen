/**
 * @name 咪咕音乐
 * @id migu_official
 * @version 2.0.0
 * @author CarLyrics
 * @description 咪咕音乐双模高精源：优先咪咕原生 MRC 逐字卡拉OK动效与超清封面，无缝降级移动端标准 LRC 与翻译歌词。
 */

var MIGU_UA = "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
var MIGU_HEADERS = {
    "User-Agent": MIGU_UA,
    "channel": "0146921",
    "Referer": "https://app.c.nf.migu.cn/"
};

function miguSingers(item) {
    var names = [];
    var list = item.singers || item.singerList || item.artists || [];
    for (var i = 0; i < list.length; i++) {
        var n = list[i] && (list[i].name || list[i].singerName);
        if (n) names.push(n);
    }
    if (names.length) return names.join(" / ");
    return item.singer || item.author || "";
}

function miguPickCover(item) {
    if (!item) return null;
    var direct = item.img3 || item.img2 || item.img1;
    if (direct) return direct;

    var imgs = item.imgItems || item.albumImgs || [];
    if (!imgs || !imgs.length) return null;

    var prefer = ["03", "3", "02", "2", "01", "1"];
    for (var p = 0; p < prefer.length; p++) {
        for (var i = 0; i < imgs.length; i++) {
            var size = String(imgs[i].imgSizeType || "");
            if (size === prefer[p]) {
                return imgs[i].img || imgs[i].webpImg || null;
            }
        }
    }
    return imgs[0].img || imgs[0].webpImg || null;
}

/**
 * 转换咪咕 MRC 逐字歌词为原生 YRC 卡拉OK格式
 * 咪咕 MRC 格式: [lineStart,lineDur](wStart,wDur)字(wStart,wDur)字...
 * 转换目标格式: [lineStart,lineDur](wStart,wDur,0)字(wStart,wDur,0)字...
 */
function convertMrcToYrc(mrcText) {
    if (!mrcText) return "";
    var lines = mrcText.split(/\r?\n/);
    var yrcLines = [];
    var hasWordTiming = false;

    for (var i = 0; i < lines.length; i++) {
        var line = lines[i].trim();
        if (!line) continue;

        // 匹配整行时间标签: ^\[(\d+),(\d+)\](.*)$
        var lineMatch = line.match(/^\[(\d+),(\d+)\](.*)$/);
        if (lineMatch) {
            var lStart = lineMatch[1];
            var lDur = lineMatch[2];
            var content = lineMatch[3] || "";

            // 检查并替换逐字标签 \((\d+),(\d+)\) -> ($1,$2,0)
            if (/\((\d+),(\d+)\)/.test(content)) {
                hasWordTiming = true;
                var transformed = content.replace(/\((\d+),(\d+)\)/g, "($1,$2,0)");
                yrcLines.push("[" + lStart + "," + lDur + "]" + transformed);
            } else {
                yrcLines.push(line);
            }
        } else if (/^\[\d{2}:\d{2}/.test(line)) {
            yrcLines.push(line);
        }
    }

    return hasWordTiming ? yrcLines.join("\n") : mrcText;
}

/**
 * 1. 快速直查 (MediaSession 0ms 直连)
 */
function match(title, artist, duration, mediaId) {
    if (!mediaId) return null;
    var str = String(mediaId);
    var m = str.match(/(?:^|\D)(\d{6,})(?:$|\D)/);
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
 * 优先采用带渠道签名的移动端综合搜索接口，降级采用旧版 REST 接口
 */
function search(title, artist, duration) {
    var rawTitle = String(title || "").trim();
    var rawArtist = String(artist || "").trim();
    var keyword = (rawTitle + " " + rawArtist).trim();
    if (!keyword) keyword = rawTitle;
    if (!keyword) return [];

    var results = [];

    // 通道 1: 移动端专属搜索接口
    try {
        var searchSwitch = encodeURIComponent('{"song":1}');
        var url1 = "https://pd.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do"
            + "?ua=Android_migu&version=5.0.1"
            + "&text=" + encodeURIComponent(keyword)
            + "&pageNo=1&pageSize=10"
            + "&searchSwitch=" + searchSwitch;

        var resp1 = http.get(url1, MIGU_HEADERS);
        if (resp1) {
            var data1 = JSON.parse(resp1);
            var list1 = (data1 && data1.songResultData && data1.songResultData.result) || [];
            for (var i = 0; i < list1.length; i++) {
                var it1 = list1[i];
                var cId = String(it1.contentId || it1.copyrightId || "");
                if (!cId) continue;
                results.push({
                    id: cId,
                    title: it1.name || it1.songName || "",
                    artist: miguSingers(it1),
                    duration: parseInt(it1.duration || it1.length || 0, 10) || 0
                });
            }
        }
    } catch (e1) {
        console.error("咪咕通道1搜索异常: " + e1);
    }

    // 通道 2 降级: 网页搜索兜底
    if (results.length === 0) {
        try {
            var url2 = "https://m.music.migu.cn/migu/remoting/scr_search_tag?rows=10&type=2&keyword=" + encodeURIComponent(keyword);
            var resp2 = http.get(url2, { "User-Agent": MIGU_UA });
            if (resp2) {
                var data2 = JSON.parse(resp2);
                var list2 = (data2 && (data2.musics || data2.list)) || [];
                for (var j = 0; j < list2.length; j++) {
                    var it2 = list2[j];
                    var id2 = String(it2.copyrightId || it2.id || "");
                    if (id2) {
                        results.push({
                            id: id2,
                            title: it2.songName || it2.title || "",
                            artist: it2.singerName || it2.artist || "",
                            duration: 0
                        });
                    }
                }
            }
        } catch (e2) {
            console.error("咪咕通道2兜底搜索异常: " + e2);
        }
    }

    return results.slice(0, 10);
}

/**
 * 3. 获取具体歌词与专辑封面
 * 核心升级: 优先提取官方 resource.mrcUrl 逐字卡拉OK打轴，无缝降级为 resource.lrcUrl 普通主词 + trcUrl 翻译词
 */
function getLyrics(id) {
    if (!id) return null;

    var infoUrl = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do"
        + "?resourceType=2&resourceId=" + encodeURIComponent(id);
    var infoResp = http.get(infoUrl, MIGU_HEADERS);
    if (!infoResp) return null;

    try {
        var info = JSON.parse(infoResp);
        var resource = (info && info.resource && info.resource[0]) || null;
        if (!resource) return null;

        var mrcUrl = resource.mrcUrl || "";
        var lrcUrl = resource.lrcUrl || resource.lyricUrl || "";
        var trcUrl = resource.trcUrl || "";
        var cover = miguPickCover(resource);

        var finalLrc = "";

        // 优先模式: 尝试获取并解析 MRC 逐字卡拉OK动效
        if (mrcUrl) {
            try {
                var mrcRaw = http.get(mrcUrl, MIGU_HEADERS);
                if (mrcRaw && mrcRaw.trim().indexOf("[") !== -1) {
                    var yrcFormatted = convertMrcToYrc(mrcRaw.trim());
                    if (yrcFormatted && yrcFormatted.indexOf("(0,") === -1) {
                        finalLrc = yrcFormatted;
                    }
                }
            } catch (eMrc) {
                console.error("咪咕 MRC 逐字解析异常: " + eMrc);
            }
        }

        // 降级模式: 若 MRC 为空或解析失败，降级回退到标准 LRC
        if (!finalLrc && lrcUrl) {
            try {
                var lrcRaw = http.get(lrcUrl, MIGU_HEADERS);
                if (lrcRaw && lrcRaw.trim().length > 0) {
                    finalLrc = lrcRaw.trim();
                }
            } catch (eLrc) {
                console.error("咪咕降级 LRC 获取异常: " + eLrc);
            }
        }

        if (!finalLrc) {
            return cover ? { lrc: "", cover: cover } : null;
        }

        // 附带翻译歌词追加为第二轨
        if (trcUrl) {
            try {
                var trcRaw = http.get(trcUrl, MIGU_HEADERS);
                if (trcRaw && trcRaw.indexOf("[") !== -1) {
                    finalLrc = finalLrc + "\n" + trcRaw.trim();
                }
            } catch (_) {}
        }

        return {
            lrc: finalLrc,
            cover: cover
        };
    } catch (e) {
        console.error("咪咕歌词全流程解析异常: " + e);
        return null;
    }
}
