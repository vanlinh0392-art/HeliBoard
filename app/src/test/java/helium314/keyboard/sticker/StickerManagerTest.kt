package helium314.keyboard.sticker

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class StickerManagerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var context: App

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sticker_prefs", 0).edit().clear().commit()
    }

    @Test
    fun deletedAssetPackDoesNotReappearAfterReload() {
        val assetPack = StickerPack(
            id = "default_Memeisphong",
            name = "Memeisphong",
            coverUri = "file:///android_asset/stickers/Memeisphong/1.webp",
            stickers = mutableListOf(
                Sticker(
                    id = "default_Memeisphong_1",
                    uri = "file:///android_asset/stickers/Memeisphong/1.webp",
                    mimeType = "image/webp",
                )
            ),
            isAssetPack = true,
        )
        context.getSharedPreferences("sticker_prefs", 0)
            .edit()
            .putString("sticker_packs", json.encodeToString(listOf(assetPack)))
            .commit()

        val manager = StickerManager(context)
        val loadedPack = manager.packs.firstOrNull { it.isAssetPack } ?: assetPack
        val packsField = StickerManager::class.java.getDeclaredField("_packs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val packs = packsField.get(manager) as MutableList<StickerPack>
        if (packs.none { it.id == loadedPack.id }) {
            packs.add(loadedPack)
        }
        assertNotNull(manager.getPack(loadedPack.id))

        manager.deletePack(loadedPack.id)

        val deletedAssetPacks = context.getSharedPreferences("sticker_prefs", 0)
            .getStringSet("deleted_asset_packs", emptySet()).orEmpty()
        assertTrue(loadedPack.id in deletedAssetPacks)
        val reloadedManager = StickerManager(context)
        assertFalse(reloadedManager.packs.any { it.id == loadedPack.id })
    }
}
