package com.majeur.psclient.io

import android.annotation.TargetApi
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.majeur.psclient.R
import com.majeur.psclient.model.pokemon.BasePokemon
import com.majeur.psclient.util.toId
import timber.log.Timber

/**
 * Plays battle audio: the looping battle theme, Pokémon cries and the move-hit sound.
 *
 * The battle theme mirrors the web client: it streams the same per-battle track (chosen from the
 * numeric battle id so both players hear the same music) and loops a section of it. If streaming
 * fails (no/poor network) it falls back to the bundled Sun/Moon theme so a battle is never silent.
 */
class BattleAudioManager(private val context: Context) : AudioManager.OnAudioFocusChangeListener {

    private val compatAudio = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val musicAudioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var playbackDelayed = false
    private var playbackNowAuthorized = false
    private var resumeOnFocusGain = false
    private var userHasPaused = false

    private var roomId: String? = null
    private var loopStartMs = 0
    private var loopEndMs = 0
    private val loopHandler = Handler(Looper.getMainLooper())
    private val loopRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && loopEndMs > 0) {
                try {
                    if (mp.isPlaying && mp.currentPosition >= loopEndMs) mp.seekTo(loopStartMs)
                } catch (ignored: IllegalStateException) {
                }
            }
            loopHandler.postDelayed(this, LOOP_CHECK_INTERVAL_MS)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (playbackDelayed) {
                    playbackDelayed = false
                    playbackNowAuthorized = true
                    if (userHasPaused) {
                        userHasPaused = false
                        resumePlayback()
                    } else {
                        startPlayback()
                    }
                } else if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resumePlayback()
                }
                if (compatAudio) mediaPlayer?.setVolume(1f, 1f)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // As long as we are not a media app, there is no way for the user to resume playback
                // once focus is lost, so we don't stop it; we only abandon focus so that the next
                // playback makes a fresh request.
                resumeOnFocusGain = false
                playbackDelayed = false
                if (abandonAudioFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    playbackNowAuthorized = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = true
                playbackDelayed = false
                pausePlayback(false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (compatAudio) mediaPlayer?.setVolume(0.15f, 0.15f)
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun requestAudioFocus(): Int {
        return if (compatAudio) {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        } else {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(musicAudioAttrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this)
                    .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun abandonAudioFocus(): Int {
        return if (compatAudio) {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        } else {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                    ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun playPokemonCry(pokemon: BasePokemon?, faint: Boolean) {
        // Focus should already have been requested by the music part; cries are short so we don't
        // bother pausing them when the user leaves.
        if (pokemon == null) return
        val species = pokemon.baseSpecies.toId() + (if ("mega" == pokemon.forme) "-mega" else "")
        val mediaPlayer = newMediaPlayer(cryUrl(species)) ?: return
        mediaPlayer.setOnPreparedListener { mp ->
            if (faint && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mp.playbackParams = mp.playbackParams.setSpeed(0.65f)
            }
            mp.start()
        }
        mediaPlayer.setOnCompletionListener { it.release() }
        mediaPlayer.prepareAsync()
    }

    private fun cryUrl(species: String) = "https://play.pokemonshowdown.com/audio/cries/$species.mp3"

    fun playMoveHitSound() {
        val mediaPlayer = newMediaPlayer(R.raw.hit_normal) ?: return
        mediaPlayer.setOnPreparedListener { it.start() }
        mediaPlayer.setOnCompletionListener { it.release() }
        mediaPlayer.prepareAsync()
    }

    fun playBattleMusic(roomId: String?) {
        if (playbackDelayed) return
        if (isPlayingBattleMusic) return

        this.roomId = roomId

        when (requestAudioFocus()) {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> playbackNowAuthorized = false
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                playbackNowAuthorized = true
                if (userHasPaused) {
                    userHasPaused = false
                    resumePlayback()
                } else {
                    startPlayback()
                }
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                playbackDelayed = true
                playbackNowAuthorized = false
            }
        }
    }

    fun pauseBattleMusic() {
        if (!isPlayingBattleMusic) return
        pausePlayback(true)
        userHasPaused = true
    }

    fun stopBattleMusic() {
        if (mediaPlayer == null) return
        stopPlayback()
    }

    val isPlayingBattleMusic: Boolean
        get() = mediaPlayer?.isPlaying == true

    private fun startPlayback() {
        val index = bgmIndexFor(roomId)
        loopStartMs = BGM_LOOP_START[index]
        loopEndMs = BGM_LOOP_END[index]

        val mp = newMediaPlayer(BGM_BASE_URL + BGM_FILES[index])
        if (mp == null) {
            startFallbackPlayback()
            return
        }
        mp.setAudioAttributes(musicAudioAttrs)
        mp.setOnPreparedListener {
            it.start()
            startLoopWatch()
        }
        // If a track's true end is reached before the section loop kicks in, jump back to the loop.
        mp.setOnCompletionListener {
            try {
                it.seekTo(loopStartMs)
                it.start()
            } catch (ignored: IllegalStateException) {
            }
        }
        // Streaming can fail (no/poor network); fall back to the bundled theme instead of silence.
        mp.setOnErrorListener { failed, what, extra ->
            Timber.w("Battle music streaming failed (what=%d, extra=%d); using bundled fallback", what, extra)
            val wasCurrent = failed === mediaPlayer
            stopLoopWatch()
            try {
                failed.release()
            } catch (ignored: Exception) {
            }
            if (wasCurrent) {
                mediaPlayer = null
                startFallbackPlayback()
            }
            true
        }
        mediaPlayer = mp
        mp.prepareAsync()
    }

    /**
     * Plays the bundled Sun/Moon theme (a short intro then a seamlessly looped body) when the
     * streamed track can't be reached. Uses [MediaPlayer.setLooping] rather than the section-loop
     * watch, which is disabled here by clearing [loopEndMs].
     */
    private fun startFallbackPlayback() {
        stopLoopWatch()
        loopEndMs = 0
        val intro = newMediaPlayer(R.raw.battle_sm_intro)
        val loop = newMediaPlayer(R.raw.battle_sm_loop)
        if (intro == null || loop == null) {
            intro?.release()
            loop?.release()
            return
        }
        var introComplete = false
        intro.setAudioAttributes(musicAudioAttrs)
        intro.setOnPreparedListener { it.start() }
        intro.setOnCompletionListener {
            introComplete = true
            mediaPlayer = loop
            it.release()
        }
        mediaPlayer = intro
        intro.prepareAsync()

        loop.setAudioAttributes(musicAudioAttrs)
        loop.setOnPreparedListener {
            it.isLooping = true
            if (introComplete) it.start() else intro.setNextMediaPlayer(it)
        }
        loop.prepareAsync()
    }

    private fun startLoopWatch() {
        loopHandler.removeCallbacks(loopRunnable)
        loopHandler.postDelayed(loopRunnable, LOOP_CHECK_INTERVAL_MS)
    }

    private fun stopLoopWatch() {
        loopHandler.removeCallbacks(loopRunnable)
    }

    private fun bgmIndexFor(roomId: String?): Int {
        var n = roomId?.substringAfterLast('-')?.trim()?.toLongOrNull() ?: -1L
        if (n < 0) n = (Math.random() * 1000000).toLong()
        return (n % BGM_FILES.size).toInt()
    }

    private fun pausePlayback(abandonFocus: Boolean) {
        if (!playbackNowAuthorized) return
        stopLoopWatch()
        mediaPlayer?.let { if (it.isPlaying) it.pause() }
        if (abandonFocus && abandonAudioFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            playbackNowAuthorized = false
    }

    private fun resumePlayback() {
        if (!playbackNowAuthorized) return
        mediaPlayer?.let {
            it.start()
            if (loopEndMs > 0) startLoopWatch()
        }
    }

    private fun stopPlayback() {
        stopLoopWatch()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (ignored: IllegalStateException) {
            }
            it.release()
        }
        mediaPlayer = null
        if (abandonAudioFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            playbackNowAuthorized = false
    }

    private fun newMediaPlayer(resId: Int): MediaPlayer? {
        return try {
            val afd = context.resources.openRawResourceFd(resId) ?: return null
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mediaPlayer
        } catch (e: Exception) {
            Timber.w(e, "Failed to create MediaPlayer for res %d", resId)
            null
        }
    }

    private fun newMediaPlayer(path: String): MediaPlayer? {
        return try {
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(path)
            mediaPlayer
        } catch (e: Exception) {
            Timber.w(e, "Failed to create MediaPlayer for %s", path)
            null
        }
    }

    companion object {
        // Streamed battle themes, mirroring the web client (BattleScene.setBgm, cases 1..15). Each
        // MP3 plays once from the start, then the [loopStart, loopEnd] section (in ms) is looped.
        private const val BGM_BASE_URL = "https://play.pokemonshowdown.com/audio/"
        private const val LOOP_CHECK_INTERVAL_MS = 200L
        private val BGM_FILES = arrayOf(
                "dpp-trainer.mp3", "dpp-rival.mp3", "hgss-johto-trainer.mp3", "hgss-kanto-trainer.mp3",
                "bw-trainer.mp3", "bw-rival.mp3", "bw-subway-trainer.mp3", "bw2-kanto-gym-leader.mp3",
                "bw2-rival.mp3", "xy-trainer.mp3", "xy-rival.mp3", "oras-trainer.mp3",
                "oras-rival.mp3", "sm-trainer.mp3", "sm-rival.mp3",
        )
        private val BGM_LOOP_START = intArrayOf(
                13440, 13888, 23731, 13003, 14629, 19180, 15503, 14626,
                7152, 7802, 7802, 13579, 14303, 8323, 11389,
        )
        private val BGM_LOOP_END = intArrayOf(
                96959, 66352, 125086, 94656, 110109, 57373, 110984, 58986,
                68708, 82469, 58634, 91548, 69149, 89230, 62158,
        )
    }
}
