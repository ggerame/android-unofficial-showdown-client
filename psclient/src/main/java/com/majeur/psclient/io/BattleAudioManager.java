package com.majeur.psclient.io;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.majeur.psclient.R;
import com.majeur.psclient.model.pokemon.BasePokemon;

import java.io.IOException;

import static com.majeur.psclient.util.ExtensionsKt.toId;

public class BattleAudioManager implements AudioManager.OnAudioFocusChangeListener {

    private final boolean mCompatAudio = Build.VERSION.SDK_INT < Build.VERSION_CODES.O;

    private Context mContext;
    private AudioManager mAudioManager;
    private MediaPlayer mMediaPlayer;
    private AudioFocusRequest mAudioFocusRequest;
    private AudioAttributes mMusicAudioAttrs;

    private boolean mPlaybackDelayed;
    private boolean mPlaybackNowAuthorized;
    private boolean mResumeOnFocusGain;
    private boolean mUserHasPaused;

    private String mRoomId;
    private int mLoopStartMs;
    private int mLoopEndMs;
    private final Handler mLoopHandler = new Handler(Looper.getMainLooper());
    private final Runnable mLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (mMediaPlayer != null && mLoopEndMs > 0) {
                try {
                    if (mMediaPlayer.isPlaying() && mMediaPlayer.getCurrentPosition() >= mLoopEndMs)
                        mMediaPlayer.seekTo(mLoopStartMs);
                } catch (IllegalStateException ignored) {
                }
            }
            mLoopHandler.postDelayed(this, LOOP_CHECK_INTERVAL_MS);
        }
    };

    // Streamed battle themes, mirroring the web client (BattleScene.setBgm, cases 1..15). The track
    // is chosen from the numeric battle id so both players hear the same music. Each MP3 plays once
    // from the start, then the [loopStart, loopEnd] section (in ms) is looped seamlessly.
    private static final String BGM_BASE_URL = "https://play.pokemonshowdown.com/audio/";
    private static final long LOOP_CHECK_INTERVAL_MS = 200;
    private static final String[] BGM_FILES = {
            "dpp-trainer.mp3", "dpp-rival.mp3", "hgss-johto-trainer.mp3", "hgss-kanto-trainer.mp3",
            "bw-trainer.mp3", "bw-rival.mp3", "bw-subway-trainer.mp3", "bw2-kanto-gym-leader.mp3",
            "bw2-rival.mp3", "xy-trainer.mp3", "xy-rival.mp3", "oras-trainer.mp3",
            "oras-rival.mp3", "sm-trainer.mp3", "sm-rival.mp3",
    };
    private static final int[] BGM_LOOP_START = {
            13440, 13888, 23731, 13003, 14629, 19180, 15503, 14626,
            7152, 7802, 7802, 13579, 14303, 8323, 11389,
    };
    private static final int[] BGM_LOOP_END = {
            96959, 66352, 125086, 94656, 110109, 57373, 110984, 58986,
            68708, 82469, 58634, 91548, 69149, 89230, 62158,
    };

    public BattleAudioManager(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mMusicAudioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mPlaybackDelayed) {
                    mPlaybackDelayed = false;
                    mPlaybackNowAuthorized = true;
                    if (mUserHasPaused) {
                        mUserHasPaused = false;
                        resumePlayback();
                    } else {
                        startPlayback();
                    }
                } else if (mResumeOnFocusGain) {
                    mResumeOnFocusGain = false;
                     resumePlayback();
                }

                if (mCompatAudio)
                    mMediaPlayer.setVolume(1f, 1f);
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                // Special behavior here. As long as we are not a media app, there is no way for the user to
                // resume the playback when focus is lost. So we do not stop our playback when focus is lost.
                // But we still manually abandon focus to make sure we will make a new request when starting a
                // new playback.
                mResumeOnFocusGain = false;
                mPlaybackDelayed = false;

                int result = abandonAudioFocus();
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    mPlaybackNowAuthorized = false;

                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                mResumeOnFocusGain = true;
                mPlaybackDelayed = false;

                pausePlayback(false);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mCompatAudio)
                    mMediaPlayer.setVolume(0.15f, 0.15f);
                break;

        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private int requestAudioFocus() {
        if (mCompatAudio) {
            return mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        } else {
            mAudioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(mMusicAudioAttrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this)
                    .build();
            return mAudioManager.requestAudioFocus(mAudioFocusRequest);
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private int abandonAudioFocus() {
        if (mCompatAudio) {
            return mAudioManager.abandonAudioFocus(this);
        } else {
            return mAudioManager.abandonAudioFocusRequest(mAudioFocusRequest);
        }
    }

    public void playPokemonCry(BasePokemon pokemon, final boolean faint) {
        // We do not ask for audio focus here cause it should have been requested by the music part
        // TODO Check for delayed focus
        // Also as long as cries are really short, we do not take care of pausing it if user leaves
        String species = toId(pokemon.getBaseSpecies()) + ("mega".equals(pokemon.getForme()) ? "-mega" : "");
        String url = cryUrl(species);
        MediaPlayer mediaPlayer = newMediaPlayer(url);
        if (mediaPlayer == null) return;
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mediaPlayer) {
                if (faint && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    float speed = 0.65f;
                    mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(speed));
                }
                mediaPlayer.start();
            }
        });
        mediaPlayer.prepareAsync();
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mediaPlayer) {
                mediaPlayer.release();
            }
        });
    }

    private String cryUrl(String species) {
        return "https://play.pokemonshowdown.com/audio/cries/" + species + ".mp3";
    }

    public void playMoveHitSound() {
        // For audio focus, same goes here
        MediaPlayer mediaPlayer = newMediaPlayer(R.raw.hit_normal);
        if (mediaPlayer == null) return;
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mediaPlayer) {
                mediaPlayer.start();
            }
        });
        mediaPlayer.prepareAsync();
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mediaPlayer) {
                mediaPlayer.release();
            }
        });
    }

    public void playBattleMusic(String roomId) {
        if (mPlaybackDelayed)
            return;

        if (isPlayingBattleMusic())
            return;

        mRoomId = roomId;

        int result = requestAudioFocus();
        if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            mPlaybackNowAuthorized = false;
        } else if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mPlaybackNowAuthorized = true;
            if (mUserHasPaused) {
                mUserHasPaused = false;
                resumePlayback();
            } else {
                startPlayback();
            }
        } else if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            mPlaybackDelayed = true;
            mPlaybackNowAuthorized = false;
        }
    }

    public void pauseBattleMusic() {
        if (!isPlayingBattleMusic())
            return;

        pausePlayback(true);
        mUserHasPaused = true;
    }

    public void stopBattleMusic() {
        if (mMediaPlayer == null)
            return;

        stopPlayback();
    }

    public boolean isPlayingBattleMusic() {
        return mMediaPlayer != null && mMediaPlayer.isPlaying();
    }

    private void startPlayback() {
        int index = bgmIndexFor(mRoomId);
        mLoopStartMs = BGM_LOOP_START[index];
        mLoopEndMs = BGM_LOOP_END[index];

        final MediaPlayer mediaPlayer = newMediaPlayer(BGM_BASE_URL + BGM_FILES[index]);
        if (mediaPlayer == null) return;
        mediaPlayer.setAudioAttributes(mMusicAudioAttrs);
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.start();
                startLoopWatch();
            }
        });
        // Fallback if a track's true end is reached before the section loop kicks in.
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                try {
                    mp.seekTo(mLoopStartMs);
                    mp.start();
                } catch (IllegalStateException ignored) {
                }
            }
        });
        // Streaming can fail (network); bail out gracefully instead of crashing or looping a dead player.
        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                stopLoopWatch();
                return true;
            }
        });
        mMediaPlayer = mediaPlayer;
        mediaPlayer.prepareAsync();
    }

    private void startLoopWatch() {
        mLoopHandler.removeCallbacks(mLoopRunnable);
        mLoopHandler.postDelayed(mLoopRunnable, LOOP_CHECK_INTERVAL_MS);
    }

    private void stopLoopWatch() {
        mLoopHandler.removeCallbacks(mLoopRunnable);
    }

    private int bgmIndexFor(String roomId) {
        long n = -1;
        if (roomId != null) {
            int dash = roomId.lastIndexOf('-');
            String tail = (dash >= 0 ? roomId.substring(dash + 1) : roomId).trim();
            try {
                n = Long.parseLong(tail);
            } catch (NumberFormatException e) {
                n = -1;
            }
        }
        if (n < 0) n = (long) (Math.random() * 1000000);
        int index = (int) (n % BGM_FILES.length);
        if (index < 0) index += BGM_FILES.length;
        return index;
    }

    private void pausePlayback(boolean abandonFocus) {
        if (mPlaybackNowAuthorized) {
            stopLoopWatch();
            if (mMediaPlayer != null && mMediaPlayer.isPlaying())
                mMediaPlayer.pause();

            if (abandonFocus) {
                int result = abandonAudioFocus();
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    mPlaybackNowAuthorized = false;
            }
        }
    }

    private void resumePlayback() {
        if (mPlaybackNowAuthorized) {
            if (mMediaPlayer != null) {
                mMediaPlayer.start();
                startLoopWatch();
            }
        }
    }

    private void stopPlayback() {
        stopLoopWatch();
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying())
                    mMediaPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            mMediaPlayer.release();
            mMediaPlayer = null;
        }

        int result = abandonAudioFocus();
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            mPlaybackNowAuthorized = false;
    }

    private MediaPlayer newMediaPlayer(int resId) {
        try {
            AssetFileDescriptor assetFileDescriptor = mContext.getResources().openRawResourceFd(resId);
            if (assetFileDescriptor == null) return null;

            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(assetFileDescriptor.getFileDescriptor(),
                    assetFileDescriptor.getStartOffset(), assetFileDescriptor.getLength());
            assetFileDescriptor.close();
            return mediaPlayer;
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            e.printStackTrace();
            return null;
        }
    }

    private MediaPlayer newMediaPlayer(String path) {
        try {
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(path);
            return mediaPlayer;
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            e.printStackTrace();
            return null;
        }
    }
}
