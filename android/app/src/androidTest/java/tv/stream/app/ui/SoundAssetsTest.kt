package tv.stream.app.ui

import org.junit.Assert.*
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith

/** Decode every packaged cue through Android's actual UI sound path, without opening owner data or playing aloud. */
@RunWith(AndroidJUnit4::class)
class SoundAssetsTest {
    @Test @Throws(Exception::class) fun everyPackagedCueLoadsAndCanStartInSoundPool() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        val statuses: MutableMap<Int, Int> = ConcurrentHashMap()
        val ids: MutableMap<Sounds.Sound, Int> = java.util.EnumMap(Sounds.Sound::class.java)
        val loaded = CountDownLatch(Sounds.Sound.values().size)
        try {
            pool.setOnLoadCompleteListener { p, id, status -> statuses[id] = status; loaded.countDown() }
            for (sound in Sounds.Sound.values()) {
                val id = pool.load(context, sound.resource, 1)
                assertTrue("Resource queued: $sound", id > 0); ids[sound] = id
            }
            assertTrue("Every packaged sound decoded", loaded.await(15, TimeUnit.SECONDS))
            for (sound in Sounds.Sound.values()) {
                val id: Int = ids[sound]!!
                assertEquals("Decode succeeded: $sound", Integer.valueOf(0), statuses[id])
                val stream = pool.play(id, 0f, 0f, sound.size.ordinal, 0, 1f)
                assertTrue("Decoded sound can play: $sound", stream > 0)
                pool.stop(stream)
            }
        } finally { pool.release() }
    }
}
