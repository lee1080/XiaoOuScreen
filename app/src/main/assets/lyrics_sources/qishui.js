/**
 * @name 汽水音乐
 * @id qishui_official
 * @version 2.0.0
 * @author CarLyrics
 * @description 专为汽水音乐（Luna Music）与抖音热歌生态打造的专属高精度官方歌词源。支持 MediaSession mediaId 快速直连秒查、官方 Web 分享页 SSR 数据解析、原生 YRC 逐字卡拉OK打轴以及短视频热歌双端高可用容错。
 */

// 短视频热歌与括号副标题标准化清洗函数
function cleanKeyword(text) {
    if (!text) return "";
    return String(text)
        .replace(/（.*?）/g, " ")
        .replace(/\(.*?\)/g, " ")
        .replace(/【.*?】/g, " ")
        .replace(/\[.*?\]/g, " ")
        .replace(/\s*[-_—].*$/g, " ") // 过滤尾部副标题如 - 热播版
        .trim();
}

/**
 * 从文本或 mediaId 中提取纯数字 Track ID (6位及以上)
 * 兼容 "7123456789"、"track_7123456789"、"com.luna.music:7123456789" 等各种宿主注入格式
 */
function extractTrackId(text) {
    if (!text) return "";
    var m = String(text).match(/(?:^|\D)(\d{6,})(?:$|\D)/);
    return m ? m[1] : "";
}

/**
 * 稳健的 JSON 对象括号平衡截取算法 (移植自歌词伴侣 jsonObjectEnd)
 * 完美解决非贪婪正则在面对多行、转义引号和深层嵌套时的截断与崩溃问题
 */
function extractRouterData(html) {
    if (!html) return null;
    var idx = html.indexOf("_ROUTER_DATA");
    if (idx < 0) return null;
    var eqIdx = html.indexOf("=", idx + 12);
    if (eqIdx < 0) return null;
    var start = html.indexOf("{", eqIdx + 1);
    if (start < 0) return null;

    var inStr = false;
    var escape = false;
    var depth = 0;
    for (var i = start; i < html.length; i++) {
        var c = html.charAt(i);
        if (inStr) {
            if (escape) {
                escape = false;
            } else if (c === "\\") {
                escape = true;
            } else if (c === "\"") {
                inStr = false;
            }
        } else {
            if (c === "\"") {
                inStr = true;
            } else if (c === "{") {
                depth++;
            } else if (c === "}") {
                depth--;
                if (depth === 0) {
                    try {
                        return JSON.parse(html.substring(start, i + 1));
                    } catch (e) {
                        console.error("JSON.parse _ROUTER_DATA failed: " + e);
                        return null;
                    }
                }
            }
        }
    }
    return null;
}

/**
 * 格式化时间为标准 LRC 格式 [mm:ss.xxx]
 */
function formatLrcTag(ms) {
    var totalMs = Math.max(0, parseInt(ms || 0));
    var min = Math.floor(totalMs / 60000);
    var sec = Math.floor((totalMs % 60000) / 1000);
    var remMs = totalMs % 1000;
    var minStr = (min < 10 ? "0" : "") + min;
    var secStr = (sec < 10 ? "0" : "") + sec;
    var msStr = remMs < 100 ? (remMs < 10 ? "00" + remMs : "0" + remMs) : String(remMs);
    return "[" + minStr + ":" + secStr + "." + msStr + "]";
}

/**
 * 请求汽水音乐官方分享页并解析出全量歌词与超清封面
 * 采用车机端 + 网页端 双端分享地址互补容错
 */
function fetchSodaLyricsById(trackId) {
    if (!trackId) return null;

    var endpoints = [
        {
            url: "https://music.douyin.com/qishui/share/track?track_id=" + encodeURIComponent(trackId),
            referer: "https://music.douyin.com/"
        },
        {
            url: "https://www.qishui.com/share/track?track_id=" + encodeURIComponent(trackId),
            referer: "https://www.qishui.com/"
        }
    ];

    var routerData = null;
    for (var ep = 0; ep < endpoints.length; ep++) {
        var resp = http.get(endpoints[ep].url, {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer": endpoints[ep].referer,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        });
        if (resp) {
            routerData = extractRouterData(resp);
            if (routerData) break;
        }
    }

    if (!routerData) return null;

    try {
        var loaderData = routerData.loaderData || {};
        var trackPage = loaderData.track_page || {};
        var audioOption = trackPage.audioWithLyricsOption || {};
        var lyricsObj = audioOption.lyrics || {};
        var sentences = lyricsObj.sentences || [];

        if (!sentences || sentences.length === 0) return null;

        var yrcLines = [];
        var translationLines = [];

        for (var i = 0; i < sentences.length; i++) {
            var item = sentences[i];
            if (!item) continue;

            var startMs = parseInt(item.startMs || 0);
            if (startMs < 0) continue;
            var endMs = parseInt(item.endMs || startMs);
            var lineDur = Math.max(0, endMs - startMs);

            var words = item.words || [];
            var text = String(item.text || "").trim();
            if (!text && words.length > 0) {
                var wordsTextArr = [];
                for (var wt = 0; wt < words.length; wt++) {
                    wordsTextArr.push(words[wt].text || "");
                }
                text = wordsTextArr.join("").trim();
            }

            if (!text && words.length === 0) continue;

            // 1. 构建逐字卡拉OK (YRC 格式)
            if (words && words.length > 0) {
                var wordSegments = "";
                for (var w = 0; w < words.length; w++) {
                    var wObj = words[w];
                    var wText = wObj.text || "";
                    if (!wText) continue;
                    var wStart = Math.max(startMs, parseInt(wObj.startMs || startMs));
                    var wEnd = Math.max(wStart, parseInt(wObj.endMs || (wStart + 100)));
                    var wDur = Math.max(0, wEnd - wStart);
                    wordSegments += "(" + wStart + "," + wDur + ",0)" + wText;
                }
                if (wordSegments) {
                    yrcLines.push("[" + startMs + "," + lineDur + "]" + wordSegments);
                } else if (text) {
                    yrcLines.push(formatLrcTag(startMs) + text);
                }
            } else if (text) {
                yrcLines.push(formatLrcTag(startMs) + text);
            }

            // 2. 收集翻译歌词行
            var trans = String(item.translation || item.translatedText || "").trim();
            if (trans) {
                translationLines.push(formatLrcTag(startMs) + trans);
            }
        }

        if (yrcLines.length === 0) return null;

        var finalLrc = yrcLines.join("\n");
        if (translationLines.length > 0) {
            finalLrc += "\n" + translationLines.join("\n");
        }

        // 提取超清专辑封面
        var cover = audioOption.coverURL ||
                    (trackPage.headerInfo && trackPage.headerInfo.cover) ||
                    (audioOption.lyricsOption && audioOption.lyricsOption.albumCover) ||
                    null;

        return {
            lrc: finalLrc,
            cover: cover
        };
    } catch (e) {
        console.error("fetchSodaLyricsById 解析异常: " + e);
        return null;
    }
}

/**
 * 1. 媒体唯一 ID 快速直查 (汽水音乐 0ms 秒配专线)
 * 当在车机或手机上使用汽水音乐播放时，系统 MediaSession 会直接上报含有 track_id 的 mediaId，
 * 此处直接秒查官方分享页，免去模糊搜索与网络往返！
 */
function match(title, artist, duration, mediaId) {
    if (!mediaId) return null;
    var trackId = extractTrackId(mediaId);
    if (!trackId) return null;
    var res = getLyrics(trackId);
    if (res && res.lrc) {
        if (res.lrc.indexOf("暂无歌词") !== -1 || res.lrc.indexOf("纯音乐") !== -1) {
            return null;
        }
    }
    return res;
}

/**
 * 2. 搜索候选歌曲列表
 * 结合汽水 PC Web 搜索与短视频热歌池（酷狗/酷我）双重检索
 */
function search(title, artist, duration) {
    var rawTitle = String(title || "").trim();
    var rawArtist = String(artist || "").trim();
    if (!rawTitle) return [];

    var cleanT = cleanKeyword(rawTitle) || rawTitle;
    var query = (cleanT + " " + rawArtist).trim();

    var results = [];

    // 通道 1: 尝试汽水 PC Web 搜索接口 (官方直出)
    try {
        var pcSearchUrl = "https://api.qishui.com/luna/pc/search/track?q=" + encodeURIComponent(query) + "&cursor=0&search_method=input&aid=386088&device_platform=web&channel=pc_web";
        var resp = http.get(pcSearchUrl, {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer": "https://www.qishui.com/"
        });
        if (resp && resp.trim().length > 0) {
            var sData = JSON.parse(resp);
            var groups = sData.result_groups || [];
            for (var g = 0; g < groups.length; g++) {
                var dList = groups[g].data || [];
                for (var d = 0; d < dList.length; d++) {
                    var track = dList[d].entity && dList[d].entity.track;
                    if (track && track.id) {
                        var artistNames = [];
                        if (track.artists) {
                            for (var a = 0; a < track.artists.length; a++) {
                                if (track.artists[a].name) artistNames.push(track.artists[a].name);
                            }
                        }
                        results.push({
                            id: String(track.id),
                            title: track.name || "",
                            artist: artistNames.join(" / "),
                            duration: Math.round((track.duration || 0) / 1000)
                        });
                    }
                }
            }
        }
    } catch (e1) {
        // 忽略网络或风控异常，继续走通道 2 兜底
    }

    if (results.length > 0) {
        return results.slice(0, 10);
    }

    // 通道 2: 酷狗短视频热歌与伴奏池检索兜底 (收录微醺版、剪辑版、DJ版最全)
    try {
        var kgSearchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=" + encodeURIComponent(query) + "&page=1&pagesize=10";
        var kgResp = http.get(kgSearchUrl, {
            "User-Agent": "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        });
        if (!kgResp && rawArtist) {
            kgSearchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=" + encodeURIComponent(cleanT) + "&page=1&pagesize=10";
            kgResp = http.get(kgSearchUrl, { "User-Agent": "Mozilla/5.0" });
        }
        if (kgResp) {
            var kgData = JSON.parse(kgResp);
            var kgList = (kgData.data && kgData.data.info) || [];
            for (var k = 0; k < kgList.length; k++) {
                var it = kgList[k];
                if (it.hash) {
                    results.push({
                        id: "kg_" + it.hash + "|" + (it.album_id || ""),
                        title: it.songname || it.songname_original || "",
                        artist: it.singername || "",
                        duration: parseInt(it.duration || 0)
                    });
                }
            }
        }
    } catch (e2) {
        console.error("汽水热歌兜底通道检索异常: " + e2);
    }

    return results.slice(0, 10);
}

/**
 * 3. 获取具体歌词文本与高清封面
 */
function getLyrics(id) {
    if (!id) return null;
    var idStr = String(id).trim();

    // 场景 A: 包含纯数字 trackId (汽水音乐官方直出 / match 直连秒查)
    var trackId = extractTrackId(idStr);
    if (trackId && idStr.indexOf("kg_") !== 0 && idStr.indexOf("kw_") !== 0) {
        var sodaResult = fetchSodaLyricsById(trackId);
        if (sodaResult && sodaResult.lrc) {
            return sodaResult;
        }
    }

    // 场景 B: 酷狗短视频伴奏/特调版打轴通道
    if (idStr.indexOf("kg_") === 0) {
        var parts = idStr.substring(3).split("|");
        var hash = parts[0];
        if (hash) {
            try {
                var searchLrcUrl = "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=&hash=" + encodeURIComponent(hash);
                var lrcSearchResp = http.get(searchLrcUrl, { "User-Agent": "KuGouMusic/10.0.0" });
                if (lrcSearchResp) {
                    var lrcJson = JSON.parse(lrcSearchResp);
                    var candidates = lrcJson.candidates || [];
                    if (candidates.length > 0) {
                        var cand = candidates[0];
                        var dlUrl = "http://lyrics.kugou.com/download?ver=1&client=pc&id=" + encodeURIComponent(cand.id) + "&accesskey=" + encodeURIComponent(cand.accesskey) + "&fmt=lrc&charset=utf8";
                        var dlResp = http.get(dlUrl, { "User-Agent": "KuGouMusic/10.0.0" });
                        if (dlResp) {
                            var dlData = JSON.parse(dlResp);
                            var contentB64 = dlData.content || "";
                            if (contentB64) {
                                var decodedLrc = crypto.base64Decode(contentB64);
                                var cover = null;
                                try {
                                    var infoUrl = "http://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=" + hash;
                                    var infoResp = http.get(infoUrl, { "User-Agent": "Mozilla/5.0 (Android; Mobile)" });
                                    if (infoResp) {
                                        var infoData = JSON.parse(infoResp);
                                        var rawCover = infoData.album_img || infoData.imgUrl || "";
                                        if (rawCover) cover = rawCover.replace("{size}", "400");
                                    }
                                } catch (_) {}
                                return {
                                    lrc: decodedLrc,
                                    cover: cover
                                };
                            }
                        }
                    }
                }
            } catch (eKg) {
                console.error("酷狗兜底歌词下载失败: " + eKg);
            }
        }
    }

    return null;
}
