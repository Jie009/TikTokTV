package mulin.tvdy.player;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.airbnb.lottie.LottieAnimationView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ExoPlayer.PreloadConfiguration;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mulin.tvdy.DeviceUtils;
import mulin.tvdy.DouyinConstants;
import mulin.tvdy.R;
import mulin.tvdy.auth.CookieHandoffServer;
import mulin.tvdy.auth.CookieImportHelper;
import mulin.tvdy.auth.LoginStatusChecker;
import mulin.tvdy.auth.QrCodeGenerator;
import mulin.tvdy.data.FeedRepository;
import mulin.tvdy.data.FeedVideo;
import mulin.tvdy.data.WatchedAwemeStore;
import mulin.tvdy.pump.FeedPumpController;

/**
 * Sole screen of the app: plays videos supplied by {@link FeedRepository}
 * with native ExoPlayer, and owns the hidden {@link FeedPumpController} that
 * keeps that repository fed for the entire lifetime of this Activity (i.e.
 * the entire time the app is in the foreground).
 * <p>
 * Navigation: DPAD up/down moves between videos (with a TikTok-style slide
 * transition); left/right seek {@value #SEEK_STEP_MS}ms back/forward; OK/
 * center is an overloaded "start" button - see {@link #onStartPressed} for
 * its show-overlay/play/pause semantics - and a long-press on it opens a
 * (currently placeholder) feature menu, closed with back. All chrome (title/
 * author up top, time/progress/stats at the bottom) auto-hides after
 * {@value #CONTROLS_AUTO_HIDE_MS}ms - see {@link #showControls}. The like/
 * collect counts are read-only display of douyin's real numbers - there's no
 * remote-control binding to toggle them and no signed write-endpoint
 * integration with the real douyin account, only the read-side data pump
 * described in {@link FeedPumpController}.
 * <p>
 * Playback uses a small ExoPlayer playlist window (see {@link #playlist})
 * rather than swapping a single {@link MediaItem} in place: the next
 * {@value #PRELOAD_AHEAD} video(s) are added to the player ahead of time so
 * ExoPlayer starts buffering them in the background while the current one is
 * still playing, and "next"/"previous" just seek across that already-loaded
 * window instead of cold-starting a new fetch.
 */
public class PlayerActivity extends Activity implements FeedRepository.Listener {

    private static final String TAG = "PlayerActivity";
    private static final int PRELOAD_AHEAD = 3;
    /** Preload budget while the user is actively watching. */
    private static final long PRELOAD_PLAYING_US = 10_000_000L;
    /** Smaller head-start for the next item while paused (then preload idles). */
    private static final long PRELOAD_PAUSED_US = 5_000_000L;
    /** After pausing, let the paused preload budget fill then stop fetching ahead. */
    private static final long PRELOAD_PAUSE_SETTLE_MS = 3_000;
    private static final int HISTORY_KEEP = 3;
    private static final long SEEK_STEP_MS = 5_000;
    /** Delay before repeated seeks begin while a direction key is held. */
    private static final long SEEK_REPEAT_INITIAL_MS = 400;
    /** Interval between repeated seeks while the key stays down. */
    private static final long SEEK_REPEAT_INTERVAL_MS = 300;
    private static final int SLIDE_DURATION_MS = 220;
    private static final long CONTROLS_AUTO_HIDE_MS = 3_000;
    private static final long CENTER_LONG_PRESS_MS = 500;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 500;
    /** If no video after this long, auto-retry (TV needs much longer than emulator). */
    private static final long STARTUP_TIMEOUT_PHONE_MS = 45_000;
    private static final long STARTUP_TIMEOUT_TV_MS = 120_000;
    private static final int MAX_STARTUP_RETRIES = 3;
    /** Minimum play time before a skipped video counts as "watched" for filtering. */
    private static final long WATCHED_THRESHOLD_MS = 3_000;
    private static final int HASHTAG_COLOR = Color.parseColor("#7CB2FF");
    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#[^\\s#]+");

    private final FeedRepository repository = FeedRepository.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Mirrors the ExoPlayer timeline 1:1 - index {@code i} here is
     * {@code MediaItem} index {@code i} in the player. Kept separately
     * because we need the full {@link FeedVideo} (desc/author/counts/etc.),
     * not just the playable uri, for whichever item the player lands on.
     */
    private final List<FeedVideo> playlist = new ArrayList<>();
    private int playlistIndex = -1;

    /**
     * Remembers, per {@code awemeId}, the last playback position a video was
     * left at when the user navigated away from it mid-playback - so scrolling
     * back to a previously-watched video in the same session resumes where it
     * left off instead of restarting at 0 (ExoPlayer's playlist navigation
     * has no such memory on its own: {@code seekToNextMediaItem}/
     * {@code seekToPreviousMediaItem} always land on each window's default
     * position, i.e. 0). Videos that finish naturally (see
     * {@code STATE_ENDED} handling in {@link #advance}) are deliberately not
     * remembered here, so a completed video replays from the start next time.
     * In-memory only - not persisted across app restarts, and entries are
     * dropped once their video ages out of {@link #playlist} (see
     * {@link #refillPlaylist}) since they can no longer be navigated back to.
     */
    private final Map<String, Long> resumePositions = new HashMap<>();
    /** Next index into {@link FeedVideo#playUrlCandidates} to try after CDN 403. */
    private final Map<String, Integer> playUrlCandidateIndex = new HashMap<>();

    private FeedPumpController pump;
    private ExoPlayer player;
    private View splashOverlay;
    private LottieAnimationView splashAnimation;
    private TextView splashStatusText;
    /** See {@link #hideSplashIfNeeded} - guards against re-running the fade-out on later videos' first frames. */
    private boolean splashVisible = true;
    private boolean startupTimedOut = false;
    private int startupRetryCount = 0;
    private long startupTimeoutMs;
    private boolean pendingStartupLoginCheck = false;
    private FrameLayout contentContainer;
    private PlayerView playerView;
    private ImageView backdropImage;
    private ImageView coverImage;
    private LottieAnimationView loadingSpinner;
    private View pauseIcon;
    private View topPanel;
    private View bottomPanel;
    private TextView titleText;
    private TextView authorText;
    private ImageView avatarImage;
    private TextView positionText;
    private TextView durationText;
    private ProgressBar seekProgress;
    private TextView likeCountText;
    private TextView commentCountText;
    private TextView collectCountText;
    private View featureMenu;
    private final List<View> menuItems = new ArrayList<>();
    private int menuSelectedIndex = 0;
    private ImageView featureMenuLoginAvatar;
    private TextView featureMenuLoginText;
    private View cookieLoginOverlay;
    private View handoffRow;
    private ImageView handoffQrImage;
    private TextView handoffUrlText;
    private TextView handoffStatusText;
    private boolean cookieLoginVisible = false;
    /** See {@link #applyCookieAndFinish} - blocks re-entrant submissions while one is mid-verification. */
    private boolean verifyingLogin = false;
    private CookieHandoffServer cookieHandoffServer;
    /**
     * The account confirmed by the most recent successful
     * {@link LoginStatusChecker} run (initial login, a manual "检测登录状态",
     * or the silent startup check) - see {@link #setLoginState}. {@code
     * null} fields whenever {@link #loggedIn} is {@code false}.
     */
    private boolean loggedIn = false;
    private String loggedInNickname;
    private String loggedInAvatarUrl;
    private FeedVideo current;
    private boolean waitingForBuffer = false;
    /** When false, newly-arrived feed data is queued/preloaded but not auto-played. */
    private boolean autoStartWhenReady = true;
    /** Set when the user pressed next/previous but the feed buffer was empty. */
    private int pendingAdvance = 0;
    private boolean controlsVisible = false;
    private boolean featureMenuVisible = false;
    private boolean centerLongPressFired = false;
    /** See {@link #dispatchBackKey} for why this needs to survive across a single press's DOWN and UP. */
    private boolean backConsumedSpecially = false;

    private final Runnable hideControlsRunnable = this::hideControls;
    private final Runnable startupTimeoutRunnable = this::onStartupTimeout;
    private final Runnable longPressRunnable = this::openFeatureMenu;
    private final Runnable capPreloadWhilePausedRunnable = this::capPreloadWhilePaused;
    private long seekRepeatDeltaMs = 0;
    private final Runnable seekRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (seekRepeatDeltaMs == 0) return;
            seekBy(seekRepeatDeltaMs);
            showControls();
            handler.postDelayed(this, SEEK_REPEAT_INTERVAL_MS);
        }
    };
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgressUi();
            handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS);
        }
    };

    @Override
    @OptIn(markerClass = UnstableApi.class)
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enableImmersiveMode();
        setContentView(R.layout.activity_player);

        splashOverlay = findViewById(R.id.splashOverlay);
        splashAnimation = findViewById(R.id.splashAnimation);
        splashStatusText = findViewById(R.id.splashStatusText);
        contentContainer = findViewById(R.id.contentContainer);
        backdropImage = findViewById(R.id.backdropImage);
        coverImage = findViewById(R.id.coverImage);
        playerView = findViewById(R.id.playerView);
        loadingSpinner = findViewById(R.id.loadingSpinner);
        pauseIcon = findViewById(R.id.pauseIcon);
        topPanel = findViewById(R.id.topPanel);
        bottomPanel = findViewById(R.id.bottomPanel);
        titleText = findViewById(R.id.titleText);
        authorText = findViewById(R.id.authorText);
        avatarImage = findViewById(R.id.avatarImage);
        positionText = findViewById(R.id.positionText);
        durationText = findViewById(R.id.durationText);
        seekProgress = findViewById(R.id.seekProgress);
        likeCountText = findViewById(R.id.likeCount);
        commentCountText = findViewById(R.id.commentCount);
        collectCountText = findViewById(R.id.collectCount);
        featureMenu = findViewById(R.id.featureMenu);
        cookieLoginOverlay = findViewById(R.id.cookieLoginOverlay);
        handoffRow = findViewById(R.id.handoffRow);
        handoffQrImage = findViewById(R.id.handoffQrImage);
        handoffUrlText = findViewById(R.id.handoffUrlText);
        handoffStatusText = findViewById(R.id.handoffStatusText);

        featureMenuLoginAvatar = findViewById(R.id.featureMenuLoginAvatar);
        featureMenuLoginText = findViewById(R.id.featureMenuLoginText);
        menuItems.add(findViewById(R.id.featureMenuLogin));
        menuItems.add(findViewById(R.id.featureMenuCheckLogin));
        menuItems.add(findViewById(R.id.featureMenuLike));
        menuItems.add(findViewById(R.id.featureMenuFollow));
        menuItems.add(findViewById(R.id.featureMenuComments));

        makeCircular(avatarImage);
        makeCircular(featureMenuLoginAvatar);

        WatchedAwemeStore.getInstance().init(this);

        // Only re-verify a saved session on startup; anonymous use is the
        // default and never prompts the user to log in.
        if (hasSavedSession()) {
            pendingStartupLoginCheck = true;
        }

        startupTimeoutMs = DeviceUtils.isTelevision(this)
                ? STARTUP_TIMEOUT_TV_MS
                : STARTUP_TIMEOUT_PHONE_MS;
        scheduleStartupTimeout();

        // Start the feed pump before ExoPlayer setup so douyin.com begins
        // loading while the player stack is still being constructed (matters
        // on CPU-starved TV boxes where both compete for the same cores).
        pump = new FeedPumpController(this);
        pump.setListener(new FeedPumpController.Listener() {
            @Override
            public void onPumpStatus(String message) {
                updateSplashStatus(message);
            }

            @Override
            public void onPumpError(String message) {
                updateSplashStatus(message);
                Log.w(TAG, "pump error: " + message);
            }
        });
        if (hasSavedSession()) {
            pump.setLoggedIn(true);
        }
        pump.start((ViewGroup) findViewById(R.id.playerRoot));

        // Session cookies are injected per-request in DouyinHttpDataSource.
        DouyinHttpDataSource.Factory httpDataSourceFactory = new DouyinHttpDataSource.Factory();

        player = new ExoPlayer.Builder(this, buildRenderersFactory())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpDataSourceFactory))
                .setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                                .build(),
                        // false: don't let ExoPlayer auto-duck/pause on
                        // transient system audio-focus events. Turning this
                        // on briefly to fight WebView audio bleed instead
                        // caused sound to silently drop out mid-playback
                        // (a focus-loss "duck" that never got reversed) -
                        // net worse than the problem it was meant to fix.
                        // The WebView-side mitigations in FeedHookScripts
                        // (muting <video>/<audio>, suspending AudioContext)
                        // are the real fix for that; this doesn't need to
                        // also fight over system audio focus.
                        /* handleAudioFocus= */ false)
                .build();
        player.setPreloadConfiguration(new PreloadConfiguration(PRELOAD_PLAYING_US));
        player.setRepeatMode(Player.REPEAT_MODE_OFF);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && splashVisible && !playlist.isEmpty()) {
                    hideSplashIfNeeded();
                }
                if (playbackState == Player.STATE_ENDED) {
                    playNext();
                } else {
                    updatePlaybackChrome();
                    updateProgressUi();
                }
            }

            @Override
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                // Fires the instant one item finishes and ExoPlayer auto-
                // continues to the next already-queued item on its own -
                // see handleAutoAdvance() for why this (not STATE_ENDED) is
                // the real "a video just finished naturally" signal.
                // MEDIA_ITEM_TRANSITION_REASON_SEEK is deliberately ignored
                // here: that's *our own* seekToNextMediaItem()/
                // seekToPreviousMediaItem() calls in advance(), which
                // already do their own bookkeeping.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    handleAutoAdvance();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlaybackChrome();
                if (isPlaying) {
                    adjustPreloadForPlay();
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                coverImage.animate().alpha(0f).setDuration(180).start();
                hideSplashIfNeeded();
                if (pump != null) pump.notifyPlaybackStarted();
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.w(TAG, "playback error for " + (current != null ? current.awemeId : "?")
                        + " url=" + playbackUrlFor(current)
                        + " errorCode=" + error.errorCode, error);
                if (retryCurrentWithNextCandidate()) return;
                playNext();
            }
        });
        playerView.setPlayer(player);

        repository.addListener(this);
        startInitialPlayback();
    }

    /**
     * Builds a {@link RenderersFactory} that requests a larger-than-default
     * {@code AudioTrack} buffer for PCM audio. Logcat captured during a
     * reported "crackle then silence" episode showed repeated {@code
     * AudioTrack} {@code "device stall time corrected"} warnings - the audio
     * HAL occasionally reporting an unreliable playback timestamp, which
     * points at the audio driver rather than app/decoding logic. A bigger
     * buffer can't fix an unstable HAL outright, but it gives playback more
     * slack to absorb those brief stalls before they turn into an audible
     * crackle/dropout.
     */
    @OptIn(markerClass = UnstableApi.class)
    private RenderersFactory buildRenderersFactory() {
        return new DefaultRenderersFactory(this) {
            @OptIn(markerClass = UnstableApi.class)
            @Override
            protected AudioSink buildAudioSink(
                    Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                DefaultAudioTrackBufferSizeProvider bufferSizeProvider =
                        new DefaultAudioTrackBufferSizeProvider.Builder()
                                .setPcmBufferMultiplicationFactor(8) // default is 4
                                .setMinPcmBufferDurationUs(500_000) // default is 250_000us (250ms)
                                .setMaxPcmBufferDurationUs(1_500_000) // default is 750_000us (750ms)
                                .build();
                return new DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioTrackBufferSizeProvider(bufferSizeProvider)
                        .build();
            }
        };
    }

    /**
     * Fades out and tears down the startup splash the first time a video
     * actually has a frame on screen - see {@link #splashOverlay}'s XML
     * comment for why that (not e.g. a fixed delay) is the trigger. A no-op
     * on every call after the first, since {@code onRenderedFirstFrame}
     * fires again for every later video, not just the initial one.
     */
    private void hideSplashIfNeeded() {
        if (!splashVisible) return;
        splashVisible = false;
        cancelStartupTimeout();
        maybeRunDeferredLoginCheck();
        splashOverlay.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction(() -> {
                    splashOverlay.setVisibility(View.GONE);
                    splashAnimation.cancelAnimation();
                })
                .start();
    }

    private void startInitialPlayback() {
        refillPlaylist();
        if (playlist.isEmpty()) {
            waitingForBuffer = true;
            autoStartWhenReady = true;
            updatePlaybackChrome();
            return;
        }
        playlistIndex = 0;
        refillPlaylist();
        applyCurrentUi();
        Log.d(TAG, "startInitialPlayback awemeId=" + current.awemeId
                + " bufferRemaining=" + repository.bufferSize()
                + " cookieLen=" + DouyinConstants.playbackCookieLength()
                + " candidates=" + current.playUrlCandidates.size()
                + " url=" + playbackUrlFor(current));
        player.prepare();
        player.setPlayWhenReady(true);
        player.play();
        adjustPreloadForPlay();
        updatePlaybackChrome();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enableImmersiveMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pump.onResume();
        if (player != null && current != null && player.getPlayWhenReady()) {
            player.play();
        }
        handler.post(progressTick);
    }

    @Override
    protected void onPause() {
        pump.onPause();
        if (player != null) player.pause();
        handler.removeCallbacks(progressTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        repository.removeListener(this);
        cancelStartupTimeout();
        pump.onDestroy();
        stopCookieHandoffServer();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private static void makeCircular(ImageView view) {
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
    }

    /** Whether the process-wide CookieManager already has a saved session worth silently re-verifying. */
    private boolean hasSavedSession() {
        String cookie = CookieManager.getInstance().getCookie(DouyinConstants.FEED_URL);
        return cookie != null && cookie.contains("sessionid=");
    }

    /** Silently restores menu state from a cookie saved in a prior session. */
    private void runStartupLoginCheck() {
        new LoginStatusChecker().check(this, (ViewGroup) findViewById(R.id.playerRoot), new LoginStatusChecker.Callback() {
            @Override
            public void onResult(boolean loggedIn, String nickname, String avatarUrl) {
                setLoginState(loggedIn, nickname, avatarUrl);
                if (loggedIn && pump != null) pump.scheduleWatchHistorySync();
            }

            @Override
            public void onCheckFailed() {
                // Timed out - leave menu at default; user can re-check manually.
            }
        });
    }

    private void maybeRunDeferredLoginCheck() {
        if (!pendingStartupLoginCheck) return;
        pendingStartupLoginCheck = false;
        runStartupLoginCheck();
    }

    private void scheduleStartupTimeout() {
        handler.removeCallbacks(startupTimeoutRunnable);
        handler.postDelayed(startupTimeoutRunnable, startupTimeoutMs);
    }

    private void cancelStartupTimeout() {
        handler.removeCallbacks(startupTimeoutRunnable);
        startupTimedOut = false;
        startupRetryCount = 0;
    }

    private void onStartupTimeout() {
        if (!splashVisible || repository.bufferSize() > 0) return;
        if (startupRetryCount < MAX_STARTUP_RETRIES) {
            startupRetryCount++;
            updateSplashStatus("加载较慢，自动重试 (" + startupRetryCount + "/" + MAX_STARTUP_RETRIES + ")…");
            retryStartup();
            return;
        }
        startupTimedOut = true;
        updateSplashStatus("加载超时：请更新系统 WebView 或检查网络，按 OK 重试");
        Toast.makeText(this, "加载超时，按 OK 重试", Toast.LENGTH_LONG).show();
    }

    private void retryStartup() {
        startupTimedOut = false;
        updateSplashStatus("正在重新加载…");
        resetPlaybackAndFeed();
    }

    /**
     * Clears all locally buffered feed items and playback state, then
     * rebuilds the hidden pump WebView so the next fetch uses the current
     * CookieManager session (anonymous vs logged-in).
     */
    private void resetPlaybackAndFeed() {
        repository.reset();
        playlist.clear();
        playlistIndex = -1;
        current = null;
        resumePositions.clear();
        pendingAdvance = 0;
        waitingForBuffer = true;
        autoStartWhenReady = true;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        coverImage.setAlpha(1f);
        scheduleStartupTimeout();
        pump.setLoggedIn(loggedIn);
        if (loggedIn) {
            pump.softReloadAfterLogin();
            pump.scheduleWatchHistorySync();
        } else {
            pump.hardRestart();
        }
        updatePlaybackChrome();
    }

    private void updateSplashStatus(String message) {
        if (splashStatusText != null && message != null && !message.isEmpty()) {
            splashStatusText.setText(message);
        }
    }

    @SuppressWarnings("deprecation")
    private void enableImmersiveMode() {
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        getWindow().getDecorView().setSystemUiVisibility(uiOptions);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return dispatchBackKey(event);
        }

        // While splash is up: long-press OK opens the menu; short OK retries
        // after a startup timeout.
        if (splashVisible) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        centerLongPressFired = false;
                        handler.postDelayed(longPressRunnable, CENTER_LONG_PRESS_MS);
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    handler.removeCallbacks(longPressRunnable);
                    if (!centerLongPressFired && startupTimedOut) {
                        retryStartup();
                    }
                }
                return true;
            }
            return true;
        }

        // Cookie login overlay has no focusable input of its own (see
        // startCookieLogin) - just swallow everything else so it doesn't
        // fall through to this method's video-navigation shortcuts below.
        if (cookieLoginVisible) {
            return true;
        }

        // While the feature menu is up, D-pad up/down move the highlighted
        // item and OK/center activates it; everything else is swallowed so
        // it doesn't also drive video navigation/seeking underneath it.
        if (featureMenuVisible) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        moveMenuSelection(-1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        moveMenuSelection(1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        if (event.getRepeatCount() == 0) {
                            activateSelectedMenuItem();
                        }
                        return true;
                    default:
                        break;
                }
            }
            return true;
        }

        // OK/center is overloaded (short press = start/pause, long press =
        // feature menu), so it needs its own down/up tracking instead of the
        // single ACTION_DOWN switch below.
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    centerLongPressFired = false;
                    handler.postDelayed(longPressRunnable, CENTER_LONG_PRESS_MS);
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longPressRunnable);
                if (!centerLongPressFired) {
                    onStartPressed();
                }
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    startSeekRepeat(keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                            ? -SEEK_STEP_MS : SEEK_STEP_MS);
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                stopSeekRepeat();
                return true;
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    playNext();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    playPrevious();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_SPACE:
                    togglePlayPause();
                    showControls();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * A single physical back press reaches this method twice - once for
     * {@code ACTION_DOWN}, once for {@code ACTION_UP} - and Android's
     * default {@code onBackPressed()} (which finishes the Activity) only
     * fires off an <em>unconsumed</em> {@code ACTION_UP}. Closing an
     * overlay/menu here only on {@code ACTION_DOWN} and returning {@code
     * true} looks locally correct, but by the time the matching {@code
     * ACTION_UP} arrives the state that decision was based on
     * ({@link #cookieLoginVisible}/{@link #featureMenuVisible}/
     * {@link #controlsVisible}) has already flipped back to "nothing
     * open" - so re-checking that state for the {@code ACTION_UP} would
     * wrongly fall through to the default handler and exit the whole app
     * as a side effect of closing an overlay. {@link #backConsumedSpecially}
     * instead remembers *this press's own* verdict across both actions.
     */
    private boolean dispatchBackKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (cookieLoginVisible) {
                hideCookieLogin();
                backConsumedSpecially = true;
            } else if (featureMenuVisible) {
                closeFeatureMenu();
                backConsumedSpecially = true;
            } else if (controlsVisible) {
                hideControls();
                backConsumedSpecially = true;
            } else {
                backConsumedSpecially = false;
            }
        }
        if (backConsumedSpecially) {
            if (event.getAction() == KeyEvent.ACTION_UP) backConsumedSpecially = false;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * The "start" button's behavior depends on current playback state
     * rather than just toggling: resuming from pause always just resumes
     * (one press), but pausing while already playing takes two presses -
     * the first only reveals the (possibly auto-hidden) overlay, the second
     * actually pauses. While buffering or waiting for the feed, a single
     * press toggles pause/resume without auto-starting when the user chose
     * to wait.
     */
    private void onStartPressed() {
        if (player == null) return;

        if (waitingForBuffer) {
            autoStartWhenReady = !autoStartWhenReady;
            if (autoStartWhenReady && repository.bufferSize() > 0) {
                waitingForBuffer = false;
                if (playlistIndex < 0) {
                    startInitialPlayback();
                } else {
                    playNext();
                }
            } else if (!autoStartWhenReady) {
                adjustPreloadForPause();
            }
            updatePlaybackChrome();
            showControls();
            return;
        }

        int state = player.getPlaybackState();
        if (state == Player.STATE_BUFFERING) {
            if (player.getPlayWhenReady()) {
                userPause();
            } else {
                resumeFromUserPause();
            }
            showControls();
            return;
        }

        if (!player.getPlayWhenReady()) {
            resumeFromUserPause();
            showControls();
        } else if (controlsVisible) {
            userPause();
            showControls();
        } else {
            showControls();
        }
    }

    private void togglePlayPause() {
        if (player == null) return;
        if (waitingForBuffer) {
            onStartPressed();
            return;
        }
        if (player.getPlayWhenReady()) {
            userPause();
        } else {
            resumeFromUserPause();
        }
        updatePlaybackChrome();
    }

    private void userPause() {
        autoStartWhenReady = false;
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        adjustPreloadForPause();
        updatePlaybackChrome();
    }

    private void resumeFromUserPause() {
        autoStartWhenReady = true;
        if (player == null) return;
        player.setPlayWhenReady(true);
        adjustPreloadForPlay();
        if (pendingAdvance > 0 && playlistIndex + 1 < playlist.size()) {
            pendingAdvance = 0;
            advance(1);
        } else if (pendingAdvance < 0 && playlistIndex > 0) {
            pendingAdvance = 0;
            advance(-1);
        } else {
            pendingAdvance = 0;
            player.play();
        }
        updatePlaybackChrome();
    }

    /** Full preload budget while the user is watching. */
    private void adjustPreloadForPlay() {
        handler.removeCallbacks(capPreloadWhilePausedRunnable);
        if (player != null) {
            player.setPreloadConfiguration(new PreloadConfiguration(PRELOAD_PLAYING_US));
        }
    }

    /**
     * While paused, buffer a slice of the next item, then stop ahead-fetching
     * until the user resumes (see {@link #capPreloadWhilePaused}).
     */
    private void adjustPreloadForPause() {
        if (player == null) return;
        player.setPreloadConfiguration(new PreloadConfiguration(PRELOAD_PAUSED_US));
        handler.removeCallbacks(capPreloadWhilePausedRunnable);
        handler.postDelayed(capPreloadWhilePausedRunnable, PRELOAD_PAUSE_SETTLE_MS);
    }

    private void capPreloadWhilePaused() {
        if (player == null || player.getPlayWhenReady()) return;
        player.setPreloadConfiguration(PreloadConfiguration.DEFAULT);
    }

    private void seekBy(long deltaMs) {
        if (player == null) return;
        long duration = player.getDuration();
        long target = player.getCurrentPosition() + deltaMs;
        if (target < 0) target = 0;
        if (duration != C.TIME_UNSET && target > duration) target = duration;
        player.seekTo(target);
    }

    /** First seek fires immediately; further seeks repeat while the key is held. */
    private void startSeekRepeat(long deltaMs) {
        seekRepeatDeltaMs = deltaMs;
        handler.removeCallbacks(seekRepeatRunnable);
        seekBy(deltaMs);
        showControls();
        handler.postDelayed(seekRepeatRunnable, SEEK_REPEAT_INITIAL_MS);
    }

    private void stopSeekRepeat() {
        seekRepeatDeltaMs = 0;
        handler.removeCallbacks(seekRepeatRunnable);
    }

    private void showControls() {
        controlsVisible = true;
        topPanel.setVisibility(View.VISIBLE);
        bottomPanel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideControlsRunnable);
        handler.postDelayed(hideControlsRunnable, CONTROLS_AUTO_HIDE_MS);
    }

    private void hideControls() {
        controlsVisible = false;
        topPanel.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.GONE);
        handler.removeCallbacks(hideControlsRunnable);
    }

    private void openFeatureMenu() {
        centerLongPressFired = true;
        featureMenuVisible = true;
        menuSelectedIndex = 0;
        updateMenuHighlight();
        featureMenu.setVisibility(View.VISIBLE);
        if (splashVisible) {
            splashOverlay.setVisibility(View.GONE);
        }
    }

    private void closeFeatureMenu() {
        featureMenuVisible = false;
        featureMenu.setVisibility(View.GONE);
        if (splashVisible) {
            splashOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void moveMenuSelection(int delta) {
        int size = menuItems.size();
        menuSelectedIndex = ((menuSelectedIndex + delta) % size + size) % size;
        updateMenuHighlight();
    }

    private void updateMenuHighlight() {
        for (int i = 0; i < menuItems.size(); i++) {
            menuItems.get(i).setBackgroundColor(i == menuSelectedIndex ? 0x33FFFFFF : 0x00000000);
        }
    }

    /** Only "登录账号"(/logout)/"检测登录状态" are wired up so far; the rest are still placeholders. */
    private void activateSelectedMenuItem() {
        int index = menuSelectedIndex;
        closeFeatureMenu();
        switch (index) {
            case 0:
                if (loggedIn) {
                    logout();
                } else {
                    startCookieLogin();
                }
                break;
            case 1:
                checkLoginStatusFromMenu();
                break;
            default:
                Toast.makeText(this, "功能开发中", Toast.LENGTH_SHORT).show();
                break;
        }
    }

    /**
     * Ad-hoc, user-triggered re-run of the same server-side check
     * {@link #applyCookieAndFinish} does right after a fresh login - lets
     * the user confirm days later whether douyin's servers still honor the
     * session currently held, without needing to go through the whole
     * scan-and-paste flow again just to find out. Reported via Toasts
     * rather than {@link #handoffStatusText} since the cookie overlay isn't
     * necessarily open when this runs.
     */
    private void checkLoginStatusFromMenu() {
        Toast.makeText(this, "正在向抖音服务器验证登录状态…", Toast.LENGTH_SHORT).show();
        new LoginStatusChecker().check(this, (ViewGroup) findViewById(R.id.playerRoot), new LoginStatusChecker.Callback() {
            @Override
            public void onResult(boolean loggedIn, String nickname, String avatarUrl) {
                setLoginState(loggedIn, nickname, avatarUrl);
                Toast.makeText(PlayerActivity.this,
                        loggedIn ? "已登录：" + nickname : "未登录（Cookie 已失效或从未成功登录）",
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onCheckFailed() {
                Toast.makeText(PlayerActivity.this, "验证超时，请检查网络后重试", Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Reflects a {@link LoginStatusChecker} result in the feature menu's
     * login row: its avatar/nickname while {@link #loggedIn}, or back to
     * the plain "登录账号" prompt once it isn't. This is the single place
     * that row's contents come from, fed by three different triggers - the
     * initial login flow, a manual "检测登录状态", and the silent startup
     * check - so all three stay visually consistent with each other.
     */
    private void setLoginState(boolean loggedIn, String nickname, String avatarUrl) {
        this.loggedIn = loggedIn;
        this.loggedInNickname = loggedIn ? nickname : null;
        this.loggedInAvatarUrl = loggedIn ? avatarUrl : null;
        if (pump != null) {
            pump.setLoggedIn(loggedIn);
        }
        WatchedAwemeStore.getInstance().bindSession();
        if (loggedIn) {
            featureMenuLoginText.setText(
                    loggedInNickname != null && !loggedInNickname.isEmpty() ? loggedInNickname : "已登录");
            featureMenuLoginAvatar.setVisibility(View.VISIBLE);
            SimpleImageLoader.load(loggedInAvatarUrl, featureMenuLoginAvatar);
        } else {
            featureMenuLoginText.setText("登录账号");
            featureMenuLoginAvatar.setVisibility(View.GONE);
            featureMenuLoginAvatar.setImageDrawable(null);
        }
    }

    /**
     * Selecting the login row while already {@link #loggedIn} logs out
     * instead of reopening the login flow: wipes the Cookie ({@link
     * CookieImportHelper#clear}), reverts the row back to "登录账号", and
     * reloads the feed so the pump WebView's next fetch goes out as an
     * anonymous session instead of continuing to ride the now-cleared one.
     * {@link #resetPlaybackAndFeed()} clears any videos buffered under the
     * old session so they are not played after logout.
     */
    private void logout() {
        CookieImportHelper.clear();
        setLoginState(false, null, null);
        resetPlaybackAndFeed();
        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show();
    }

    /**
     * Shows a QR code pointing at {@link CookieHandoffServer}'s local URL,
     * the only way in: there's no on-TV text field to paste a Cookie into,
     * since typing a 200+ character Cookie header with a remote's
     * on-screen keyboard is painful. Scanning it opens a form on the
     * phone/PC instead, where the user pastes the Cookie header copied out
     * of an already-logged-in browser session and submits it there; this
     * method's callback applies it here via {@link CookieImportHelper}.
     * This is the only login method the app offers at all: douyin's own web
     * login page (both the QR modal and the sso.douyin.com endpoints that
     * used to front it with a plain JSON API) is a JS-rendered page guarded
     * by a slide-to-verify anti-bot captcha that a hidden, never-shown
     * WebView can never solve, so there's no way to automate it from inside
     * this app.
     */
    private void startCookieLogin() {
        cookieLoginVisible = true;
        cookieLoginOverlay.setVisibility(View.VISIBLE);
        handoffStatusText.setTextColor(0xFFFFD54F);
        handoffStatusText.setText("等待手机扫码提交…");

        cookieHandoffServer = new CookieHandoffServer(this::applyCookieAndFinish);
        String url = cookieHandoffServer.start();
        if (url != null) {
            handoffRow.setVisibility(View.VISIBLE);
            handoffUrlText.setText(url);
            handoffQrImage.setImageBitmap(QrCodeGenerator.generate(url, 480));
        } else {
            handoffRow.setVisibility(View.GONE);
            handoffStatusText.setTextColor(0xFFFF5252);
            handoffStatusText.setText("未连接到WiFi/局域网，无法生成登录二维码");
        }
    }

    private void hideCookieLogin() {
        cookieLoginVisible = false;
        cookieLoginOverlay.setVisibility(View.GONE);
        stopCookieHandoffServer();
        handoffQrImage.setImageBitmap(null);
    }

    private void stopCookieHandoffServer() {
        if (cookieHandoffServer != null) {
            cookieHandoffServer.stop();
            cookieHandoffServer = null;
        }
    }

    /**
     * {@link CookieHandoffServer.Callback}, invoked once a phone/PC has
     * POSTed a Cookie. Finding a non-empty {@code sessionid} in that string
     * (all {@link CookieImportHelper} checks) only means the paste looked
     * well-formed - it says nothing about whether douyin's servers still
     * honor that particular session, so a stale/revoked/mistyped cookie
     * would otherwise get reported as a successful login with nothing to
     * show for it later. {@link LoginStatusChecker} closes that gap by
     * asking douyin's own servers directly before this declares success.
     */
    private void applyCookieAndFinish(String raw) {
        if (verifyingLogin) return; // previous submission's check still in flight
        if (!CookieImportHelper.apply(raw)) {
            handoffStatusText.setTextColor(0xFFFF5252);
            handoffStatusText.setText("未找到 sessionid，请确认复制了完整 Cookie 后重新扫码提交");
            return;
        }

        stopCookieHandoffServer();
        verifyingLogin = true;
        handoffStatusText.setTextColor(0xFFFFD54F);
        handoffStatusText.setText("Cookie 已应用，正在向抖音服务器验证登录状态…");

        new LoginStatusChecker().check(this, (ViewGroup) findViewById(R.id.playerRoot), new LoginStatusChecker.Callback() {
            @Override
            public void onResult(boolean loggedIn, String nickname, String avatarUrl) {
                verifyingLogin = false;
                if (loggedIn) {
                    setLoginState(true, nickname, avatarUrl);
                    handoffStatusText.setTextColor(0xFF69F0AE);
                    handoffStatusText.setText("登录成功：" + nickname);
                    resetPlaybackAndFeed();
                    // Leave the confirmed result on screen briefly instead of
                    // yanking the overlay away the instant it appears.
                    handler.postDelayed(PlayerActivity.this::hideCookieLogin, 1500);
                } else {
                    setLoginState(false, null, null);
                    handoffStatusText.setTextColor(0xFFFF5252);
                    handoffStatusText.setText("抖音服务器判定为未登录，Cookie 可能已失效，请重新获取后再扫码提交");
                    reopenCookieHandoff();
                }
            }

            @Override
            public void onCheckFailed() {
                verifyingLogin = false;
                handoffStatusText.setTextColor(0xFFFF5252);
                handoffStatusText.setText("验证超时，请检查网络后重新扫码提交");
                reopenCookieHandoff();
            }
        });
    }

    /** Re-opens submission after a failed verification (same URL/QR - {@link CookieHandoffServer}'s port is fixed). */
    private void reopenCookieHandoff() {
        if (!cookieLoginVisible) return; // user already backed out while this was verifying
        cookieHandoffServer = new CookieHandoffServer(this::applyCookieAndFinish);
        cookieHandoffServer.start();
    }

    private void playNext() {
        if (playlistIndex + 1 >= playlist.size()) {
            // Preload window exhausted (rare - refillPlaylist() normally keeps
            // PRELOAD_AHEAD items queued well before this point is reached).
            FeedVideo next = repository.pollNext();
            if (next == null) {
                waitingForBuffer = true;
                pendingAdvance = 1;
                updatePlaybackChrome();
                return;
            }
            playlist.add(next);
            player.addMediaItem(mediaItemFor(next));
        }
        waitingForBuffer = false;
        pendingAdvance = 0;
        advance(1);
    }

    private void playPrevious() {
        if (playlistIndex <= 0) return;
        pendingAdvance = 0;
        advance(-1);
    }

    private MediaItem mediaItemFor(FeedVideo video) {
        for (String url : video.playUrlCandidates) {
            DouyinPlaybackRegistry.register(url, video.awemeId);
        }
        return MediaItem.fromUri(playbackUrlFor(video))
                .buildUpon()
                .setMediaId(video.awemeId)
                .build();
    }

    private String playbackUrlFor(FeedVideo video) {
        if (video == null) return "";
        int idx = playUrlCandidateIndex.getOrDefault(video.awemeId, 0);
        if (idx >= 0 && idx < video.playUrlCandidates.size()) {
            return video.playUrlCandidates.get(idx);
        }
        return video.playUrl;
    }

    /** Tries the next CDN mirror when the current one returns HTTP 403. */
    private boolean retryCurrentWithNextCandidate() {
        if (current == null || player == null || playlistIndex < 0) return false;
        int nextIdx = playUrlCandidateIndex.getOrDefault(current.awemeId, 0) + 1;
        if (nextIdx >= current.playUrlCandidates.size()) return false;
        playUrlCandidateIndex.put(current.awemeId, nextIdx);
        String altUrl = current.playUrlCandidates.get(nextIdx);
        DouyinPlaybackRegistry.register(altUrl, current.awemeId);
        Log.w(TAG, "retry awemeId=" + current.awemeId + " candidate=" + nextIdx
                + "/" + current.playUrlCandidates.size() + " url=" + altUrl);
        player.replaceMediaItem(playlistIndex, MediaItem.fromUri(altUrl)
                .buildUpon()
                .setMediaId(current.awemeId)
                .build());
        player.seekTo(playlistIndex, 0);
        player.prepare();
        player.setPlayWhenReady(true);
        return true;
    }

    /**
     * Keeps {@link #playlistIndex}/{@link #current}/the on-screen info in
     * sync when ExoPlayer moves to the next item entirely on its own
     * (see {@code onMediaItemTransition} above) - the video content has
     * already switched by the time this runs, so unlike {@link #advance}
     * this deliberately does no slide animation and no seek (the player is
     * already there); it just catches the rest of this app's bookkeeping
     * up to what ExoPlayer already did, using {@code getCurrentMediaItemIndex()}
     * as ground truth rather than assuming it only ever moved by one.
     * <p>
     * {@link #rememberCurrentPosition} can't be reused here: by this point
     * {@code player.getDuration()}/{@code getCurrentPosition()} already
     * refer to the *new* item, not the one that just finished, so the just-
     * finished video's resume position is cleared directly instead.
     */
    private void handleAutoAdvance() {
        int newIndex = player.getCurrentMediaItemIndex();
        if (newIndex == playlistIndex || newIndex < 0 || newIndex >= playlist.size()) return;
        if (current != null) {
            resumePositions.remove(current.awemeId);
            markVideoFullyWatched(current);
        }
        playlistIndex = newIndex;
        refillPlaylist();
        Long resumeMs = resumePositions.get(playlist.get(playlistIndex).awemeId);
        if (resumeMs != null) {
            player.seekTo(resumeMs);
        }
        applyCurrentUi();
    }

    /**
     * Slides the whole content container off-screen, seeks the (already
     * preloaded) player to the neighboring playlist item while it's
     * off-screen, then slides it back in from the opposite edge - a
     * TikTok-style directional transition without needing a second
     * concurrent decoder (only one ExoPlayer instance is ever used, matching
     * this project's "never waste a decode session" design goal).
     *
     * @param direction {@code 1} for next (content exits up top, enters from
     *                  bottom), {@code -1} for previous (exits down, enters
     *                  from top).
     */
    private void advance(int direction) {
        Runnable step = () -> {
            rememberCurrentPosition();
            playlistIndex += direction;
            if (direction > 0) {
                player.seekToNextMediaItem();
                refillPlaylist();
            } else {
                player.seekToPreviousMediaItem();
            }
            Long resumeMs = resumePositions.get(playlist.get(playlistIndex).awemeId);
            if (resumeMs != null) {
                player.seekTo(resumeMs);
            }
            applyCurrentUi();
            player.play();
        };

        int height = contentContainer.getHeight();
        if (playlistIndex < 0 || height == 0) {
            step.run();
            return;
        }
        contentContainer.animate().cancel();
        contentContainer.animate()
                .translationY(-direction * height)
                .setDuration(SLIDE_DURATION_MS)
                .withEndAction(() -> {
                    step.run();
                    contentContainer.setTranslationY(direction * height);
                    contentContainer.animate()
                            .translationY(0)
                            .setDuration(SLIDE_DURATION_MS)
                            .start();
                })
                .start();
    }

    /**
     * Tops up the player's own playlist so {@link #PRELOAD_AHEAD} not-yet-
     * watched videos are always queued (ExoPlayer buffers upcoming playlist
     * items in the background while the current one plays), and trims
     * already-watched items beyond {@link #HISTORY_KEEP} so the playlist
     * doesn't grow unbounded over a long session.
     */
    private void refillPlaylist() {
        while (playlist.size() - (playlistIndex + 1) < PRELOAD_AHEAD) {
            FeedVideo next = repository.pollNext();
            if (next == null) break;
            playlist.add(next);
            player.addMediaItem(mediaItemFor(next));
        }
        while (playlistIndex > HISTORY_KEEP) {
            FeedVideo dropped = playlist.remove(0);
            resumePositions.remove(dropped.awemeId);
            player.removeMediaItem(0);
            playlistIndex--;
        }
    }

    /**
     * Saves {@link #current}'s live playback position into
     * {@link #resumePositions} before navigating away from it, unless it
     * just finished naturally (in which case any stale saved position is
     * cleared instead, so a full rewatch starts from 0 rather than the end).
     */
    private void rememberCurrentPosition() {
        if (current == null || player == null) return;
        long duration = player.getDuration();
        long position = player.getCurrentPosition();
        boolean finished = player.getPlaybackState() == Player.STATE_ENDED
                || (duration != C.TIME_UNSET && duration > 0 && position >= duration - 500);
        if (finished) {
            resumePositions.remove(current.awemeId);
            markVideoFullyWatched(current);
        } else {
            if (position >= WATCHED_THRESHOLD_MS
                    || (duration != C.TIME_UNSET && duration > 0 && position >= duration * 0.3)) {
                markVideoPartiallyWatched(current, position);
            }
            resumePositions.put(current.awemeId, position);
        }
    }

    private void markVideoFullyWatched(FeedVideo video) {
        repository.markConsumed(video.awemeId);
        if (loggedIn && player != null) {
            long duration = player.getDuration();
            long playMs = duration != C.TIME_UNSET && duration > 0 ? duration : WATCHED_THRESHOLD_MS;
            pump.reportPlay(video.awemeId, playMs);
        }
    }

    private void markVideoPartiallyWatched(FeedVideo video, long playMs) {
        repository.markConsumed(video.awemeId);
        if (loggedIn) pump.reportPlay(video.awemeId, playMs);
    }

    private void applyCurrentUi() {
        FeedVideo video = playlist.get(playlistIndex);
        current = video;
        coverImage.setAlpha(1f);
        SimpleImageLoader.loadWithBackdrop(video.coverUrl, coverImage, backdropImage);
        SimpleImageLoader.load(video.authorAvatarUrl, avatarImage);
        updateInfoPanel(video);
        updateLikeUi(video);
        updateCollectUi(video);
        commentCountText.setText(formatCount(video.commentCount));
        updateProgressUi();
        showControls();
    }

    private void updateInfoPanel(FeedVideo video) {
        boolean hasAuthor = video.authorName != null && !video.authorName.isEmpty();
        authorText.setVisibility(hasAuthor ? View.VISIBLE : View.GONE);
        authorText.setText(hasAuthor ? "@" + video.authorName : "");
        titleText.setText(highlightHashtags(video.desc == null ? "" : video.desc));
    }

    /** Colors "#tag" tokens to match douyin's own hashtag styling in the caption/title. */
    private static CharSequence highlightHashtags(String desc) {
        SpannableString spannable = new SpannableString(desc);
        Matcher matcher = HASHTAG_PATTERN.matcher(desc);
        while (matcher.find()) {
            spannable.setSpan(new ForegroundColorSpan(HASHTAG_COLOR),
                    matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return spannable;
    }

    private void updateLikeUi(FeedVideo video) {
        likeCountText.setText(formatCount(video.diggCount));
    }

    private void updateCollectUi(FeedVideo video) {
        collectCountText.setText(formatCount(video.collectCount));
    }

    /** Matches the "277.3K" / "1.2M" style douyin's own web UI displays counts in. */
    private static String formatCount(long count) {
        if (count >= 1_000_000) {
            return String.format(Locale.US, "%.1fM", count / 1_000_000.0);
        }
        if (count >= 1_000) {
            return String.format(Locale.US, "%.1fK", count / 1_000.0);
        }
        return String.valueOf(count);
    }

    /** Refreshes the position/duration labels, played fill, and buffered/preload fill. */
    private void updateProgressUi() {
        if (player == null) return;
        long position = Math.max(0, player.getCurrentPosition());
        long duration = player.getDuration();
        long buffered = player.getBufferedPosition();
        positionText.setText(formatTime(position));
        if (duration > 0 && duration != C.TIME_UNSET) {
            durationText.setText(formatTime(duration));
            int max = seekProgress.getMax();
            int played = (int) (position * max / duration);
            // Buffered/preloaded span: grows ahead of the playhead while loading.
            // Clamped to the current item's duration (100% = current video fully buffered).
            long bufferedEnd = Math.min(Math.max(buffered, position), duration);
            int bufferedPx = (int) (bufferedEnd * max / duration);
            seekProgress.setProgress(played);
            seekProgress.setSecondaryProgress(Math.max(bufferedPx, played));
        } else {
            durationText.setText("--:--");
            seekProgress.setProgress(0);
            seekProgress.setSecondaryProgress(0);
        }
    }

    /** mm:ss (matches the short-form clips this feed serves - no need for an hour digit). */
    private static String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void showLoading(boolean loading) {
        if (loading) {
            loadingSpinner.setVisibility(View.VISIBLE);
            if (!loadingSpinner.isAnimating()) loadingSpinner.playAnimation();
        } else {
            loadingSpinner.pauseAnimation();
            loadingSpinner.setVisibility(View.GONE);
        }
    }

    /**
     * Loading spinner vs pause icon: spinner only while actively trying to
     * play through a buffer/feed wait; pause icon when the user chose to
     * wait (including mid-buffer).
     */
    private void updatePlaybackChrome() {
        if (player == null && !waitingForBuffer) return;

        boolean wantsPlay = player == null || player.getPlayWhenReady();
        int state = player != null ? player.getPlaybackState() : Player.STATE_IDLE;
        boolean feedLoading = waitingForBuffer && (playlist.isEmpty() || pendingAdvance != 0);

        if (feedLoading && autoStartWhenReady) {
            showLoading(true);
            pauseIcon.setVisibility(View.GONE);
        } else if (feedLoading && !autoStartWhenReady) {
            showLoading(false);
            pauseIcon.setVisibility(View.VISIBLE);
        } else if (state == Player.STATE_BUFFERING && wantsPlay) {
            showLoading(true);
            pauseIcon.setVisibility(View.GONE);
        } else if (!wantsPlay && (state == Player.STATE_BUFFERING || state == Player.STATE_READY)) {
            showLoading(false);
            pauseIcon.setVisibility(View.VISIBLE);
        } else {
            showLoading(false);
            pauseIcon.setVisibility(View.GONE);
        }
    }

    @Override
    public void onBufferChanged(int size) {
        if (size <= 0 || !waitingForBuffer) return;

        cancelStartupTimeout();

        if (!autoStartWhenReady) {
            prepareFeedWhilePaused();
            waitingForBuffer = false;
            updatePlaybackChrome();
            return;
        }

        waitingForBuffer = false;
        if (playlistIndex < 0) {
            startInitialPlayback();
        } else {
            playNext();
        }
    }

    /**
     * Feed data arrived while the user paused: queue/prepare without starting
     * playback, and preload a slice of upcoming items in the background.
     */
    private void prepareFeedWhilePaused() {
        if (playlistIndex < 0) {
            refillPlaylist();
            if (playlist.isEmpty()) return;
            playlistIndex = 0;
            refillPlaylist();
            applyCurrentUi();
            player.prepare();
            player.setPlayWhenReady(false);
        } else {
            refillPlaylist();
        }
        adjustPreloadForPause();
    }
}
