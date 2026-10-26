package tv.daniil.stream.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Decode every packaged cue through Android's actual UI sound path, without opening owner data or playing aloud. */
@RunWith(AndroidJUnit4.class)
public final class SoundAssetsTest {
    @Test public void everyPackagedCueLoadsAndCanStartInSoundPool() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SoundPool pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        Map<Integer, Integer> statuses = new ConcurrentHashMap<>();
        Map<Sounds.Sound, Integer> ids = new java.util.EnumMap<>(Sounds.Sound.class);
        CountDownLatch loaded = new CountDownLatch(Sounds.Sound.values().length);
        try {
            pool.setOnLoadCompleteListener((p, id, status) -> { statuses.put(id, status); loaded.countDown(); });
            for (Sounds.Sound sound : Sounds.Sound.values()) {
                int id = pool.load(context, sound.resource, 1);
                assertTrue("Resource queued: " + sound, id > 0); ids.put(sound, id);
            }
            assertTrue("Every packaged sound decoded", loaded.await(15, TimeUnit.SECONDS));
            for (Sounds.Sound sound : Sounds.Sound.values()) {
                int id = ids.get(sound);
                assertEquals("Decode succeeded: " + sound, Integer.valueOf(0), statuses.get(id));
                int stream = pool.play(id, 0, 0, sound.size.ordinal(), 0, 1);
                assertTrue("Decoded sound can play: " + sound, stream > 0);
                pool.stop(stream);
            }
        } finally { pool.release(); }
    }
}
