package helium314.keyboard.sticker

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class StickerDownloaderTest {
    @Test
    fun parseTelegramStickerSetNameAcceptsCommonLinks() {
        assertEquals("FunnyPack", parseTelegramStickerSetName("https://t.me/addstickers/FunnyPack"))
        assertEquals("FunnyPack", parseTelegramStickerSetName("http://telegram.me/addstickers/FunnyPack/"))
        assertEquals("FunnyPack", parseTelegramStickerSetName("t.me/addstickers/FunnyPack?start=share"))
        assertEquals("FunnyPack", parseTelegramStickerSetName("https://www.t.me/addstickers/FunnyPack#preview"))
    }

    @Test
    fun parseTelegramStickerSetNameRejectsUnsupportedLinks() {
        assertNull(parseTelegramStickerSetName(""))
        assertNull(parseTelegramStickerSetName("https://t.me/FunnyPack"))
        assertNull(parseTelegramStickerSetName("https://example.com/addstickers/FunnyPack"))
        assertNull(parseTelegramStickerSetName("https://t.me/addstickers/"))
    }
}
