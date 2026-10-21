package tv.daniil.cast;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import tv.daniil.playback.PlaybackSession;
import tv.daniil.playback.R;
import static org.junit.Assert.*;

/** Inject actual touchscreen and mouse MotionEvents, rather than invoking click listeners. */
@RunWith(AndroidJUnit4.class)
public final class PointerControlsTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private ActivityScenario<PlayerActivity> scenario;
    private PlayerActivity screen;
    private SubtitleFixtureServer fixtures;

    @Before public void open() throws Exception {
        fixtures = new SubtitleFixtureServer();
        scenario = ActivityScenario.launch(PlayerActivity.class);
        scenario.onActivity(activity -> screen = activity);
    }
    @After public void close() throws Exception {
        if (scenario != null) scenario.close();
        if (fixtures != null) fixtures.close();
    }

    @Test public void touchNavigatesSettingsChoicesBackAndClose() throws Exception {
        tap(tv.daniil.cast.R.id.idle_settings, false);
        row("Subtitle style", false);
        row("Size", false);
        row("Large", false);
        waitFor(() -> status().optJSONObject("subtitleStyle").optString("size").equals("large"));
        tap(R.id.menu_back, false);
        waitFor(() -> ((TextView)screen.findViewById(R.id.menu_title)).getText().toString().equals("Settings"));
        tap(R.id.menu_close, false);
        waitFor(() -> screen.findViewById(R.id.menu).getVisibility() == View.GONE);
        // Pointer input must not prevent optional D-pad navigation afterwards.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
        waitFor(() -> screen.findViewById(R.id.menu).getVisibility() == View.VISIBLE);
        SystemClock.sleep(300);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor(() -> ((TextView)screen.findViewById(R.id.menu_title)).getText().toString().equals("Subtitle style"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        tap(R.id.menu_go_back, true);
        waitFor(() -> screen.findViewById(R.id.menu).getVisibility() == View.GONE);
    }

    @Test public void mouseAndTouchControlPlaybackAndDragTimeline() throws Exception {
        load(false);
        // Tap the video to reveal actual on-screen controls; no remote input.
        waitFor(() -> !screen.findViewById(R.id.controls).isShown());
        tap(R.id.player_view, false);
        waitFor(() -> screen.findViewById(R.id.control_play).isShown());
        tap(R.id.center_icon, false);
        waitFor(() -> !status().optBoolean("playWhenReady"));
        instrumentation.runOnMainSync(() -> {
            View play=screen.findViewById(R.id.center_icon);
            assertEquals(play.getPaddingLeft(), play.getPaddingRight());
            assertEquals(play.getPaddingTop(), play.getPaddingBottom());
            View captions = screen.findViewById(R.id.subtitles);
            Rect timeline = new Rect();
            screen.findViewById(R.id.progress).getGlobalVisibleRect(timeline);
            assertTrue("Captions stay above the draggable timeline",
                    captions.getHeight() - captions.getPaddingBottom() < timeline.top);
        });
        long initial=status().optLong("position");
        tap(R.id.control_forward, true);
        waitFor(() -> status().optLong("position") >= initial + 9000);
        tap(R.id.control_rewind, false);
        waitFor(() -> Math.abs(status().optLong("position") - initial) < 2000);
        dragTimeline();
        waitFor(() -> status().optLong("position") > 80000 && status().optLong("position") < 100000);
        tap(R.id.control_options, true);
        row("Speed", true);
        row("1.5×", true);
        waitFor(() -> Math.abs(status().optDouble("speed")-1.5) < 0.001);
        row("Subtitles", false);
        row("French", true);
        waitFor(() -> status().optString("textTrack").equals(status().optJSONArray("textTracks").optJSONObject(1).optString("id")));
        tap(R.id.menu_close, true);
        // Menu close leaves the video surface available to reveal controls.
        tap(R.id.player_view, true);
        tap(R.id.control_play, true);
        waitFor(() -> status().optBoolean("playing"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        waitFor(() -> !status().optBoolean("playing"));
        tap(R.id.control_stop, false);
        waitFor(screen::isFinishing);
    }

    @Test public void pointerHandlesNextEpisodeCancelRetryAndClose() throws Exception {
        load(true);
        showNext();
        tap(R.id.up_next_cancel, true);
        waitFor(() -> screen.findViewById(R.id.up_next).getVisibility()==View.GONE);
        showNext();
        tap(R.id.up_next_play, false);
        waitFor(() -> status().optJSONObject("episode").optString("id").equals("e2") && status().optBoolean("playing"));
        send(new JSONObject().put("action","fail").put("code","network").put("retry",true).put("message","Test error"));
        tap(R.id.error_retry, true);
        waitFor(() -> status().isNull("error") && status().optBoolean("playing"));
        send(new JSONObject().put("action","fail").put("code","network").put("retry",false));
        tap(R.id.error_close, false);
        waitFor(screen::isFinishing);
    }

    @Test public void repeatedRemoteSeeksAddUpToOneSeek() throws Exception {
        load(false);
        AtomicInteger seeks = new AtomicInteger();
        instrumentation.runOnMainSync(() -> player().addListener(new Player.Listener() {
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                    Player.PositionInfo newPosition, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks.incrementAndGet();
            }
        }));
        long initial = status().optLong("position");
        // Three presses in one main-thread pass, so a slow emulator can't space them past the gathering delay.
        instrumentation.runOnMainSync(() -> {
            for (int i = 0; i < 3; i++) {
                screen.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                screen.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
            }
        });
        waitFor(() -> ((TextView) screen.findViewById(R.id.seek_label)).getText().toString().equals("+30 s"));
        waitFor(() -> status().optLong("position") >= initial + 29_000);
        SystemClock.sleep(1000);
        assertEquals("Gathered presses seek once", 1, seeks.get());
    }

    private ExoPlayer player() {
        try {
            Field field = PlayerActivity.class.getDeclaredField("session");
            field.setAccessible(true);
            return ((PlaybackSession) field.get(screen)).player();
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private void showNext() {
        instrumentation.runOnMainSync(() -> {
            try {
                var method=PlayerActivity.class.getDeclaredMethod("offerNextEpisode");
                method.setAccessible(true);method.invoke(screen);
            } catch(Exception error) {throw new AssertionError(error);}
        });
    }
    private void load(boolean episodes) throws Exception {
        JSONArray subs=new JSONArray();
        for(String[] track:new String[][]{{"long.vtt","English"},{"alternate.vtt","French"}})subs.put(new JSONObject().put("url",fixtures.url(track[0])).put("mime","text/vtt").put("label",track[1]));
        JSONObject command=new JSONObject().put("action","load").put("url",fixtures.url("video.mp4")).put("mime","video/mp4").put("title","Pointer controls").put("subtitles",subs).put("subtitle","0");
        if(episodes) {
            JSONArray list=new JSONArray();
            for(int i=1;i<=2;i++)list.put(new JSONObject().put("id","e"+i).put("number",""+i).put("title","Episode "+i).put("versions",new JSONArray().put(new JSONObject().put("url",fixtures.url("video.mp4")).put("kind","mp4").put("mime","video/mp4"))));
            command.put("catalog",new JSONObject().put("title","Test series").put("seasons",new JSONArray().put(new JSONObject().put("id","s1").put("number","1").put("episodes",list)))).put("episode","e1");
        }
        send(command);
        waitFor(() -> status().optBoolean("renderedVideo") && status().optBoolean("playing"));
    }
    private void send(JSONObject command) {
        instrumentation.runOnMainSync(() -> {try {screen.command(command);}catch(Exception error){throw new AssertionError(error);}});
    }
    private void row(String label, boolean mouse) {
        final View[] target={null};
        instrumentation.runOnMainSync(() -> {
            ViewGroup rows=screen.findViewById(R.id.menu_rows);
            for(int i=0;i<rows.getChildCount();i++) {
                View row=rows.getChildAt(i);
                if(((TextView)row.findViewById(R.id.row_label)).getText().toString().equals(label)){target[0]=row;row.requestRectangleOnScreen(new Rect(0,0,row.getWidth(),row.getHeight()),true);break;}
            }
            assertNotNull("Menu row "+label,target[0]);
        });
        tap(target[0],mouse);
    }
    private void tap(int id,boolean mouse){tap(screen.findViewById(id),mouse);}
    private void tap(View view,boolean mouse) {
        SystemClock.sleep(300); // Wait for transitions before determining real screen coordinates.
        int[] point=new int[2];
        instrumentation.runOnMainSync(() -> {assertTrue("Visible target "+(view.getId()==View.NO_ID?view.toString():screen.getResources().getResourceEntryName(view.getId())),view.isShown());view.getLocationOnScreen(point);point[0]+=view.getWidth()/2;point[1]+=view.getHeight()/2;});
        long time=SystemClock.uptimeMillis();
        motion(time,time,MotionEvent.ACTION_DOWN,point[0],point[1],mouse);
        motion(time,time+60,MotionEvent.ACTION_UP,point[0],point[1],mouse);
        instrumentation.waitForIdleSync();
    }
    private void dragTimeline() {
        int[] p=new int[3];
        instrumentation.runOnMainSync(() -> {View v=screen.findViewById(R.id.progress);v.getLocationOnScreen(p);p[1]+=v.getHeight()/2;p[2]=v.getWidth();});
        long time=SystemClock.uptimeMillis();
        motion(time,time,MotionEvent.ACTION_DOWN,p[0]+p[2]*0.1f,p[1],true);
        for(int i=1;i<=5;i++)motion(time,time+i*40,MotionEvent.ACTION_MOVE,p[0]+p[2]*(0.1f+i*0.1f),p[1],true);
        motion(time,time+240,MotionEvent.ACTION_UP,p[0]+p[2]*0.6f,p[1],true);
        instrumentation.waitForIdleSync();
    }
    private void motion(long down,long time,int action,float x,float y,boolean mouse) {
        MotionEvent.PointerProperties props=new MotionEvent.PointerProperties();props.id=0;props.toolType=mouse?MotionEvent.TOOL_TYPE_MOUSE:MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=x;coords.y=y;coords.pressure=action==MotionEvent.ACTION_UP?0:1;coords.size=1;
        MotionEvent event=MotionEvent.obtain(down,time,action,1,new MotionEvent.PointerProperties[]{props},new MotionEvent.PointerCoords[]{coords},0,mouse&&action!=MotionEvent.ACTION_UP?MotionEvent.BUTTON_PRIMARY:0,1,1,0,0,mouse?InputDevice.SOURCE_MOUSE:InputDevice.SOURCE_TOUCHSCREEN,0);
        instrumentation.getUiAutomation().injectInputEvent(event,true);event.recycle();
    }
    private JSONObject status() {
        try {return CastHub.status();}catch(Exception error){throw new AssertionError(error);}
    }
    private void waitFor(BooleanSupplier condition) {
        AtomicBoolean matched=new AtomicBoolean();long until=SystemClock.elapsedRealtime()+12000;
        do {
            instrumentation.runOnMainSync(() -> matched.set(condition.getAsBoolean()));
            if(matched.get())return;SystemClock.sleep(100);
        }while(SystemClock.elapsedRealtime()<until);
        fail("Timed out waiting for pointer action. Menu: "+((TextView)screen.findViewById(R.id.menu_title)).getText()+". Status: "+status());
    }
}
