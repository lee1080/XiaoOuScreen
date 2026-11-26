/**
 * @name Apple Music（暂不可用）
 * @id applemusic_official
 * @version 1.0.2
 * @author lee
 * @description Apple Music AMP 搜索、封面与歌词（仅本源；跨源回退由客户端统一处理）。公开 token 暂无法获取歌词，故标记暂不可用。
 */

var APPLE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";
var APPLE_HOME = "https://music.apple.com";
var APPLE_AMP = "https://amp-api.music.apple.com";
var APPLE_STOREFRONT = "cn";
var __appleToken = "";

function appleArtwork(template, size) {
    if (!template) return null;
    var s = String(size || 600);
    return String(template).replace(/\{w\}/g, s).replace(/\{h\}/g, s);
}

function appleGetToken() {
    if (__appleToken) return __appleToken;

    var homepage = http.get(APPLE_HOME, { "User-Agent": APPLE_UA });
    if (!homepage) return "";

    // 兼容 index~xxx.js / index-legacy~xxx.js
    var match = homepage.match(/\/(assets\/index(?:-legacy)?[~-][^"'\\s]+\.js)/);
    if (!match) return "";

    var script = http.get(APPLE_HOME + "/" + match[1], { "User-Agent": APPLE_UA });
    if (!script) return "";

    var tokenRe = /eyJ[A-Za-z0-9._-]{40,}/g;
    var candidates = [];
    var m;
    while ((m = tokenRe.exec(script)) !== null) {
        if (candidates.indexOf(m[0]) === -1) candidates.push(m[0]);
    }

    for (var i = 0; i < candidates.length; i++) {
        var probe = http.get(
            APPLE_AMP + "/v1/catalog/" + APPLE_STOREFRONT + "/search?term=music&types=songs&limit=1",
            {
                "User-Agent": APPLE_UA,
                "Authorization": "Bearer " + candidates[i],
                "Origin": APPLE_HOME,
                "Accept": "application/json"
            }
        );
        if (probe) {
            __appleToken = candidates[i];
            return __appleToken;
        }
    }
    return "";
}

function appleAmpGet(path, query) {
    var token = appleGetToken();
    if (!token) return null;
    var url = APPLE_AMP + path + (query ? ("?" + query) : "");
    return http.get(url, {
        "User-Agent": APPLE_UA,
        "Authorization": "Bearer " + token,
        "Origin": APPLE_HOME,
        "Accept": "application/json"
    });
}

function appleParseSong(item) {
    if (!item || !item.id || !item.attributes) return null;
    var a = item.attributes;
    var name = a.name || "";
    if (!name) return null;
    return {
        id: String(item.id),
        title: name,
        artist: a.artistName || "",
        duration: Math.round((a.durationInMillis || 0) / 1000)
    };
}

function search(title, artist, duration) {
    var keyword = (title + " " + artist).trim();
    if (!keyword) return [];

    var resp = appleAmpGet(
        "/v1/catalog/" + APPLE_STOREFRONT + "/search",
        "term=" + encodeURIComponent(keyword) + "&types=songs&limit=5"
    );
    if (!resp) return [];

    try {
        var data = JSON.parse(resp);
        var list = (data && data.results && data.results.songs && data.results.songs.data) || [];
        var results = [];
        for (var i = 0; i < list.length; i++) {
            var song = appleParseSong(list[i]);
            if (song) results.push(song);
        }
        return results;
    } catch (e) {
        console.error("Apple Music 搜索解析异常: " + e);
        return [];
    }
}

function getLyrics(id) {
    if (!id) return null;

    var resp = appleAmpGet(
        "/v1/catalog/" + APPLE_STOREFRONT + "/songs/" + encodeURIComponent(id),
        "include=lyrics"
    );
    if (!resp) return null;

    try {
        var data = JSON.parse(resp);
        var item = data && data.data && data.data[0];
        if (!item) return null;

        var cover = null;
        if (item.attributes && item.attributes.artwork) {
            cover = appleArtwork(item.attributes.artwork.url, 600);
        }

        var lyrics = (item.relationships && item.relationships.lyrics && item.relationships.lyrics.data) || [];
        var lrc = "";
        if (lyrics.length && lyrics[0].attributes) {
            lrc = lyrics[0].attributes.text || lyrics[0].attributes.ttml || "";
        }

        if (!lrc) return null;
        return {
            lrc: lrc,
            cover: cover
        };
    } catch (e) {
        console.error("Apple Music 歌词解析异常: " + e);
        return null;
    }
}
