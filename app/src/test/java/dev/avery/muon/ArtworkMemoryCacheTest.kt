package dev.avery.muon

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ArtworkMemoryCacheTest {
    @Test fun tinySharedImagesAcrossLibraryGenerationsCannotKeepEveryOldKey() {
        val cache = artworkMemoryCache()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        fun key(i: Int) = ArtworkMemoryKey("http://example.test/art/${i % 400}", "generation${i / 400}", 128)
        try {
            repeat(1_200) { cache.put(key(it), bitmap) }
            assertEquals(384, cache.snapshot().size)
            assertEquals(ARTWORK_MEMORY_BYTES, cache.size())
            assertNull(cache.get(key(0))); assertNull(cache.get(key(815)))
            assertSame(bitmap, cache.get(key(816))); assertSame(bitmap, cache.get(key(1_199)))
            // Eviction/clear must not recycle a value still shown elsewhere or shared by keys.
            cache.evictAll(); assertEquals(0, cache.size()); assertFalse(bitmap.isRecycled)
            assertEquals(1, bitmap.width)
        } finally { cache.evictAll(); bitmap.recycle() }
    }

    @Test fun longUrlAndIdentityPayloadsAreChargedAboveTheEntryFloor() {
        val cache = artworkMemoryCache(128 * 1024)
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val url = "http://example.test/" + "u".repeat(8_500)
        val identity = "界".repeat(8_500)
        val keys = (1..4).map { ArtworkMemoryKey(url + it, identity, 64) }
        try {
            keys.forEach { cache.put(it, bitmap) }
            // Four minimum-charge entries would fit; these larger metadata entries must not.
            assertEquals(3, cache.snapshot().size); assertNull(cache.get(keys[0]))
            keys.drop(1).forEach { assertSame(bitmap, cache.get(it)) }
            assertTrue(cache.size() > 3 * 32 * 1024); assertTrue(cache.size() <= 128 * 1024)
            assertFalse(bitmap.isRecycled)
            // Oversized metadata is never retained; bounded weighting must remain usable afterward.
            val oversized = ArtworkMemoryKey("http://example.test/large", "x".repeat(100_000), 64)
            cache.put(oversized, bitmap); assertNull(cache.get(oversized)); assertEquals(0, cache.size())
            cache.put(keys[3], bitmap); assertSame(bitmap, cache.get(keys[3]))
        } finally { cache.evictAll(); bitmap.recycle() }
    }
}
