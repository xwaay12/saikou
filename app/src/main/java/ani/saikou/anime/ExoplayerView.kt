package ani.saikou.anime

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.AudioManager.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings.System
import android.support.v4.media.session.MediaSessionCompat
import android.util.Rational
import android.util.TypedValue
import android.view.*
import android.view.KeyEvent.*
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.math.MathUtils.clamp
import androidx.core.view.WindowCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media.session.MediaButtonReceiver
import ani.saikou.*
import ani.saikou.R
import ani.saikou.anilist.Anilist
import ani.saikou.databinding.ActivityExoplayerBinding
import ani.saikou.media.Media
import ani.saikou.media.MediaDetailsViewModel
import ani.saikou.others.AniSkip
import ani.saikou.others.AniSkip.getType
import ani.saikou.others.ResettableTimer
import ani.saikou.others.getSerializable
import ani.saikou.parsers.*
import ani.saikou.settings.PlayerSettings
import ani.saikou.settings.PlayerSettingsActivity
import ani.saikou.settings.UserInterfaceSettings
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.C.AUDIO_CONTENT_TYPE_MOVIE
import com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.*
import com.google.android.exoplayer2.ui.CaptionStyleCompat.*
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.material.slider.Slider
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("SetTextI18n", "ClickableViewAccessibility")
class ExoplayerView : AppCompatActivity(), Player.Listener {

    private val resumeWindow = "resumeWindow"
    private val resumePosition = "resumePosition"
    private val playerFullscreen = "playerFullscreen"
    private val playerOnPlay = "playerOnPlay"

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var cacheFactory: CacheDataSource.Factory
    private lateinit var playbackParameters: PlaybackParameters
    private lateinit var mediaItem: MediaItem

    private lateinit var binding: ActivityExoplayerBinding
    private lateinit var playerView: StyledPlayerView
    private lateinit var exoPlay: ImageButton
    private lateinit var exoSource: ImageButton
    private lateinit var exoSettings: ImageButton
    private lateinit var exoSubtitle: ImageButton
    private lateinit var exoSubtitleView: SubtitleView
    private lateinit var exoRotate: ImageButton
    private lateinit var exoQuality: ImageButton
    private lateinit var exoSpeed: ImageButton
    private lateinit var exoScreen: ImageButton
    private lateinit var exoNext: ImageButton
    private lateinit var exoPrev: ImageButton
    private lateinit var exoSkipOpEd: ImageButton
    private lateinit var exoPip: ImageButton
    private lateinit var exoBrightness: Slider
    private lateinit var exoVolume: Slider
    private lateinit var exoBrightnessCont: View
    private lateinit var exoVolumeCont: View
    private lateinit var exoSkip: View
    private lateinit var skipTimeButton: View
    private lateinit var skipTimeText: TextView
    private lateinit var timeStampText: TextView
    private lateinit var animeTitle: TextView
    private lateinit var videoName: TextView
    private lateinit var videoInfo: TextView
    private lateinit var serverInfo: TextView
    private lateinit var episodeTitle: Spinner

    private var orientationListener: OrientationEventListener? = null

    private lateinit var media: Media
    private lateinit var episode: Episode
    private lateinit var episodes: MutableMap<String, Episode>
    private lateinit var episodeArr: List<String>
    private lateinit var episodeTitleArr: ArrayList<String>
    private var currentEpisodeIndex = 0
    private var epChanging = false

    private var extractor: VideoExtractor? = null
    private var video: Video? = null
    private var subtitle: Subtitle? = null
    private val player = "player_settings"

    private var notchHeight: Int = 0
    private var currentWindow = 0
    private var playbackPosition: Long = 0
    private var episodeLength: Float = 0f
    private var isFullscreen: Int = 0
    private var isInitialized = false
    private var isPlayerPlaying = true
    private var changingServer = false
    private var interacted = false

    private var pipEnabled = false
    private var aspectRatio = Rational(16, 9)

    var settings = PlayerSettings()
    private var uiSettings = UserInterfaceSettings()

    private val handler = Handler(Looper.getMainLooper())
    val model: MediaDetailsViewModel by viewModels()

    private var isTimeStampsLoaded = false

    var rotation = 0

    override fun onAttachedToWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val displayCutout = window.decorView.rootWindowInsets.displayCutout
            if (displayCutout != null) {
                if (displayCutout.boundingRects.size > 0) {
                    notchHeight = min(displayCutout.boundingRects[0].width(), displayCutout.boundingRects[0].height())
                    checkNotch()
                }
            }
        }
        super.onAttachedToWindow()
    }

    private fun checkNotch() {
        if (notchHeight != 0) {
            val orientation = resources.configuration.orientation
            playerView.findViewById<View>(R.id.exo_controller_margin).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    marginStart = notchHeight
                    marginEnd = notchHeight
                    topMargin = 0
                } else {
                    topMargin = notchHeight
                    marginStart = 0
                    marginEnd = 0
                }
            }
            playerView.findViewById<View>(R.id.exo_buffering).translationY =
                (if (orientation == Configuration.ORIENTATION_LANDSCAPE) 0 else (notchHeight + 8f.px)).dp
            exoBrightnessCont.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginEnd = if (orientation == Configuration.ORIENTATION_LANDSCAPE) notchHeight else 0
            }
            exoVolumeCont.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = if (orientation == Configuration.ORIENTATION_LANDSCAPE) notchHeight else 0
            }
        }
    }

    private fun setupSubFormatting(playerView: StyledPlayerView, settings: PlayerSettings) {
        val primaryColor = when (settings.primaryColor) {
            0    -> Color.BLACK
            1    -> Color.DKGRAY
            2    -> Color.GRAY
            3    -> Color.LTGRAY
            4    -> Color.WHITE
            5    -> Color.RED
            6    -> Color.YELLOW
            7    -> Color.GREEN
            8    -> Color.CYAN
            9    -> Color.BLUE
            10   -> Color.MAGENTA
            11   -> Color.TRANSPARENT
            else -> Color.WHITE
        }
        val secondaryColor = when (settings.secondaryColor) {
            0    -> Color.BLACK
            1    -> Color.DKGRAY
            2    -> Color.GRAY
            3    -> Color.LTGRAY
            4    -> Color.WHITE
            5    -> Color.RED
            6    -> Color.YELLOW
            7    -> Color.GREEN
            8    -> Color.CYAN
            9    -> Color.BLUE
            10   -> Color.MAGENTA
            11   -> Color.TRANSPARENT
            else -> Color.BLACK
        }
        val outline = when (settings.outline) {
            0    -> EDGE_TYPE_OUTLINE // Normal
            1    -> EDGE_TYPE_DEPRESSED // Shine
            2    -> EDGE_TYPE_DROP_SHADOW // Drop shadow
            3    -> EDGE_TYPE_NONE // No outline
            else -> EDGE_TYPE_OUTLINE // Normal
        }
        val subBackground = when (settings.subBackground) {
            0    -> Color.TRANSPARENT
            1    -> Color.BLACK
            2    -> Color.DKGRAY
            3    -> Color.GRAY
            4    -> Color.LTGRAY
            5    -> Color.WHITE
            6    -> Color.RED
            7    -> Color.YELLOW
            8    -> Color.GREEN
            9    -> Color.CYAN
            10   -> Color.BLUE
            11   -> Color.MAGENTA
            else -> Color.TRANSPARENT
        }
        val subWindow = when (settings.subWindow) {
            0    -> Color.TRANSPARENT
            1    -> Color.BLACK
            2    -> Color.DKGRAY
            3    -> Color.GRAY
            4    -> Color.LTGRAY
            5    -> Color.WHITE
            6    -> Color.RED
            7    -> Color.YELLOW
            8    -> Color.GREEN
            9    -> Color.CYAN
            10   -> Color.BLUE
            11   -> Color.MAGENTA
            else -> Color.TRANSPARENT
        }
        val font = when (settings.font) {
            0    -> ResourcesCompat.getFont(this, R.font.poppins_semi_bold)
            1    -> ResourcesCompat.getFont(this, R.font.poppins_bold)
            2    -> ResourcesCompat.getFont(this, R.font.poppins)
            3    -> ResourcesCompat.getFont(this, R.font.poppins_thin)
            else -> ResourcesCompat.getFont(this, R.font.poppins_semi_bold)
        }
        playerView.subtitleView?.setStyle(
            CaptionStyleCompat(
                primaryColor,
                subBackground,
                subWindow,
                outline,
                secondaryColor,
                font
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExoplayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        //Initialize
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        onBackPressedDispatcher.addCallback(this){
            finishAndRemoveTask()
        }

        settings = loadData("player_settings") ?: PlayerSettings().apply { saveData("player_settings", this) }
        uiSettings = loadData("ui_settings") ?: UserInterfaceSettings().apply { saveData("ui_settings", this) }

        playerView = findViewById(R.id.player_view)
        exoQuality = playerView.findViewById(R.id.exo_quality)
        exoPlay = playerView.findViewById(R.id.exo_play)
        exoSource = playerView.findViewById(R.id.exo_source)
        exoSettings = playerView.findViewById(R.id.exo_settings)
        exoSubtitle = playerView.findViewById(R.id.exo_sub)
        exoSubtitleView = playerView.findViewById(R.id.exo_subtitles)
        exoRotate = playerView.findViewById(R.id.exo_rotate)
        exoSpeed = playerView.findViewById(R.id.exo_playback_speed)
        exoScreen = playerView.findViewById(R.id.exo_screen)
        exoBrightness = playerView.findViewById(R.id.exo_brightness)
        exoVolume = playerView.findViewById(R.id.exo_volume)
        exoBrightnessCont = playerView.findViewById(R.id.exo_brightness_cont)
        exoVolumeCont = playerView.findViewById(R.id.exo_volume_cont)
        exoPip = playerView.findViewById(R.id.exo_pip)
        exoSkipOpEd = playerView.findViewById(R.id.exo_skip_op_ed)
        exoSkip = playerView.findViewById(R.id.exo_skip)
        skipTimeButton = playerView.findViewById(R.id.exo_skip_timestamp)
        skipTimeText = skipTimeButton.findViewById(R.id.exo_skip_timestamp_text)
        timeStampText = playerView.findViewById(R.id.exo_time_stamp_text)

        animeTitle = playerView.findViewById(R.id.exo_anime_title)
        episodeTitle = playerView.findViewById(R.id.exo_ep_sel)

        playerView.controllerShowTimeoutMs = 5000

        val audioManager = applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager

        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus({ focus ->
            when (focus) {
                AUDIOFOCUS_LOSS_TRANSIENT, AUDIOFOCUS_LOSS -> if (isInitialized) exoPlayer.pause()
            }
        }, AUDIO_CONTENT_TYPE_MOVIE, AUDIOFOCUS_GAIN)

        if (System.getInt(contentResolver, System.ACCELEROMETER_ROTATION, 0) != 1) {
            requestedOrientation = rotation
            exoRotate.setOnClickListener {
                requestedOrientation = rotation
                it.visibility = View.GONE
            }
            orientationListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation in 45..135) {
                        if (rotation != ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) exoRotate.visibility = View.VISIBLE
                        rotation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    } else if (orientation in 225..315) {
                        if (rotation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) exoRotate.visibility = View.VISIBLE
                        rotation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                }
            }
            orientationListener?.enable()
        }

        setupSubFormatting(playerView, settings)


        playerView.subtitleView?.alpha = when (settings.subtitles) {
            true  -> 1f
            false -> 0f
        }
        val fontSize = settings.fontSize.toFloat()
        playerView.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)

        if (savedInstanceState != null) {
            currentWindow = savedInstanceState.getInt(resumeWindow)
            playbackPosition = savedInstanceState.getLong(resumePosition)
            isFullscreen = savedInstanceState.getInt(playerFullscreen)
            isPlayerPlaying = savedInstanceState.getBoolean(playerOnPlay)
        }

        //BackButton
        playerView.findViewById<ImageButton>(R.id.exo_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        //TimeStamps
        model.timeStamps.observe(this) { it ->
            isTimeStampsLoaded = true
            exoSkipOpEd.visibility = if (it != null) {
                val adGroups = it.flatMap {
                    listOf(it.interval.startTime.toLong() * 1000, it.interval.endTime.toLong() * 1000)
                }.toLongArray()
                val playedAdGroups = it.flatMap {
                    listOf(false, false)
                }.toBooleanArray()
                playerView.setExtraAdGroupMarkers(adGroups, playedAdGroups)
                View.VISIBLE
            } else View.GONE
        }

        exoSkipOpEd.alpha = if (settings.autoSkipOPED) 1f else 0.3f
        exoSkipOpEd.setOnClickListener {
            settings.autoSkipOPED = if (settings.autoSkipOPED) {
                toastString("Disabled Auto Skipping OP & ED")
                false
            } else {
                toastString("Auto Skipping OP & ED")
                true
            }
            saveData("player_settings", settings)
            exoSkipOpEd.alpha = if (settings.autoSkipOPED) 1f else 0.3f
        }

        //Play Pause
        exoPlay.setOnClickListener {
            if (isInitialized) {
                isPlayerPlaying = exoPlayer.isPlaying
                (exoPlay.drawable as Animatable?)?.start()
                if (isPlayerPlaying) {
                    Glide.with(this).load(R.drawable.anim_play_to_pause).into(exoPlay)
                    exoPlayer.pause()
                } else {
                    Glide.with(this).load(R.drawable.anim_pause_to_play).into(exoPlay)
                    exoPlayer.play()
                }
            }
        }

        // Picture-in-picture
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pipEnabled = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) && settings.pip
            if (pipEnabled) {
                exoPip.visibility = View.VISIBLE
                exoPip.setOnClickListener {
                    enterPipMode()
                }
            } else exoPip.visibility = View.GONE
        }


        //Lock Button
        var locked = false
        val container = playerView.findViewById<View>(R.id.exo_controller_cont)
        val screen = playerView.findViewById<View>(R.id.exo_black_screen)
        val lockButton = playerView.findViewById<ImageButton>(R.id.exo_unlock)
        val timeline = playerView.findViewById<ExtendedTimeBar>(R.id.exo_progress)
        playerView.findViewById<ImageButton>(R.id.exo_lock).setOnClickListener {
            locked = true
            screen.visibility = View.GONE
            container.visibility = View.GONE
            lockButton.visibility = View.VISIBLE
            timeline.setForceDisabled(true)
        }
        lockButton.setOnClickListener {
            locked = false
            screen.visibility = View.VISIBLE
            container.visibility = View.VISIBLE
            it.visibility = View.GONE
            timeline.setForceDisabled(false)
        }

        //Skip Time Button
        if (settings.skipTime > 0) {
            exoSkip.findViewById<TextView>(R.id.exo_skip_time).text = settings.skipTime.toString()
            exoSkip.setOnClickListener {
                if (isInitialized)
                    exoPlayer.seekTo(exoPlayer.currentPosition + settings.skipTime * 1000)
            }
            exoSkip.setOnLongClickListener {
                val dialog = Dialog(this, R.style.DialogTheme)
                dialog.setContentView(R.layout.item_seekbar_dialog)
                dialog.setCancelable(true)
                dialog.setCanceledOnTouchOutside(true)
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                if (settings.skipTime <= 120) {
                    dialog.findViewById<Slider>(R.id.seekbar).value = settings.skipTime.toFloat()
                } else {
                    dialog.findViewById<Slider>(R.id.seekbar).value = 120f
                }
                dialog.findViewById<Slider>(R.id.seekbar).addOnChangeListener { _, value, _ ->
                    settings.skipTime = value.toInt()
                    saveData(player, settings)
                    playerView.findViewById<TextView>(R.id.exo_skip_time).text = settings.skipTime.toString()
                    dialog.findViewById<TextView>(R.id.seekbar_value).text = settings.skipTime.toString()
                }
                dialog.findViewById<Slider>(R.id.seekbar).addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) {}
                    override fun onStopTrackingTouch(slider: Slider) {
                        dialog.dismiss()
                    }
                })
                dialog.findViewById<TextView>(R.id.seekbar_title).text = getString(R.string.skip_time)
                dialog.findViewById<TextView>(R.id.seekbar_value).text = settings.skipTime.toString()
                @Suppress("DEPRECATION")
                dialog.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                dialog.show()
                true
            }
        } else {
            exoSkip.visibility = View.GONE
        }

        val gestureSpeed = (300 * uiSettings.animationSpeed).toLong()
        //Player UI Visibility Handler
        val brightnessRunnable = Runnable {
            if (exoBrightnessCont.alpha == 1f)
                lifecycleScope.launch {
                    ObjectAnimator.ofFloat(exoBrightnessCont, "alpha", 1f, 0f).setDuration(gestureSpeed).start()
                    delay(gestureSpeed)
                    exoBrightnessCont.visibility = View.GONE
                    checkNotch()
                }
        }
        val volumeRunnable = Runnable {
            if (exoVolumeCont.alpha == 1f)
                lifecycleScope.launch {
                    ObjectAnimator.ofFloat(exoVolumeCont, "alpha", 1f, 0f).setDuration(gestureSpeed).start()
                    delay(gestureSpeed)
                    exoVolumeCont.visibility = View.GONE
                    checkNotch()
                }
        }
        playerView.setControllerVisibilityListener(StyledPlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.GONE) {
                hideSystemBars()
                brightnessRunnable.run()
                volumeRunnable.run()
            }
        })
        val overshoot = AnimationUtils.loadInterpolator(this, R.anim.over_shoot)
        val controllerDuration = (uiSettings.animationSpeed * 200).toLong()
        fun handleController() {
            if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) !isInPictureInPictureMode else true) {
                if (playerView.isControllerFullyVisible) {
                    ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_controller), "alpha", 1f, 0f)
                        .setDuration(controllerDuration).start()
                    ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_bottom_cont), "translationY", 0f, 128f)
                        .apply { interpolator = overshoot;duration = controllerDuration;start() }
                    ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_timeline_cont), "translationY", 0f, 128f)
                        .apply { interpolator = overshoot;duration = controllerDuration;start() }
                    ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_top_cont), "translationY", 0f, -128f)
                        .apply { interpolator = overshoot;duration = controllerDuration;start() }
                    playerView.postDelayed({ playerView.hideController() }, controllerDuration)
                } else {
                    checkNotch()
                    playerView.showController()
                    ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_controller), "alpha", 0f, 1f)
                        .setDuration(controllerDuration).start()
                    ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_bottom_cont), "translationY", 128f, 0f)
                        .apply { interpolator = overshoot;duration = controllerDuration;start() }
                    ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_timeline_cont), "translationY", 128f, 0f)
                        .apply { interpolator = overshoot;duration = controllerDuration;start() }
                    ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_top_cont), "translationY", -128f, 0f)
                        .apply { interpolator = overshoot;duration = controllerDuration;start() }
                }
            }
        }

        playerView.findViewById<View>(R.id.exo_full_area).setOnClickListener {
            handleController()
        }

        val rewindText = playerView.findViewById<TextView>(R.id.exo_fast_rewind_anim)
        val forwardText = playerView.findViewById<TextView>(R.id.exo_fast_forward_anim)
        val fastForwardCard = playerView.findViewById<View>(R.id.exo_fast_forward)
        val fastRewindCard = playerView.findViewById<View>(R.id.exo_fast_rewind)


        //Seeking
        val seekTimerF = ResettableTimer()
        val seekTimerR = ResettableTimer()
        var seekTimesF = 0
        var seekTimesR = 0

        fun seek(forward: Boolean, event: MotionEvent? = null) {
            val views = if (forward) {
                forwardText.text = "+${settings.seekTime * ++seekTimesF}"
                handler.post { exoPlayer.seekTo(exoPlayer.currentPosition + settings.seekTime * 1000) }
                fastForwardCard to forwardText
            } else {
                rewindText.text = "-${settings.seekTime * ++seekTimesR}"
                handler.post { exoPlayer.seekTo(exoPlayer.currentPosition - settings.seekTime * 1000) }
                fastRewindCard to rewindText
            }
            startDoubleTapped(views.first, views.second, event, forward)
            if (forward) {
                seekTimerR.reset(object : TimerTask() {
                    override fun run() {
                        stopDoubleTapped(views.first, views.second)
                        seekTimesF = 0
                    }
                }, 850)
            } else {
                seekTimerF.reset(object : TimerTask() {
                    override fun run() {
                        stopDoubleTapped(views.first, views.second)
                        seekTimesR = 0
                    }
                }, 850)
            }
        }

        if (!settings.doubleTap) {
            playerView.findViewById<View>(R.id.exo_fast_forward_button_cont).visibility = View.VISIBLE
            playerView.findViewById<View>(R.id.exo_fast_rewind_button_cont).visibility = View.VISIBLE
            playerView.findViewById<ImageButton>(R.id.exo_fast_forward_button).setOnClickListener {
                if (isInitialized) {
                    seek(true)
                }
            }
            playerView.findViewById<ImageButton>(R.id.exo_fast_rewind_button).setOnClickListener {
                if (isInitialized) {
                    seek(false)
                }
            }
        }

        keyMap[KEYCODE_DPAD_RIGHT] = { seek(true) }
        keyMap[KEYCODE_DPAD_LEFT] = { seek(false) }

        //Screen Gestures
        if (settings.gestures || settings.doubleTap) {

            fun doubleTap(forward: Boolean, event: MotionEvent) {
                if (!locked && isInitialized && settings.doubleTap) {
                    seek(forward, event)
                }
            }

            //Brightness
            var brightnessTimer = Timer()
            exoBrightnessCont.visibility = View.GONE

            fun brightnessHide() {
                brightnessTimer.cancel()
                brightnessTimer.purge()
                val timerTask: TimerTask = object : TimerTask() {
                    override fun run() {
                        handler.post(brightnessRunnable)
                    }
                }
                brightnessTimer = Timer()
                brightnessTimer.schedule(timerTask, 3000)
            }
            exoBrightness.value = (getCurrentBrightnessValue(this) * 10f)

            exoBrightness.addOnChangeListener { _, value, _ ->
                val lp = window.attributes
                lp.screenBrightness = brightnessConverter(value / 10, false)
                window.attributes = lp
                brightnessHide()
            }

            //Volume
            var volumeTimer = Timer()
            exoVolumeCont.visibility = View.GONE

            val volumeMax = audioManager.getStreamMaxVolume(STREAM_MUSIC)
            exoVolume.value = audioManager.getStreamVolume(STREAM_MUSIC).toFloat() / volumeMax * 10
            fun volumeHide() {
                volumeTimer.cancel()
                volumeTimer.purge()
                val timerTask: TimerTask = object : TimerTask() {
                    override fun run() {
                        handler.post(volumeRunnable)
                    }
                }
                volumeTimer = Timer()
                volumeTimer.schedule(timerTask, 3000)
            }
            exoVolume.addOnChangeListener { _, value, _ ->
                val volume = (value / 10 * volumeMax).roundToInt()
                audioManager.setStreamVolume(STREAM_MUSIC, volume, 0)
                volumeHide()
            }

            //FastRewind (Left Panel)
            val fastRewindDetector = GestureDetector(this, object : GesturesListener() {
                override fun onDoubleClick(event: MotionEvent) {
                    doubleTap(false, event)
                }

                override fun onScrollYClick(y: Float) {
                    if (!locked && settings.gestures) {
                        exoBrightness.value = clamp(exoBrightness.value + y / 100, 0f, 10f)
                        if (exoBrightnessCont.visibility != View.VISIBLE) {
                            exoBrightnessCont.visibility = View.VISIBLE
                        }
                        exoBrightnessCont.alpha = 1f
                    }
                }

                override fun onSingleClick(event: MotionEvent) = handleController()
            })
            val rewindArea = playerView.findViewById<View>(R.id.exo_rewind_area)
            rewindArea.isClickable = true
            rewindArea.setOnTouchListener { v, event ->
                fastRewindDetector.onTouchEvent(event)
                v.performClick()
                true
            }

            //FastForward (Right Panel)
            val fastForwardDetector = GestureDetector(this, object : GesturesListener() {
                override fun onDoubleClick(event: MotionEvent) {
                    doubleTap(true, event)
                }

                override fun onScrollYClick(y: Float) {
                    if (!locked && settings.gestures) {
                        exoVolume.value = clamp(exoVolume.value + y / 100, 0f, 10f)
                        if (exoVolumeCont.visibility != View.VISIBLE) {
                            exoVolumeCont.visibility = View.VISIBLE
                        }
                        exoVolumeCont.alpha = 1f
                    }
                }

                override fun onSingleClick(event: MotionEvent) = handleController()
            })
            val forwardArea = playerView.findViewById<View>(R.id.exo_forward_area)
            forwardArea.isClickable = true
            forwardArea.setOnTouchListener { v, event ->
                fastForwardDetector.onTouchEvent(event)
                v.performClick()
                true
            }
        }

        //Handle Media
        try {
            media = (intent.getSerializable("media",Media::class)) ?: return
        } catch (e: Exception) {
            toast(e.toString())
            return
        }
        model.setMedia(media)
        title = media.userPreferredName
        episodes = media.anime?.episodes ?: return

        videoName = playerView.findViewById(R.id.exo_video_name)
        videoInfo = playerView.findViewById(R.id.exo_video_info)
        serverInfo = playerView.findViewById(R.id.exo_server_info)

        if (!settings.videoInfo) {
            videoName.visibility = View.GONE
            videoInfo.visibility = View.GONE
            serverInfo.visibility = View.GONE
        } else {
            videoName.isSelected = true
        }

        model.watchSources = if (media.isAdult) HAnimeSources else AnimeSources
        serverInfo.text = model.watchSources!!.names[media.selected!!.source]

        model.epChanged.observe(this) {
            epChanging = !it
        }

        // Subtitle View Margin fix for Kamyroll
        if(model.watchSources!!.names[media.selected!!.source] == "Kamyroll" && (settings.kamySubType == 0 || settings.kamySubType == 2)) {
            val marginInt = -19 // This gets rounded to -18dp
            val margin = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, marginInt.toFloat(), resources.displayMetrics)).roundToInt()
            exoSubtitleView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = margin
            }
        }

        //Anime Title
        animeTitle.text = media.userPreferredName

        episodeArr = episodes.keys.toList()
        currentEpisodeIndex = episodeArr.indexOf(media.anime!!.selectedEpisode!!)

        episodeTitleArr = arrayListOf()
        episodes.forEach {
            val episode = it.value
            episodeTitleArr.add("${if (!episode.title.isNullOrEmpty() && episode.title != "null") "" else "Episode "}${episode.number}${if (episode.filler) " [Filler]" else ""}${if (!episode.title.isNullOrEmpty() && episode.title != "null") " : " + episode.title else ""}")
        }

        //Episode Change
        fun change(index: Int) {
            if (isInitialized) {
                changingServer = false
                saveData(
                    "${media.id}_${episodeArr[currentEpisodeIndex]}",
                    exoPlayer.currentPosition,
                    this
                )
                val prev = episodeArr[currentEpisodeIndex]
                isTimeStampsLoaded = false
                episodeLength = 0f
                media.anime!!.selectedEpisode = episodeArr[index]
                model.setMedia(media)
                model.epChanged.postValue(false)
                model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "change")
                model.onEpisodeClick(
                    media, media.anime!!.selectedEpisode!!, this.supportFragmentManager,
                    false,
                    prev
                )
            }
        }

        //EpisodeSelector
        episodeTitle.adapter = NoPaddingArrayAdapter(this, R.layout.item_dropdown, episodeTitleArr)
        episodeTitle.setSelection(currentEpisodeIndex)
        episodeTitle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                if (position != currentEpisodeIndex) change(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        //Next Episode
        exoNext = playerView.findViewById(R.id.exo_next_ep)
        exoNext.setOnClickListener {
            if (isInitialized) {
                nextEpisode { i ->
                    updateAniProgress()
                    change(currentEpisodeIndex + i)
                }
            }
        }
        //Prev Episode
        exoPrev = playerView.findViewById(R.id.exo_prev_ep)
        exoPrev.setOnClickListener {
            if (currentEpisodeIndex > 0) {
                change(currentEpisodeIndex - 1)
            } else
                toastString("This is the 1st Episode!")
        }

        model.getEpisode().observe(this) {
            hideSystemBars()
            if (it != null && !epChanging) {
                episode = it
                media.selected = model.loadSelected(media)
                model.setMedia(media)
                currentEpisodeIndex = episodeArr.indexOf(it.number)
                episodeTitle.setSelection(currentEpisodeIndex)
                if (isInitialized) releasePlayer()
                playbackPosition = loadData("${media.id}_${it.number}", this) ?: 0
                initPlayer()
                preloading = false
                updateProgress()
            }
        }

        //FullScreen
        isFullscreen = loadData("${media.id}_fullscreenInt", this) ?: isFullscreen
        playerView.resizeMode = when (isFullscreen) {
            0    -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            1    -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2    -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        exoScreen.setOnClickListener {
            if (isFullscreen < 2) isFullscreen += 1 else isFullscreen = 0
            playerView.resizeMode = when (isFullscreen) {
                0    -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                1    -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2    -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            toastString(
                when (isFullscreen) {
                    0    -> "Original"
                    1    -> "Zoom"
                    2    -> "Stretch"
                    else -> "Original"
                }
            )
            saveData("${media.id}_fullscreenInt", isFullscreen, this)
        }

        //Cast
        if (settings.cast) {
            playerView.findViewById<ImageButton>(R.id.exo_cast).apply {
                visibility = View.VISIBLE
                setSafeOnClickListener {
                    cast()
                }
            }
        }

        //Settings
        exoSettings.setOnClickListener {
            saveData("${media.id}_${media.anime!!.selectedEpisode}", exoPlayer.currentPosition, this)
            val intent = Intent(this, PlayerSettingsActivity::class.java).apply {
                putExtra("media", media)
                putExtra("subtitle", subtitle)
            }
            finish()
            startActivity(intent)
        }

        //Speed
        val speeds =
            if (settings.cursedSpeeds)
                arrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 10f, 25f, 50f)
            else
                arrayOf(0.25f, 0.33f, 0.5f, 0.66f, 0.75f, 1f, 1.25f, 1.33f, 1.5f, 1.66f, 1.75f, 2f)

        val speedsName = speeds.map { "${it}x" }.toTypedArray()
        var curSpeed = loadData("${media.id}_speed", this) ?: settings.defaultSpeed

        playbackParameters = PlaybackParameters(speeds[curSpeed])
        var speed: Float
        val speedDialog = AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Speed")
        exoSpeed.setOnClickListener {
            speedDialog.setSingleChoiceItems(speedsName, curSpeed) { dialog, i ->
                if (isInitialized) {
                    saveData("${media.id}_speed", i, this)
                    speed = speeds[i]
                    curSpeed = i
                    playbackParameters = PlaybackParameters(speed)
                    exoPlayer.playbackParameters = playbackParameters
                    dialog.dismiss()
                    hideSystemBars()
                }
            }.show()
        }
        speedDialog.setOnCancelListener { hideSystemBars() }

        if (settings.autoPlay) {
            var touchTimer = Timer()
            fun touched() {
                interacted = true
                touchTimer.apply {
                    cancel()
                    purge()
                }
                touchTimer = Timer()
                touchTimer.schedule(object : TimerTask() {
                    override fun run() {
                        interacted = false
                    }
                }, 1000 * 60 * 60)
            }
            playerView.findViewById<View>(R.id.exo_touch_view).setOnTouchListener { _, _ ->
                touched()
                false
            }
        }

        isFullscreen = settings.resize
        playerView.resizeMode = when (settings.resize) {
            0    -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            1    -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2    -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        preloading = false
        val showProgressDialog = if (settings.askIndividual) loadData<Boolean>("${media.id}_progressDialog") ?: true else false
        if (showProgressDialog && Anilist.userid != null && if (media.isAdult) settings.updateForH else true)
            AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Auto Update progress for ${media.userPreferredName}?")
                .apply {
                    setOnCancelListener { hideSystemBars() }
                    setCancelable(false)
                    setPositiveButton("Yes") { dialog, _ ->
                        saveData("${media.id}_progressDialog", false)
                        saveData("${media.id}_save_progress", true)
                        dialog.dismiss()
                        model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "invoke")
                    }
                    setNegativeButton("No") { dialog, _ ->
                        saveData("${media.id}_progressDialog", false)
                        saveData("${media.id}_save_progress", false)
                        toast("You can long click List Editor button to Reset Auto Update")
                        dialog.dismiss()
                        model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "invoke")
                    }
                    show()
                }
        else model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "invoke")

        //Start the recursive Fun
        if (settings.timeStampsEnabled)
            updateTimeStamp()
    }

    private fun initPlayer() {
        checkNotch()

        saveData("${media.id}_current_ep", media.anime!!.selectedEpisode!!, this)

        val set = loadData<MutableSet<Int>>("continue_ANIME", this) ?: mutableSetOf()
        if (set.contains(media.id)) set.remove(media.id)
        set.add(media.id)
        saveData("continue_ANIME", set, this)

        lifecycleScope.launch(Dispatchers.IO) {
            extractor?.onVideoStopped(video)
        }

        val ext = episode.extractors?.find { it.server.name == episode.selectedExtractor } ?: return
        extractor = ext
        video = ext.videos.getOrNull(episode.selectedVideo) ?: return

        val subLang: String? = loadData("subLang_${media.id}", this)
        if(subLang == null) {
            subtitle = ext.subtitles.let { sub ->
                when (episode.selectedSubtitle) {
                    null -> null
                    -1   -> sub.find { it.language == "English" || it.language == "en-US" }
                    else -> sub.getOrNull(episode.selectedSubtitle!!)
                }
            }
        }
        if(subLang == "none") {
            subtitle = ext.subtitles.let { null }
        } else {
            subtitle = ext.subtitles.let { sub ->
                sub.find { it.language == subLang || it.language == when(subLang){
                    "ja-JP"  -> "Japanese"
                    "en-US"  -> "English"
                    "de-DE"  -> "German"
                    "es-ES"  -> "Spanish"
                    "es-419" -> "Spanish"
                    "fr-FR"  -> "French"
                    "it-IT"  -> "Italian"
                    "pt-BR"  -> "Portuguese (Brazil)"
                    "pt-PT"  -> "Portuguese (Portugal)"
                    "ru-RU"  -> "Russian"
                    "zh-CN"  -> "Chinese"
                    "tr-TR"  -> "Turkish"
                    "ar-ME"  -> "Arabic"
                    else     -> "English"
                    }
                }
            }
        }

        //Subtitles
        exoSubtitle.visibility = if (ext.subtitles.isNotEmpty()) View.VISIBLE else View.GONE
        exoSubtitle.setOnClickListener {
            subClick()
        }

        val newSub = intent.getSerializable("subtitle", Subtitle::class)
        var sub: MediaItem.SubtitleConfiguration? = null
        if(newSub == null && subtitle != null) {
            sub = MediaItem.SubtitleConfiguration
                    .Builder(Uri.parse(subtitle!!.url.url))
                    .setSelectionFlags(C.SELECTION_FLAG_FORCED)
                    .setMimeType(
                        when (subtitle?.type) {
                            SubtitleType.VTT -> MimeTypes.TEXT_VTT
                            SubtitleType.ASS -> MimeTypes.TEXT_SSA
                            SubtitleType.SRT -> MimeTypes.APPLICATION_SUBRIP
                            else             -> MimeTypes.TEXT_UNKNOWN
                        }
                    )
                    .build()
        }
        if(newSub != null){
            sub = MediaItem.SubtitleConfiguration
                    .Builder(Uri.parse(newSub.url.url))
                    .setSelectionFlags(C.SELECTION_FLAG_FORCED)
                    .setMimeType(when (newSub.type) {
                        SubtitleType.VTT -> MimeTypes.TEXT_VTT
                        SubtitleType.ASS -> MimeTypes.TEXT_SSA
                        SubtitleType.SRT -> MimeTypes.APPLICATION_SUBRIP
                        }
                    )
                    .build()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            ext.onVideoPlayed(video)
        }

        val but = playerView.findViewById<ImageButton>(R.id.exo_download)
        if (video?.format == VideoType.CONTAINER) {
            but.visibility = View.VISIBLE
            but.setOnClickListener {
                download(this, episode, animeTitle.text.toString())
            }
        } else but.visibility = View.GONE

        val simpleCache = VideoCache.getInstance(this)
        val httpClient = okHttpClient.newBuilder().apply {
            ignoreAllSSLErrors()
            followRedirects(true)
            followSslRedirects(true)
        }.build()
        val dataSourceFactory = DataSource.Factory {
            val dataSource: HttpDataSource = OkHttpDataSource.Factory(httpClient).createDataSource()
            defaultHeaders.forEach {
                dataSource.setRequestProperty(it.key, it.value)
            }
            video?.url?.headers?.forEach {
                dataSource.setRequestProperty(it.key, it.value)
            }
            dataSource
        }
        cacheFactory = CacheDataSource.Factory().apply {
            setCache(simpleCache)
            setUpstreamDataSourceFactory(dataSourceFactory)
        }

        val mimeType = when (video?.format) {
            VideoType.M3U8 -> MimeTypes.APPLICATION_M3U8
            VideoType.DASH -> MimeTypes.APPLICATION_MPD
            else           -> MimeTypes.APPLICATION_MP4
        }

        val builder = MediaItem.Builder().setUri(video!!.url.url).setMimeType(mimeType)

        if (sub != null) builder.setSubtitleConfigurations(mutableListOf(sub))
        mediaItem = builder.build()

        //Source
        exoSource.setOnClickListener {
            sourceClick()
        }

        //Quality Track
        trackSelector = DefaultTrackSelector(this)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setMinVideoSize(loadData("maxWidth", this) ?: 720, loadData("maxHeight", this) ?: 480)
                .setMaxVideoSize(1, 1)
        )

        if (playbackPosition != 0L && !changingServer && !settings.alwaysContinue) {
            val time = String.format(
                "%02d:%02d:%02d", TimeUnit.MILLISECONDS.toHours(playbackPosition),
                TimeUnit.MILLISECONDS.toMinutes(playbackPosition) - TimeUnit.HOURS.toMinutes(
                    TimeUnit.MILLISECONDS.toHours(
                        playbackPosition
                    )
                ),
                TimeUnit.MILLISECONDS.toSeconds(playbackPosition) - TimeUnit.MINUTES.toSeconds(
                    TimeUnit.MILLISECONDS.toMinutes(
                        playbackPosition
                    )
                )
            )
            AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Continue from ${time}?").apply {
                setCancelable(false)
                setPositiveButton("Yes") { d, _ ->
                    buildExoplayer()
                    d.dismiss()
                }
                setNegativeButton("No") { d, _ ->
                    playbackPosition = 0L
                    buildExoplayer()
                    d.dismiss()
                }
            }.show()
        } else buildExoplayer()
    }

    private fun buildExoplayer() {
        //Player
        hideSystemBars()
        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setTrackSelector(trackSelector)
            .build().apply {
                playWhenReady = true
                this.playbackParameters = this@ExoplayerView.playbackParameters
                setMediaItem(mediaItem)
                prepare()
                loadData<Long>("${media.id}_${media.anime!!.selectedEpisode}_max")?.apply {
                    if (this <= playbackPosition) playbackPosition = max(0, this - 5)
                }
                seekTo(playbackPosition)
            }
        playerView.player = exoPlayer

        try {
            val mediaButtonReceiver = ComponentName(this, MediaButtonReceiver::class.java)
            MediaSessionCompat(this, "Player", mediaButtonReceiver, null).let { media ->
                val mediaSessionConnector = MediaSessionConnector(media)
                mediaSessionConnector.setPlayer(exoPlayer)
                media.isActive = true
            }
        } catch (e: Exception) {
            toast(e.toString())
        }

        exoPlayer.addListener(this)
        isInitialized = true
    }

    private fun releasePlayer() {
        isPlayerPlaying = exoPlayer.playWhenReady
        playbackPosition = exoPlayer.currentPosition
        exoPlayer.release()
        VideoCache.release()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (isInitialized) {
            outState.putInt(resumeWindow, exoPlayer.currentMediaItemIndex)
            outState.putLong(resumePosition, exoPlayer.currentPosition)
        }
        outState.putInt(playerFullscreen, isFullscreen)
        outState.putBoolean(playerOnPlay, isPlayerPlaying)
        super.onSaveInstanceState(outState)
    }

    private fun sourceClick() {
        changingServer = true

        media.selected!!.server = null
        saveData("${media.id}_${media.anime!!.selectedEpisode}", exoPlayer.currentPosition, this)
        model.saveSelected(media.id, media.selected!!, this)
        model.onEpisodeClick(
            media, episode.number, this.supportFragmentManager,
            launch = false
        )
    }

    private fun subClick() {
        saveData("${media.id}_${media.anime!!.selectedEpisode}", exoPlayer.currentPosition, this)
        model.saveSelected(media.id, media.selected!!, this)
        SubtitleDialogFragment().show(supportFragmentManager, "dialog")
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
        if (isInitialized) {
            playerView.player?.pause()
            saveData("${media.id}_${media.anime!!.selectedEpisode}", exoPlayer.currentPosition, this)
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener?.enable()
        hideSystemBars()
        if (isInitialized) {
            playerView.onResume()
            playerView.useController = true
        }
    }

    override fun onStop() {
        playerView.player?.pause()
        super.onStop()
    }

    private var wasPlaying = false
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (settings.focusPause && !epChanging) {
            if (isInitialized && !hasFocus) wasPlaying = exoPlayer.isPlaying
            if (hasFocus) {
                if (isInitialized && wasPlaying) exoPlayer.play()
            } else {
                if (isInitialized) exoPlayer.pause()
            }
        }
        super.onWindowFocusChanged(hasFocus)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isBuffering) {
            isPlayerPlaying = isPlaying
            playerView.keepScreenOn = isPlaying
            (exoPlay.drawable as Animatable?)?.start()
            if (!this.isDestroyed) Glide.with(this)
                .load(if (isPlaying) R.drawable.anim_play_to_pause else R.drawable.anim_pause_to_play).into(exoPlay)
        }
    }

    override fun onRenderedFirstFrame() {
        super.onRenderedFirstFrame()
        saveData("${media.id}_${media.anime!!.selectedEpisode}_max", exoPlayer.duration, this)
        val height = (exoPlayer.videoFormat ?: return).height
        val width = (exoPlayer.videoFormat ?: return).width

        if (video?.format != VideoType.CONTAINER) {
            saveData("maxHeight", height)
            saveData("maxWidth", width)
        }

        aspectRatio = Rational(width, height)

        videoName.text = episode.selectedExtractor
        videoInfo.text = "$width x $height"

        if (exoPlayer.duration < playbackPosition)
            exoPlayer.seekTo(0)

        if (!isTimeStampsLoaded) {
            val dur = exoPlayer.duration
            lifecycleScope.launch(Dispatchers.IO) {
                model.loadTimeStamps(
                    media.idMAL,
                    media.anime?.selectedEpisode?.trim()?.toIntOrNull(),
                    dur / 1000
                )
            }
        }
    }

    //Link Preloading
    private var preloading = false
    private fun updateProgress() {
        if (isInitialized) {
            if (exoPlayer.currentPosition.toFloat() / exoPlayer.duration > settings.watchPercentage) {
                preloading = true
                nextEpisode(false) { i ->
                    val ep = episodes[episodeArr[currentEpisodeIndex + i]] ?: return@nextEpisode
                    val selected = media.selected ?: return@nextEpisode
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (media.selected!!.server != null)
                            model.loadEpisodeSingleVideo(ep, selected, false)
                        else
                            model.loadEpisodeVideos(ep, selected.source, false)
                    }
                }
            }
        }
        if (!preloading) handler.postDelayed({
            updateProgress()
        }, 2500)
    }

    //TimeStamp Updating
    private var currentTimeStamp: AniSkip.Stamp? = null
    private var skippedTimeStamps: MutableList<AniSkip.Stamp> = mutableListOf()
    private fun updateTimeStamp() {
        if (isInitialized) {
            val playerCurrentTime = exoPlayer.currentPosition / 1000
            currentTimeStamp = model.timeStamps.value?.find { timestamp ->
                timestamp.interval.startTime < playerCurrentTime && playerCurrentTime < (timestamp.interval.endTime - 1)
            }

            val new = currentTimeStamp
            timeStampText.text = if (new != null) {
                if (settings.showTimeStampButton) {
                    skipTimeButton.visibility = View.VISIBLE
                    exoSkip.visibility = View.GONE
                    skipTimeText.text = new.skipType.getType()
                    skipTimeButton.setOnClickListener {
                        exoPlayer.seekTo((new.interval.endTime * 1000).toLong())
                    }
                }
                if (settings.autoSkipOPED && (new.skipType == "op" || new.skipType == "ed") && !skippedTimeStamps.contains(new)) {
                    exoPlayer.seekTo((new.interval.endTime * 1000).toLong())
                    skippedTimeStamps.add(new)
                }
                new.skipType.getType()
            } else {
                skipTimeButton.visibility = View.GONE
                if (settings.skipTime > 0) exoSkip.visibility = View.VISIBLE
                ""
            }
        }
        handler.postDelayed({
            updateTimeStamp()
        }, 500)
    }

    override fun onTracksChanged(tracks: Tracks) {
        if (tracks.groups.size <= 2) exoQuality.visibility = View.GONE
        else {
            exoQuality.visibility = View.VISIBLE
            exoQuality.setOnClickListener {
                initPopupQuality().show()
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            -> {
                toast("Source Exception : ${error.message}")
                isPlayerPlaying = true
                sourceClick()
            }
            else
            -> toast("Player Error ${error.errorCode} (${error.errorCodeName}) : ${error.message}")
        }
    }

    private var isBuffering = true
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == ExoPlayer.STATE_READY) {
            exoPlayer.play()
            if (episodeLength == 0f) {
                episodeLength = exoPlayer.duration.toFloat()
            }
        }
        isBuffering = playbackState == Player.STATE_BUFFERING
        if (playbackState == Player.STATE_ENDED && settings.autoPlay) {
            if (interacted) exoNext.performClick()
            else toast("Autoplay cancelled, no Interaction for more than 1 Hour.")
        }
        super.onPlaybackStateChanged(playbackState)
    }

    private fun updateAniProgress() {
        if (exoPlayer.currentPosition / episodeLength > settings.watchPercentage && Anilist.userid != null)
            if (loadData<Boolean>("${media.id}_save_progress") != false && if (media.isAdult) settings.updateForH else true) {
                media.anime!!.selectedEpisode?.apply {
                    updateAnilistProgress(media, this)
                }
            }
    }

    private fun nextEpisode(toast: Boolean = true, runnable: ((Int) -> Unit)) {
        var isFiller = true
        var i = 1
        while (isFiller) {
            if (episodeArr.size > currentEpisodeIndex + i) {
                isFiller = if (settings.autoSkipFiller) episodes[episodeArr[currentEpisodeIndex + i]]?.filler ?: false else false
                if (!isFiller) runnable.invoke(i)
                i++
            } else {
                if (toast)
                    toast("No next Episode Found!")
                isFiller = false
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        finishAndRemoveTask()
        startActivity(intent)
    }

    override fun onDestroy() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        lifecycleScope.launch(Dispatchers.IO) {
            extractor?.onVideoStopped(video)
        }

        if (isInitialized) {
            updateAniProgress()
            releasePlayer()
        }

        super.onDestroy()
        finishAndRemoveTask()
    }

    // QUALITY SELECTOR
    private fun initPopupQuality(): Dialog {

        val trackSelectionDialogBuilder =
            TrackSelectionDialogBuilder(this, "Available Qualities", exoPlayer, TRACK_TYPE_VIDEO)
        trackSelectionDialogBuilder.setTheme(R.style.DialogTheme)
        trackSelectionDialogBuilder.setTrackNameProvider {
            if (it.frameRate > 0f) it.height.toString() + "p" else it.height.toString() + "p (fps : N/A)"
        }
        val trackDialog = trackSelectionDialogBuilder.build()
        trackDialog.setOnDismissListener { hideSystemBars() }
        return trackDialog
    }

    //Double Tap Animation
    private fun startDoubleTapped(v: View, text: TextView, event: MotionEvent? = null, forward: Boolean) {
        ObjectAnimator.ofFloat(text, "alpha", 1f, 1f).setDuration(600).start()
        ObjectAnimator.ofFloat(text, "alpha", 0f, 1f).setDuration(150).start()

        (text.compoundDrawables[1] as Animatable).apply {
            if (!isRunning) start()
        }

        if (event != null) {
            playerView.hideController()
            v.circularReveal(event.x.toInt(), event.y.toInt(), !forward, 800)
            ObjectAnimator.ofFloat(v, "alpha", 1f, 1f).setDuration(800).start()
            ObjectAnimator.ofFloat(v, "alpha", 0f, 1f).setDuration(300).start()
        }
    }

    private fun stopDoubleTapped(v: View, text: TextView) {
        handler.post {
            ObjectAnimator.ofFloat(v, "alpha", v.alpha, 0f).setDuration(150).start()
            ObjectAnimator.ofFloat(text, "alpha", 1f, 0f).setDuration(150).start()
        }
    }

    // Cast
    private fun cast() {
        val videoURL = video?.url?.url ?: return
        val shareVideo = Intent(Intent.ACTION_VIEW)
        shareVideo.setDataAndType(Uri.parse(videoURL), "video/*")
        shareVideo.setPackage("com.instantbits.cast.webvideo")
        if (subtitle != null) shareVideo.putExtra("subtitle", subtitle!!.url.url)
        shareVideo.putExtra("title", media.userPreferredName + " : Ep " + episodeTitleArr[currentEpisodeIndex])
        shareVideo.putExtra("poster", episode.thumb?.url ?: media.cover)
        val headers = Bundle()
        video?.url?.headers?.forEach {
            headers.putString(it.key, it.value)
        }
        shareVideo.putExtra("android.media.intent.extra.HTTP_HEADERS", headers)
        shareVideo.putExtra("secure_uri", true)
        try {
            startActivity(shareVideo)
        } catch (ex: ActivityNotFoundException) {
            val intent = Intent(Intent.ACTION_VIEW)
            val uriString = "market://details?id=com.instantbits.cast.webvideo"
            intent.data = Uri.parse(uriString)
            startActivity(intent)
        }
    }

    // Enter PiP Mode
    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.N)
    private fun enterPipMode() {
        if (!pipEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(
                    PictureInPictureParams
                        .Builder()
                        .setAspectRatio(aspectRatio)
                        .build()
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {

                enterPictureInPictureMode()
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun onPiPChanged(isInPictureInPictureMode: Boolean) {
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            orientationListener?.disable()
        } else {
            orientationListener?.enable()
        }
        if (isInitialized) {
            saveData("${media.id}_${episode.number}", exoPlayer.currentPosition, this)
            exoPlayer.play()
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        onPiPChanged(isInPictureInPictureMode)
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        onPiPChanged(isInPictureInPictureMode)
        super.onPictureInPictureUiStateChanged(pipState)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        onPiPChanged(isInPictureInPictureMode)
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    private val keyMap: MutableMap<Int,(() -> Unit)?> = mutableMapOf(
        KEYCODE_DPAD_RIGHT to null,
        KEYCODE_DPAD_LEFT to null,
        KEYCODE_SPACE to { exoPlay.performClick() },
        KEYCODE_N to { exoNext.performClick() },
        KEYCODE_B to { exoPrev.performClick() }
    )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return if (keyMap.containsKey(event.keyCode)) {
            (event.action == ACTION_UP).also {
                if(isInitialized) keyMap[event.keyCode]?.invoke()
            }
        }
        else {
            super.dispatchKeyEvent(event)
        }
    }
}
