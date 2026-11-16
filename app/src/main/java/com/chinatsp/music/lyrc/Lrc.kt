package com.chinatsp.music.lyrc

import android.os.Parcel
import android.os.Parcelable

/**
 * 欧尚车机自带酷狗音乐 原生歌词数据结构实体
 * 必须与车机 com.chinatsp.music.lyrc.Lrc 完全二进制一致 (Parcelable 字段顺序：time -> text)
 */
class Lrc : Parcelable {
    var time: Long = 0L
    var text: String? = null

    constructor()

    constructor(time: Long, text: String?) {
        this.time = time
        this.text = text
    }

    protected constructor(parcel: Parcel) {
        time = parcel.readLong()
        text = parcel.readString()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(time)
        dest.writeString(text)
    }

    override fun toString(): String = "Lrc(time=$time, text='$text')"

    companion object CREATOR : Parcelable.Creator<Lrc> {
        override fun createFromParcel(parcel: Parcel): Lrc = Lrc(parcel)
        override fun newArray(size: Int): Array<Lrc?> = arrayOfNulls(size)
    }
}
