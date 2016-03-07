/**
 * Copyright 2015 Donald Oakes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.oakesville.mythling.vlc;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.IVLCVout.Callback;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import com.oakesville.mythling.BuildConfig;
import com.oakesville.mythling.app.AppSettings;
import com.oakesville.mythling.media.PlaybackOptions;
import com.oakesville.mythling.util.HttpHelper.AuthType;
import com.oakesville.mythling.util.MediaStreamProxy;
import com.oakesville.mythling.util.MediaStreamProxy.ProxyInfo;

import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceView;

public class VlcMediaPlayer extends MediaPlayer implements com.oakesville.mythling.media.MediaPlayer {

    private static final String TAG = VlcMediaPlayer.class.getSimpleName();

    private int itemLength; // seconds
    public int getItemLength() {
        return itemLength;
    }

    public boolean isItemSeekable() {
        return itemLength > 0 && proxy == null; // proxied seek awaits issue #65
    }

    private boolean durationMismatch;
    public boolean isDurationMismatch() { return durationMismatch; }

    private boolean isHls;

    /**
     * Proxy is needed for Digest and Basic auth since libVLC doesn't support.
     */
    private MediaStreamProxy proxy;
    public boolean isProxying() {
        return proxy != null;
    }

    public int inferItemLength() {
        float p = getPosition();
        if (p > 0)
            return (int)(getTime() / (p * 1000));
        else
            return 0;
    }

    public VlcMediaPlayer(SurfaceView videoView, SurfaceView subtitlesView, List<String> libVlcOptions) {
        super(createLibVlc(libVlcOptions));
        libvlc.setOnHardwareAccelerationError(hardwareAccelerationErrorHandler);

        setMaxPlayRate(64); // TODO pref
        setEventListener(vlcEventListener);

        // video output
        final IVLCVout vout = getVLCVout();
        vout.setVideoView(videoView);
        if (subtitlesView != null)
            vout.setSubtitlesView(subtitlesView);
        vout.addCallback(nativeCallback);
        vout.attachViews();
    }

    public void playMedia(Uri mediaUri, int metaLength, AuthType authType, List<String> mediaOptions) throws IOException {
        itemLength = metaLength;
        Log.d(TAG, "Video designated length: " + itemLength);
        isHls = PlaybackOptions.isHls(mediaUri);
        ProxyInfo proxyInfo = MediaStreamProxy.needsAuthProxy(mediaUri);
        Media media;
        if (proxyInfo == null) {
            media = new Media(libvlc, mediaUri);
        }
        else {
            // libvlc needs proxying to support authentication
            proxy = new MediaStreamProxy(proxyInfo, authType);
            proxy.init();
            proxy.start();
            String playUrl = "http://" + proxy.getLocalhost().getHostAddress() + ":" + proxy.getPort() + mediaUri.getPath();
            if (mediaUri.getQuery() != null)
                playUrl += "?" + mediaUri.getQuery();
            Log.i(TAG, "Media proxy URL: " + playUrl);
            media = new Media(libvlc, Uri.parse(playUrl));
        }

        if (mediaOptions == null || mediaOptions.isEmpty()) {
            media.setHWDecoderEnabled(true, false);
            media.addOption(":network-caching=2500");
        }
        else {
            for (String mediaOption : mediaOptions) {
                if (mediaOption.startsWith(AppSettings.SEEK_CORRECTION_TOLERANCE + "="))
                    seekCorrectionTolerance = 1000 * Integer.parseInt(mediaOption.substring(AppSettings.SEEK_CORRECTION_TOLERANCE.length() + 1));
                else
                    media.addOption(mediaOption);
            }
        }
        setMedia(media);
        play();
    }

    public void playMedia(FileDescriptor fd, int metaLength, List<String> mediaOptions) {
        Media media = new Media(libvlc, fd);
        itemLength = metaLength;
        Log.d(TAG, "Video designated length: " + itemLength);
        isHls = false;
        if (mediaOptions == null) {
            media.setHWDecoderEnabled(true, false);
        }
        else {
            for (String mediaOption : mediaOptions)
                media.addOption(mediaOption);
        }
        setMedia(media);
        play();
    }

    public void doRelease() {
        if (proxy != null)
            proxy.stop();
        if (libvlc == null)
            return;
        stop();
        final IVLCVout vout = getVLCVout();
        vout.removeCallback(nativeCallback);
        vout.detachViews();
        libvlc.release();
        libvlc = null;
        super.release();
    }

    private static LibVLC libvlc;
    private static LibVLC createLibVlc(List<String> mediaOptions) {
        // libvlc
        ArrayList<String> options = new ArrayList<String>();
        if (mediaOptions != null)
            options.addAll(mediaOptions);
        if (BuildConfig.DEBUG && !options.contains("-vvv"))
            options.add("-vvv");
        libvlc = new LibVLC(options);

        return libvlc;
    }

    public String getVersion() {
        return libvlc.version();
    }

    /**
     * Set current position.
     * Note: skip() is more accurate for time-based positioning.
     */
    @Override
    public void setPosition(float pos) {
        if (isItemSeekable()) {
            if (pos < 0)
                pos = 0;
            else if (pos > 1)
                pos = 1;
            super.setPosition(pos);
        }
    }

    /**
     * Get current position.  Zero if unknown.
     */
    public int getSeconds() {
        long time = getTime();
        return time == -1 ? 0 : (int)(time/1000);
    }

    public void setSeconds(int secs) {
        setPosition((float)secs/itemLength);
    }

    public void skip(int delta) {
        if (seekCorrectionTolerance > 0 && getLength() <= 0) {
            if (!isTargeting()) {
                long newTarget = getTime() + delta * 1000;
                if (newTarget <= 0)
                    setPosition(0);
                else if (newTarget >= (itemLength*1000))
                    setPosition(1);
                else
                    target(newTarget);
            }
        }
        else {
            doSkip(delta);
        }
    }

    /**
     * Seeks with retry when tolerance is exceeded.
     */
    private void target(long t) {
        target = t;
        long d = target - getTime();
        float newPos = getPosition() + (float)d/(itemLength*1000);
        setPosition(newPos);
        audioTrack = getAudioTrack();
        targetTimeoutHandler.removeCallbacks(targetTimeoutAction);
        targetTimeoutHandler.postDelayed(targetTimeoutAction, targetTimeout);
        setAudioTrack(-1); // mute until target reached
    }

    private void clearTarget() {
        target = -1;
        if (!isReleased())
            setAudioTrack(audioTrack);
    }

    private Handler targetTimeoutHandler = new Handler();
    private Runnable targetTimeoutAction = new Runnable() {
        public void run() {
            clearTarget();
            Log.d(TAG, "Seek target timed out after " + targetTimeout + " ms");
        }
    };

    private long target; // ms
    public boolean isTargeting() { return target > 0; }
    private int seekCorrectionTolerance; // ms
    private int targetTimeout = 5000; // ms
    private int audioTrack;

    /**
     * Seek forward or backward delta seconds.
     * @return past end
     */
    public float doSkip(int delta) {
        if (isItemSeekable()) {
            // libvlc setTime() never works, so use setPosition()
            float curPos = getPosition();
            float newPos = curPos + (float)delta/(float)itemLength;
            if (newPos < 0) {
                setPosition(0);
                return 0;
            }
            else if (newPos > 1) {
                setPosition(1);
                return 1;
            }
            else {
                setPosition(newPos);
                return newPos;
            }
        }
        return -1;
    }

    private int maxPlayRate = 1;
    /**
     * Max multiplier for fast-forward and rewind.
     */
    public int getMaxPlayRate() {
        if (isItemSeekable())
            return maxPlayRate;
        else
            return 1;
    }
    public void setMaxPlayRate(int maxRate) {
        if (maxRate <= 0)
            this.maxPlayRate = 1;
        else
            this.maxPlayRate = maxRate;
    }

    private int playRate = 1;
    public int getPlayRate() { return playRate; }

    @Override
    public void play() {
        if (playRate != 1) {
            playRate = 1;
            setRate(1f);
        }
        if (!isPlaying())
            super.play();
    }


    @Override
    public void pause() {
        playRate = 0;
        if (isPlaying())
            super.pause();
    }

    // TODO: fast forward and rewind handling is duplicated in AndroidMediaPlayer

    /**
     * Step up the fast-forward rate by a factor of two
     * (resets playRate = +2 if maxPlayRate would be exceeded).
     * @return the new playRate
     */
    public int stepUpFastForward() {
        if (isItemSeekable()) {
            if (isPlaying())
                super.pause(); // avoid setting playRate = 0 in this.pause()

            int newPlayRate = 2;
            if (playRate > 1) {
                newPlayRate = playRate * 2;
                if (newPlayRate > maxPlayRate)
                    newPlayRate = 2;
            }

            if (newPlayRate > 1 && playRate <= 1) {
                if (shiftListener != null)
                    shiftListener.onShift(0);
                fastForwardHandler.postDelayed(fastForwardAction, 100);
            }

            playRate = newPlayRate;

        }

        return playRate;
    }

    private Handler fastForwardHandler = new Handler();
    private Runnable fastForwardAction = new Runnable() {
        public void run() {
            if (!isReleased() && playRate > 1) {
                doSkip(playRate);
                if (shiftListener != null)
                    shiftListener.onShift(playRate);
                fastForwardHandler.postDelayed(this, 1000);
            }
        }
    };

    /**
     * Step up the rewind rate by a factor of two
     * (resets playRate = -2 if maxPlayRate would be exceeded).
     * @return the new playRate
     */
    public int stepUpRewind() {
        if (isItemSeekable()) {
            if (isPlaying())
                super.pause(); // avoid setting playRate = 0 in this.pause()

            int newPlayRate = -2;

            if (playRate < 0) {
                newPlayRate = playRate * 2;
                if (-newPlayRate > maxPlayRate)
                    newPlayRate = -2;
            }

            if (newPlayRate < 0 && playRate >= 0) {
                if (shiftListener != null)
                    shiftListener.onShift(0);
                rewindHandler.postDelayed(rewindAction, 100);
            }

            playRate = newPlayRate;
        }

        return playRate;
    }

    private Handler rewindHandler = new Handler();
    private Runnable rewindAction = new Runnable() {
        public void run() {
            if (!isReleased() && playRate < 0) {
                float begin = doSkip(playRate);
                if (begin == 0) {
                    play();
                }
                else {
                    if (shiftListener != null)
                        shiftListener.onShift(playRate);
                    rewindHandler.postDelayed(this, 1000);
                }
            }
        }
    };

    private MediaPlayerEventListener eventListener;
    public void setMediaPlayerEventListener(MediaPlayerEventListener listener) {
        this.eventListener = listener;
    }

    private MediaPlayer.EventListener vlcEventListener = new MediaPlayer.EventListener() {
        private int samples = 0;
        private int minSamples = 10;
        private int maxSamples = 30;
        private long length;  // length reported by libvlc

        @Override
        public void onEvent(MediaPlayer.Event event) {
            if (eventListener != null) {
                switch(event.type) {
                    case MediaPlayer.Event.Opening:
                        length = 0;
                        break;
                    case MediaPlayer.Event.Playing:
                        eventListener.onEvent(new MediaPlayerEvent(MediaPlayerEventType.playing));
                        break;
                    case MediaPlayer.Event.Paused:
                        eventListener.onEvent(new MediaPlayerEvent(MediaPlayerEventType.paused));
                        break;
                    case MediaPlayer.Event.Stopped:
                        eventListener.onEvent(new MediaPlayerEvent(MediaPlayerEventType.stopped));
                        break;
                    case MediaPlayer.Event.EndReached:
                        eventListener.onEvent(new MediaPlayerEvent(MediaPlayerEventType.end));
                        if (proxy != null)
                            proxy.stop();
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        eventListener.onEvent(new MediaPlayerEvent(MediaPlayerEventType.error));
                        break;
                    case MediaPlayer.Event.TimeChanged:
                        if (length <= 0 && samples < maxSamples) {
                            length = getLength(); // reported length
                            if (length > 0) {
                                if (isHls) {
                                    if (itemLength > 0) {
                                        // duration inaccuracy based on meta length
                                        // (don't trust HLS reported length)
                                        int itemLengthMs = itemLength * 1000;
                                        long lengthOffset = itemLengthMs - length;
                                        if (Math.abs((float)lengthOffset/itemLengthMs) > 0.01)
                                            durationMismatch = true;
                                    }
                                }
                                else {
                                    itemLength = (int)(length/1000);
                                    Log.d(TAG, "Video length determined: " + itemLength);
                                }
                            }
                        }

                        if (samples > minSamples) {
                            if (length <= 0) // not known to vlc (usually true for streamed files)
                                durationMismatch = true;
                            // infer length if no meta
                            if (itemLength == 0) {
                                length = inferItemLength();
                                if (length != itemLength) {
                                    Log.d(TAG, "Estimated video length: " + length);
                                    itemLength = (int)length;
                                }
                            }
                        }

                        samples++;
                        eventListener.onEvent(new MediaPlayerEvent(MediaPlayerEventType.time));
                        break;
                    case MediaPlayer.Event.PositionChanged:
                        if (target > 0) {
                            long d = target - getTime();
                            Log.d(TAG, "Seek delta ms: " + d);
                            if (Math.abs(d) > seekCorrectionTolerance) {
                                float newPos = getPosition() + (float)d/(itemLength*1000);
                                Log.d(TAG, "Correcting position: " + newPos);
                                setPosition(newPos);
                            }
                            else {
                                clearTarget();
                                eventListener.onEvent(new MediaPlayerEvent(MediaPlayerEventType.seek));
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    };

    private MediaPlayerShiftListener shiftListener;
    public void setMediaPlayerShiftListener(MediaPlayerShiftListener listener) {
        this.shiftListener = listener;
    }

    private LibVLC.HardwareAccelerationError hardwareAccelerationErrorHandler = new LibVLC.HardwareAccelerationError() {
        public void eventHardwareAccelerationError() {
            if (eventListener != null){
                MediaPlayerEvent event = new MediaPlayerEvent(MediaPlayerEventType.error);
                event.message = LibVLC.HardwareAccelerationError.class.getName();
                eventListener.onEvent(event);
            }
        }
    };

    private MediaPlayerLayoutChangeListener layoutChangeListener;
    public void setLayoutChangeListener(MediaPlayerLayoutChangeListener listener) {
        this.layoutChangeListener = listener;
    }

    private Callback nativeCallback = new Callback() {
        public void onNewLayout(IVLCVout vout, int width, int height,
                int visibleWidth, int visibleHeight, int aspectNumerator, int aspectDenominator) {
            if (layoutChangeListener != null)
                layoutChangeListener.onLayoutChange(width, height, aspectNumerator, aspectDenominator);
        }

        public void onSurfacesCreated(IVLCVout vout) {
        }

        public void onSurfacesDestroyed(IVLCVout vout) {
        }
    };
}
