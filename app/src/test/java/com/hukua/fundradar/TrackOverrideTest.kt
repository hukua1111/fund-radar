package com.hukua.fundradar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.TrackOverrideStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 跟踪标的手动指定：写进去、读出来、能改、能恢复自动。
 * 这是「基金名对不上指数简称」时唯一的补救入口，行为必须稳定。
 */
@RunWith(RobolectricTestRunner::class)
class TrackOverrideTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun assignAndReadBack() {
        val store = TrackOverrideStore(context)
        store.clear("900001")
        store.set("900001", IndexEntry("931994", 2, "中证电网设备主题"))

        val entry = store.get("900001")
        assertEquals("2.931994", entry?.secid)
        assertEquals("中证电网设备主题", entry?.name)
        assertEquals("900001", store.all().keys.first())
    }

    @Test
    fun assignOverwritesPreviousChoice() {
        val store = TrackOverrideStore(context)
        store.set("900002", IndexEntry("000300", 1, "沪深300"))
        store.set("900002", IndexEntry("000905", 1, "中证500"))
        assertEquals("1.000905", store.get("900002")?.secid)
    }

    @Test
    fun clearDropsOnlyThatFund() {
        val store = TrackOverrideStore(context)
        store.set("900003", IndexEntry("399998", 0, "中证煤炭"))
        store.set("900004", IndexEntry("399997", 0, "中证白酒"))

        store.clear("900003")
        assertNull(store.get("900003"))
        assertEquals("0.399997", store.get("900004")?.secid)
        store.clear("900004")
    }

    @Test
    fun retainRemovesDeletedFunds() {
        val store = TrackOverrideStore(context)
        store.set("900005", IndexEntry("399998", 0, "中证煤炭"))
        store.set("900006", IndexEntry("399997", 0, "中证白酒"))

        store.retain(listOf("900005"))
        assertTrue(store.all().keys.contains("900005"))
        assertNull(store.get("900006"))
        store.clear("900005")
    }

    @Test
    fun unknownFundReturnsNull() {
        val store = TrackOverrideStore(context)
        store.clear("900008")
        assertNull(store.get("900008"))
    }
}
