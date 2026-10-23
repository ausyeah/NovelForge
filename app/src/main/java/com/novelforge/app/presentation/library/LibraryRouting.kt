package com.novelforge.app.presentation.library

import com.novelforge.app.data.local.WrittenCountRow

/**
 * 点封面的分流规则，和「每本书写了几章」的聚合。
 *
 * 放在 main 而不是 test：`RoomProjectRepository`（data 层）也要用聚合规则，
 * 而 data 层不能依赖 test 源集。两处各 inline 一遍 `associate` 的话，
 * 聚合规则迟早只改一边。
 */
object LibraryRouting {

    /**
     * 点封面去读还是去写。
     *
     * 写过了去读（落在上次读到的那一章），一次没写过的送去写作。
     *
     * 空书不能也去读：那会进目录页，而那里每张卡都是「未生成正文」，点下去
     * 什么都不发生 —— 一片死胡同，用户得自己退出来去找写作。而开始写是所有
     * 书的必经一步。
     *
     * 判据是「有没有写过」而不是「是不是 0 章」：空正文的修订行也占一行，
     * 真出现负数或异常值时这个判断依然成立。
     */
    fun tapGoesToReading(writtenChapters: Int): Boolean = writtenChapters > 0

    /**
     * 把 DAO 的一行行结果合成 projectId -> 章数。
     *
     * 同一本书出现多行时**取最大值**，不是互相覆盖。
     *
     * `associate` 在这里看着是对的，实际会出事：两个 Flow 各自发出局部快照时
     * （比如别处也在写这张表），后到的那个局部值会把先到的整本书计数抹掉 ——
     * 一本写了 545 章的书瞬间显示成 1 章，点封面就分流到写作去了。
     */
    fun toCountMap(rows: List<WrittenCountRow>): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for (row in rows) {
            out[row.projectId] = maxOf(out[row.projectId] ?: 0, row.writtenCount)
        }
        return out
    }
}
