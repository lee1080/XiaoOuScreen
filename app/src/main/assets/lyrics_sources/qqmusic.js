/**
 * @name QQ音乐
 * @id qqmusic_official
 * @version 2.0.0
 * @author CarLyrics
 * @description QQ音乐双模官方源：支持 MediaSession 0ms 直连秒查、主词与中文翻译双轨同步、T002R 500x500 超清封面。
 */

var QQ_MOBILE_UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

/**
 * 1. 媒体 ID 快速直查 (MediaSession 0ms 直连秒配)
 * 适配 QQ音乐手机版与车载版上报的 songmid (如 003aAP420137Xj) 或纯数字 songid
 */
function match(title, artist, duration, mediaId) {
    if (!mediaId) return null;
    var str = String(mediaId);

    var res = null;
    // 匹配 14 位 mid: 如 00123456789abc
    var mMid = str.match(/([0-9a-zA-Z]{14})/);
    if (mMid) {
        res = getLyrics(mMid[1]);
    } else {
        // 匹配 5 位以上纯数字 songid
        var mNum = str.match(/(?:^|\D)(\d{5,})(?:$|\D)/);
        if (mNum) {
            res = getLyrics(mNum[1]);
        }
    }

    if (res && res.lrc) {
        if (res.lrc.indexOf("暂无歌词") !== -1 || res.lrc.indexOf("没有填词的纯音乐") !== -1 || res.lrc.indexOf("纯音乐") !== -1) {
            return null;
        }
    }
    return res;
}

/**
 * 2. 搜索候选歌曲列表
 * 优先采用 H5 极速通道，降级采用 client_search_cp
 */
function search(title, artist, duration) {
    var rawTitle = String(title || "").trim();
    var rawArtist = String(artist || "").trim();
    var keyword = (rawTitle + " " + rawArtist).trim();
    if (!keyword) keyword = rawTitle;
    if (!keyword) return [];

    var results = [];

    // 通道 1: H5 极速端点
    try {
        var url1 = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?g_tk=5381&uin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=h5&needNewCode=1&w="
            + encodeURIComponent(keyword)
            + "&zhidaqu=1&catZhida=1&t=0&flag=1&ie=utf-8&sem=1&aggr=0&perpage=10&n=10&p=1&remoteplace=txt.mqq.all";
        var resp1 = http.get(url1, {
            "User-Agent": QQ_MOBILE_UA,
            "Referer": "https://i.y.qq.com/"
        });
        if (resp1) {
            var data1 = JSON.parse(resp1);
            var list1 = (data1 && data1.data && data1.data.song && data1.data.song.list) || [];
            for (var i = 0; i < list1.length; i++) {
                var it1 = list1[i];
                if (!it1.songmid) continue;
                var singers = [];
                if (it1.singer) {
                    for (var s = 0; s < it1.singer.length; s++) {
                        if (it1.singer[s].name) singers.push(it1.singer[s].name);
                    }
                }
                results.push({
                    id: it1.songmid + "|" + (it1.albummid || "") + "|" + (it1.songid || ""),
                    title: it1.songname || "",
                    artist: singers.join(" / "),
                    duration: parseInt(it1.interval || 0)
                });
            }
        }
    } catch (e1) {
        console.error("QQ音乐通道1搜索异常: " + e1);
    }

    // 通道 2 降级: client_search_cp 备选
    if (results.length === 0) {
        try {
            var url2 = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=10&w=" + encodeURIComponent(keyword) + "&format=json";
            var resp2 = http.get(url2, {
                "User-Agent": QQ_MOBILE_UA,
                "Referer": "https://y.qq.com/"
            });
            if (resp2) {
                var data2 = JSON.parse(resp2);
                var list2 = (data2 && data2.data && data2.data.song && data2.data.song.list) || [];
                for (var j = 0; j < list2.length; j++) {
                    var it2 = list2[j];
                    if (!it2.songmid) continue;
                    results.push({
                        id: it2.songmid + "|" + (it2.albummid || "") + "|" + (it2.songid || ""),
                        title: it2.songname || "",
                        artist: it2.singer && it2.singer[0] ? it2.singer[0].name : "",
                        duration: parseInt(it2.interval || 0)
                    });
                }
            }
        } catch (e2) {
            console.error("QQ音乐通道2兜底搜索异常: " + e2);
        }
    }

    return results.slice(0, 10);
}

/**
 * 3. 获取具体歌词与专辑封面
 * 包含主歌词、中文翻译追加轨、以及 500x500 高清大图封面
 */
function getLyrics(id) {
    if (!id) return null;
    var parts = String(id).split("|");
    var songmid = parts[0];
    var albummid = parts.length > 1 ? parts[1] : "";
    var songid = parts.length > 2 ? parts[2] : "";

    var finalLrc = "";
    var finalCover = null;

    // 通道 1: 标准移动端歌词接口 (支持 nobase64 与翻译同步下发)
    try {
        var lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + encodeURIComponent(songmid) + "&format=json&nobase64=1";
        var resp = http.get(lyricUrl, {
            "User-Agent": QQ_MOBILE_UA,
            "Referer": "https://y.qq.com/"
        });
        if (resp) {
            var data = JSON.parse(resp);
            var rawLyric = data.lyric || "";
            var rawTrans = data.trans || "";

            if (rawLyric) {
                if (rawLyric.indexOf("[") === -1 && /^[A-Za-z0-9+/=\r\n]+$/.test(rawLyric)) {
                    finalLrc = crypto.base64Decode(rawLyric);
                } else {
                    finalLrc = rawLyric;
                }
            }

            // 附带翻译歌词作为第二轨追加
            if (rawTrans) {
                var transText = rawTrans;
                if (rawTrans.indexOf("[") === -1 && /^[A-Za-z0-9+/=\r\n]+$/.test(rawTrans)) {
                    transText = crypto.base64Decode(rawTrans);
                }
                if (transText && transText.indexOf("[") !== -1) {
                    finalLrc = finalLrc + "\n" + transText.trim();
                }
            }
        }
    } catch (e1) {
        console.error("QQ音乐歌词获取异常: " + e1);
    }

    // 通道 2 降级: 若传入的是数字 songid，通过 musicid 备用接口反查
    if (!finalLrc && (/^\d+$/.test(songmid) || songid)) {
        try {
            var numId = /^\d+$/.test(songmid) ? songmid : songid;
            var bkUrl = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg?version=15&miniversion=100&lrctype=4&musicid=" + numId;
            var bkResp = http.get(bkUrl, {
                "User-Agent": QQ_MOBILE_UA,
                "Referer": "https://y.qq.com/"
            });
            if (bkResp) {
                var m = bkResp.match(/<!\[CDATA\[(.*?)\]\]>/s);
                if (m && m[1]) {
                    finalLrc = m[1].trim();
                }
            }
        } catch (e2) {
            console.error("QQ音乐数字ID降级异常: " + e2);
        }
    }

    // 提取 500x500 高清专辑封面 (歌词伴侣同款超清规格)
    if (albummid) {
        finalCover = "https://y.gtimg.cn/music/photo_new/T002R500x500M000" + albummid + ".jpg";
    }

    if (!finalLrc && !finalCover) return null;

    return {
        lrc: finalLrc,
        cover: finalCover
    };
}
