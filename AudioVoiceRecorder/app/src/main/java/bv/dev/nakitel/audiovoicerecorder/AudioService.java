package bv.dev.nakitel.audiovoicerecorder;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.NotificationCompat;
import android.text.TextUtils;
import android.util.JsonWriter;
import android.util.Log;
import android.util.Xml;
import android.widget.Toast;

import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/*
    should also declare in manifest service with <intent-filter>
    with <action android:name="android.media.browse.MediaBrowserService"/>
 */
public class AudioService extends MediaBrowserServiceCompat {
    public static final String SOURCE_NONE = "SOURCE_NONE";
    public static final String SOURCE_MIC = "SOURCE_MIC";
    public static final String SOURCE_AUDIO = "SOURCE_AUDIO";
    public static final String VOICE_REC_MIME = "audio/mp4";
    public static final String EXTRA_KEY_AMPL = "METADATA_KEY_AMPL";
    public static final String EXTRA_KEY_AR_FREQ = "EXTRA_KEY_AR_FREQ";
    public static final String EXTRA_KEY_AR_AMPL = "EXTRA_KEY_AR_AMPL";
    public static final String EXTRA_KEY_AR_MAGN = "EXTRA_KEY_AR_MAGN";
    public static final String EXTRA_KEY_AR_DENS = "EXTRA_KEY_AR_DENS";

    private static final String EXT_STORAGE_DIR_NAME = "Voice";
    private static final String VOICE_REC_PREF = "rec-";
    private static final String VOICE_REC_EXT = ".m4a";
    private static final String VSL_JSON_FILE_NAME = "Visualization.json";
    private static final String VSL_XML_FILE_NAME = "Visualization.xml";
    private static final String VSL_PROP_WAVEFORM_NAME = "waveform";
    private static final String VSL_PROP_FFT_NAME = "fft";
    private static final String VSL_PROP_ENTRY_NAME = "entry";
    private static final String LOG_TAG = "bv_log";
    private static final String MEDIA_ROOT_ID = "MEDIA_ROOT_ID";
    private static final int SERVICE_ID = 1;
    private static final int IC_NOTIF_PLAY= R.drawable.ic_notif_play;
    private static final int IC_NOTIF_RECORD= R.drawable.ic_notif_record;
    private static final int IC_NOTIF_PAUSE =  R.drawable.ic_notif_pause;
    private static final int IC_NOTIF_STOP= R.drawable.ic_notif_stop;
    private static final int VISUALIZER_CAPTURE_SIZE = 512; // default is max 1024, min 128 (2^n)
    //private static final int VISUALIZER_SAMPLE_RATE = 4000; old
    private static final int VISUALIZER_SAMPLE_RATE = 8000;
    private static final int AMPLITUDE_CHK_RATE = 500;
    //private static final int AMPLITUDE_CHK_RATE = 200; // test

    public static final File audioFilesPath = new File(Environment.getExternalStorageDirectory(), EXT_STORAGE_DIR_NAME);

    private enum ServiceState {
        Ready,
        /*PreparePlaying, PrepareRecording,*/ // redundant
        Playing, PausedPlaying, StoppedPlaying,
        Recording, PausedRecording, StoppedRecording
    }

    private ServiceState servState = ServiceState.Ready;

    private MediaSessionCompat mediaSes;
    private MediaControllerCompat.TransportControls transCntrl;
    private PlaybackStateCompat.Builder stateBuilderImplFacility; // use method instead
    private MediaMetadataCompat.Builder metadataBuilder;
    private NotificationCompat.Builder notifBuilder;

    private boolean isRecorderInitialized = false;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private Visualizer vslr;
    // visualizer data
    private ArrayList<Byte> alWaveFormVal = new ArrayList<>();
    private ArrayList<Byte> alFftVal = new ArrayList<>();

    private String recFilePath;
    @SuppressLint("SimpleDateFormat")
    private SimpleDateFormat sDateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

    private FileFilter audioFileFilter = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            return pathname.exists() && pathname.isFile() && pathname.getName().endsWith(VOICE_REC_EXT);
        }
    };

    private SaveVslDataATask saveVslData;
    private HandlerThread hThread = new HandlerThread("AudioServiceHandlerThread");
    private Handler hndlrUI = new Handler(); // to post delayed
    // provides volume/mic control and oth functions
    private AudioManager audioManager;

    private AsyncTask<Void, Double, Void> aTaskAmplitude;

    private final AudioManager.OnAudioFocusChangeListener afchListener = new AudioManager.OnAudioFocusChangeListener() {
        private int curVolume = -1;
        @Override
        public void onAudioFocusChange(int focusChange) {
            Log.d(LOG_TAG, "AudioManager.OnAudioFocusChangeListener.onAudioFocusChange() : "
                    + focusChange + isUiMsg());

            switch(servState) {
                //if recording - do nothing
                case Playing:
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        // should pause and if focus was not returned - stop
                        transCntrl.pause();
                        hndlrUI.postDelayed(stopRunnable, TimeUnit.SECONDS.toMillis(30));
                    } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        // should pause
                        transCntrl.pause();
                    } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        // should pause or decrease volume
                        if (audioManager == null) {
                            Log.w(LOG_TAG, "AudioManager.OnAudioFocusChangeListener.onAudioFocusChange() : audioManager == null");
                        } else {
                            curVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, curVolume / 2, AudioManager.FLAG_SHOW_UI);
                        }
                    }
                    break;
                case PausedPlaying:
                case StoppedPlaying:
                    if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                        // can resume and restore volume
                        if (audioManager == null) {
                            Log.w(LOG_TAG, "AudioManager.OnAudioFocusChangeListener.onAudioFocusChange() : audioManager == null");
                        } else if (curVolume == -1) { // haven't changed yet
                            curVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                        } else {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, curVolume, AudioManager.FLAG_SHOW_UI);
                        }
                        transCntrl.play();
                    }
                    break;
            }
        }
    };

    private final Runnable stopRunnable = new Runnable() {
        @Override
        public void run() {
            transCntrl.stop();
        }
    };

    // for ex., headphones were pulled out
    private IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
    final private BroadcastReceiver bnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(LOG_TAG, "ACTION_AUDIO_BECOMING_NOISY BroadcastReceiver onReceive() : " + intent);
            if(AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                // if recording audio - do nothing
                if(servState == ServiceState.Playing) {
                    //decreaseVolume or pause playback
                    transCntrl.pause();
                    /* unused yet
                    if(audioManager != null) {
                        audioManager.setStreamVolume(..);
                    } else {
                        Log.w(LOG_TAG, "ACTION_AUDIO_BECOMING_NOISY BroadcastReceiver onReceive() : audioManager == null");
                    }
                    */
                }
            }
        }
    };

    private final MediaSessionCompat.Callback mediaSesCallback = new MediaSessionCompat.Callback() {
        @Override
        public void onCommand(String command, Bundle extras, ResultReceiver cb) {
            super.onCommand(command, extras, cb);
            Log.d(LOG_TAG, "MediaSession.Callback.onCommand() : " + command + "; " + extras
                    + "; service state == " + servState);
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            super.onPlayFromMediaId(mediaId, extras);
            Log.d(LOG_TAG, "MediaSession.Callback.onPlayFromMediaId() : " + mediaId + "; " + extras
                    + "; service state == " + servState + isUiMsg());

            switch(mediaId) {
                case SOURCE_NONE:
                    switch (servState) {
                        case Playing:
                        case PausedPlaying:
                            actionStopPlaying();
                            actionSetReady();
                            break;
                        case Recording:
                        case PausedRecording:
                            actionStopRecording();
                            actionSetReady();
                            break;
                        case StoppedPlaying:
                        case StoppedRecording:
                        case Ready:
                        default:
                            actionSetReady();
                            break;
                    }
                    break;
                case SOURCE_AUDIO: // now is redundant, need to review states
                    switch (servState) {
                        case Playing:
                            break;
                        case PausedPlaying:
                        case StoppedPlaying:
                        case StoppedRecording:
                        case Ready:
                        default:
                            actionPlay(null); // should be an error
                            break;
                        case Recording:
                        case PausedRecording:
                            actionStopRecording();
                            actionPlay(null); // should be an error
                            break;
                    }
                    break;
                case SOURCE_MIC:
                    switch (servState) {
                        case Playing:
                        case PausedPlaying:
                            actionStopPlaying();
                            actionRecord();
                            break;
                        case Recording:
                            break;
                        case StoppedPlaying:
                        case PausedRecording:
                        case StoppedRecording:
                        case Ready:
                        default:
                            actionRecord();
                            break;
                    }
                    break;
            }
        }

        @Override
        public void onPlayFromUri(Uri uri, Bundle extras) {
            Log.d(LOG_TAG, "MediaSession.Callback.onPlayFromUri() : " + uri + "; " + extras
                    + "; service state == " + servState + isUiMsg());

            switch (servState) {
                case Playing:
                    actionStopPlaying();
                    actionPlay(uri);
                    break;
                case Ready:
                case PausedPlaying:
                case StoppedPlaying:
                case StoppedRecording:
                    actionPlay(uri);
                    break;
                case Recording:
                case PausedRecording:
                    actionStopRecording();
                    actionPlay(uri);
                    break;
                default:
                    Log.e(LOG_TAG, "MediaSession.Callback.onPlayFromUri() : wrong service state : " + servState);
                    break;
            }
        }

        @Override
        public void onPlay() {
            Log.d(LOG_TAG, "MediaSession.Callback.onPlay() : service state == " + servState);

            switch (servState) {
                case Playing:
                    break;
                case PausedPlaying:
                case StoppedPlaying:
                    actionPlay(null);
                    break;
                case Recording:
                    break;
                case PausedRecording:
                case StoppedRecording:
                    actionRecord();
                    break;
                case Ready:
                default:
                    Log.e(LOG_TAG, "MediaSession.Callback.onPlay() : wrong service state : " + servState);
                    break;
            }
        }

        @Override
        public void onPause() {
            Log.d(LOG_TAG, "MediaSession.Callback.onPause() : service state == " + servState);

            switch (servState) {
                case Playing:
                    actionPausePlaying();
                    break;
                case PausedPlaying:
                case StoppedPlaying:
                    break;
                case Recording:
                    actionPauseRecording();
                    break;
                case PausedRecording:
                case StoppedRecording:
                case Ready:
                default:
                    break;
            }

        }

        @Override
        public void onStop() {
            Log.d(LOG_TAG, "MediaSession.Callback.onStop() : service state == " + servState + isUiMsg());

            switch (servState) {
                case Playing:
                case PausedPlaying:
                    actionStopPlaying();
                    break;
                case StoppedPlaying:
                    break;
                case Recording:
                case PausedRecording:
                    actionStopRecording();
                    break;
                case StoppedRecording:
                case Ready:
                default:
                    break;
            }
        }
    };

    private final Visualizer.OnDataCaptureListener vslrListener = new Visualizer.OnDataCaptureListener() {
        /**
         * see
         * https://developer.android.com/reference/android/media/audiofx/Visualizer.html#getWaveForm(byte[])
         * http://stackoverflow.com/questions/40584680/get-amplitude-from-mediaplayer-using-visualizer?rq=1
         * http://stackoverflow.com/questions/32822314/how-to-compute-decibel-db-of-amplitude-from-media-player
         * http://stackoverflow.com/questions/28215289/how-to-calculate-the-audio-amplitude-in-real-time-android
         */
        @Override
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
            // save to files
            if(alWaveFormVal != null) {
                for(byte b : waveform) {
                    alWaveFormVal.add(b);
                }
            }
            //--------------
            // waveform should be unsigned 8 bit
            int freqMin = 0;
            int freqMax = samplingRate / 2;
            double ampSum = 0;
            // make unsigned
            int[] arUWaveform = new int[waveform.length];
            for(int byteIdx = 0; byteIdx < waveform.length; byteIdx++) {
                arUWaveform[byteIdx] = waveform[byteIdx] & 0x00FF;
            }
            //double[] arAmp = new double[waveform.length / 2]; // useless
            //double[] arAmpDB = new double[waveform.length / 2]; // useless
            double[] arAmpDbSki = new double[waveform.length / 2];
            double[] arFreq = new double[waveform.length / 2];
            for(int freqIdx = 0; freqIdx < waveform.length / 2; freqIdx++) {
                // 8 bit to 16 ;
                int amp = Math.abs(waveform[freqIdx * 2] | (waveform[freqIdx * 2 + 1] << 8));
                //int amp = arUWaveform[freqIdx * 2] | (arUWaveform[freqIdx * 2 + 1] << 8); // wrong?
                // normalize to 1 ? // useless
                //double ampN = amp / 32768.0; // useless
                //ampSum += ampN * ampN; // useless
                ampSum += amp * amp;
                //arAmp[freqIdx] = amp; // useless
                //arAmpDB[freqIdx] = 20 * Math.log10(amp / 32768.0); // useless
                arAmpDbSki[freqIdx] = 20 * Math.log10(amp / 51805.5336 / 0.0002);
                arFreq[freqIdx] = 1.0 * freqIdx * samplingRate / (waveform.length / 2);
            }
            double ampTotal = Math.sqrt(ampSum / (waveform.length / 2));
            double ampDB = 20 * Math.log10(ampTotal / 51805.5336 / 0.0002);

            // send to UI activity
            Bundle bndl = new Bundle(2);
            bndl.putDoubleArray(EXTRA_KEY_AR_FREQ, arFreq);
            bndl.putDoubleArray(EXTRA_KEY_AR_AMPL, arAmpDbSki);
            setPBState(mediaSes.getController().getPlaybackState().getState(), bndl);

            /*
            // log
            Log.v(LOG_TAG, "Visualizer.OnDataCaptureListener.waveform ---------------- ");
            Log.v(LOG_TAG, "freq min : max == " + freqMin + " ; " + freqMax
                    + " ; samplingRate == " + samplingRate
                    + " ; length == " + waveform.length
                    + " ; freq points count == " + (waveform.length / 2)
                    //+ " ; ampTotal == " + ampTotal // useless
                    + " ; ampDB == " + ampDB);
            Log.v(LOG_TAG, "waveform: " + Arrays.toString(waveform));
            Log.v(LOG_TAG, "arUWaveform: " + Arrays.toString(arUWaveform));
            Log.v(LOG_TAG, "arFreq: " + Arrays.toString(arFreq));
            //Log.v(LOG_TAG, "arAmp: " + Arrays.toString(arAmp)); // useless
            //Log.v(LOG_TAG, "arAmpDB: " + Arrays.toString(arAmpDB)); // useless
            Log.v(LOG_TAG, "arAmpDbSki: " + Arrays.toString(arAmpDbSki));
            */
        }

        /**
         * see
         * https://developer.android.com/reference/android/media/audiofx/Visualizer.html#getFft%28byte[]%29
         * http://stackoverflow.com/questions/4720512/android-2-3-visualizer-trouble-understanding-getfft
         * http://stackoverflow.com/questions/6620544/fast-fourier-transform-fft-input-and-output-to-analyse-the-frequency-of-audio
         */
        @Override
        public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
            // for writing a file
            if(alFftVal != null) {
                for(byte b : fft) {
                    alFftVal.add(b);
                }
            }
            //-----------------
            int freqMin = 0;
            int freqMax = samplingRate / 2;
            int captureSize = fft.length; // the n
            int freqPtsCount = captureSize / 2 + 1;
            double[] arFreqMagnitude = new double[freqPtsCount];
            arFreqMagnitude[0] = fft[0] & 0x00FF; // by definition
            arFreqMagnitude[freqPtsCount - 1] = fft[1] & 0x00FF; // by definition
            for(int freqIdx = 1; freqIdx < freqPtsCount - 1; freqIdx++) {
                /*
                id * 2 :: id * 2 + 1
                1 -> 2 :: 3
                2 -> 4 :: 5
                3 -> 6 :: 7
                 */
                arFreqMagnitude[freqIdx] = Math.sqrt(Math.pow(fft[freqIdx * 2], 2)
                        + Math.pow(fft[freqIdx * 2 + 1], 2));
            }
            double[] arFreq = new double[freqPtsCount];
            double[] arSpectralDensity = new double[freqPtsCount];
            for(int freqIdx = 0; freqIdx < arFreqMagnitude.length; freqIdx++) {
                arFreq[freqIdx] = 1.0 * freqIdx * samplingRate / (captureSize / 2);
                arSpectralDensity[freqIdx] = 20 * Math.log10(arFreqMagnitude[freqIdx]);
            }

            // send to UI activity
            Bundle bndl = new Bundle(3);
            bndl.putDoubleArray(EXTRA_KEY_AR_FREQ, arFreq);
            bndl.putDoubleArray(EXTRA_KEY_AR_MAGN, arFreqMagnitude);
            bndl.putDoubleArray(EXTRA_KEY_AR_DENS, arSpectralDensity);
            setPBState(mediaSes.getController().getPlaybackState().getState(), bndl);

            /*
            // log
            Log.v(LOG_TAG, "Visualizer.OnDataCaptureListener.fft ---------------- ");
            Log.v(LOG_TAG, "fft: " + Arrays.toString(fft));
            Log.v(LOG_TAG, "freq min : max == " + freqMin + " ; " + freqMax
                    + " ; samplingRate == " + samplingRate
                    + " ; capture size == " + captureSize + " ; freq points count == " + freqPtsCount);
            Log.v(LOG_TAG, "arFreq: " + Arrays.toString(arFreq));
            Log.v(LOG_TAG, "arFreqMagnitude: " + Arrays.toString(arFreqMagnitude));
            Log.v(LOG_TAG, "arSpectralDensity: " + Arrays.toString(arSpectralDensity));
            */
        }
    };

    private class RecAmplitudeChkAsyncTask extends AsyncTask<Void, Double, Void> {
        @Override
        protected void onProgressUpdate(Double... values) {
            if(values == null || values.length == 0) {
                Log.e(LOG_TAG, "aTaskAmplitude.onProgressUpdate() : wrong args ");
                return;
            }
            //Log.v(LOG_TAG, "aTaskAmplitude.onProgressUpdate() : amplitude == " + Arrays.toString(values));
            Bundle bndl = new Bundle(1);
            bndl.putDouble(EXTRA_KEY_AMPL, values[0]);
            // setPBState(PlaybackStateCompat.STATE_PLAYING, bndl); // think produces state violation
            setPBState(mediaSes.getController().getPlaybackState().getState(), bndl);
        }

        // in release version not always works
        @Override
        protected Void doInBackground(Void... params) {
            while(! isCancelled()) {
                if(recorder != null && isRecorderInitialized) {
                    try {
                        Thread.sleep(AMPLITUDE_CHK_RATE);
                    } catch (InterruptedException ie) {
                        // it is normal
                        //Log.w(LOG_TAG, "aTaskAmplitude.doInBackground() : interrupted ", ie);
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    /* try to get input amplitude.
                        Visualizer's methods return miliVolts and are for api 19 and higher
                        to get better results see http://stackoverflow.com/questions/10655703/what-does-androids-getmaxamplitude-function-for-the-mediarecorder-actually-gi
                     */
                    // integer value (unsigned 16 bit)
                    double amplitude = recorder.getMaxAmplitude();
                    // try to calculate dB using two approaches
                    // very inaccurate
                    //double dbApprox = 20 * Math.log10(amplitude / 0.1);
                    double dbSki = 20 * Math.log10(amplitude / 51805.5336 / 0.0002);
                    publishProgress(dbSki);
                }
            }
            return null;
        }
    }

    public static boolean isPauseRecordingSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(LOG_TAG, "AudioService created" + isUiMsg());

        hThread.start();
        Handler hndlrBG = new Handler(hThread.getLooper()); // background thread handler

        // now is initialized when defined
        //audioFilesPath = new File(Environment.getExternalStorageDirectory(), EXT_STORAGE_DIR_NAME);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        // MediaSession:
        //error without receiver declared in manifest: java.lang.IllegalArgumentException: MediaButtonReceiver component may not be null
        mediaSes = new MediaSessionCompat(getApplicationContext(), LOG_TAG);
        // callbacks for MediaButtons and TransportControls
        mediaSes.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        metadataBuilder = new MediaMetadataCompat.Builder();
        mediaSes.setMetadata(metadataBuilder
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, SOURCE_NONE).build());
        stateBuilderImplFacility = new PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_STOP);
        mediaSes.setPlaybackState(stateBuilderImplFacility.build());
        // callback from media controller
        mediaSes.setCallback(mediaSesCallback, hndlrBG); //Handler to set execution thread
        // token through which to communicate with client
        setSessionToken(mediaSes.getSessionToken());

        transCntrl = mediaSes.getController().getTransportControls();

        actionSetReady();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MediaButtonReceiver.handleIntent(mediaSes, intent);
        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        if(getClass().getPackage().getName().equals(clientPackageName)) {
            return new BrowserRoot(MEDIA_ROOT_ID, null);
        } else {
            Log.i(LOG_TAG, "AudioService onGetRoot() : clientPackageName == " + clientPackageName);
            return new BrowserRoot("", null); // can connect but not browse
        }
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        Log.d(LOG_TAG, "AudioService onLoadChildren()" + isUiMsg());
        // can't browse
        if(TextUtils.isEmpty(parentId)) {
            result.sendResult(null);
            return;
        }
        // can call it and run execution on other thread, then send result
        //result.detach();
        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
        if(MEDIA_ROOT_ID.equals(parentId)) { // root
            if( ! Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                    && ! Environment.MEDIA_MOUNTED_READ_ONLY.equals(Environment.getExternalStorageState())) {

                Log.w(LOG_TAG, "AudioService onLoadChildren() : External Storage not available : "
                        + Environment.getExternalStorageState());
                result.sendResult(mediaItems);
                return;
            }

            if(! audioFilesPath.exists() || ! audioFilesPath.isDirectory()) {
                Log.w(LOG_TAG, "AudioService onLoadChildren() : Audio files dir not available : "
                        + audioFilesPath);
                result.sendResult(mediaItems);
                return;
            }

            File[] files = audioFilesPath.listFiles(audioFileFilter);
            if(files == null || files.length == 0) {
                Log.i(LOG_TAG, "AudioService onLoadChildren() : Audio files not present : "
                        + audioFilesPath);
                result.sendResult(mediaItems);
                return;
            }

            for(File file : files) {
                mediaItems.add(
                        new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                                .setMediaId(SOURCE_AUDIO)
                                .setTitle(getString(R.string.app_name))
                                .setSubtitle(getString(R.string.text_playing))
                                .setDescription(file.getAbsolutePath())
                                .setIconBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.ic_mic))
                                //.setMediaUri(Uri.fromFile(file)) // wrong pattern
                                .setMediaUri(new Uri.Builder().encodedPath(file.getAbsolutePath()).build())
                                .build(),
                                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            }
        }
        /* else { // no submenu supported
            // submenu
            //new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat());
        }*/

        result.sendResult(mediaItems);
    }

    private String isUiMsg() {
        return " / is UI == " + (Looper.myLooper() == Looper.getMainLooper());
    }

    // generate new record name
    private String genNewRecordName() {
        return VOICE_REC_PREF + sDateFormat.format(new Date()) + VOICE_REC_EXT;
    }

    /* redundant
    private void setStatePrepareRecording() {
        servState = ServiceState.PrepareRecording;
    }
    private void setStatePreparePlaying() {
        servState = ServiceState.PreparePlaying;
    }
    */

    /**
     * request focus for audio playback
     * @return true if granted
      */
    private boolean requestFocus() {
        boolean granted = false;
        if(audioManager != null) {
            granted = audioManager.requestAudioFocus(afchListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            Log.println( (granted ? Log.DEBUG : Log.WARN), LOG_TAG,
                    "requestFocus() : audio focus request" + (granted ? "" : " NOT") + " granted");
        } else {
            Log.w(LOG_TAG, "requestFocus() : audioManager == null");
        }
        return granted;
    }

    private void setMetaAndPBState(MediaMetadataCompat meta, int pbState) {
        // first metadata, then pbState
        setMetaAndPBState(meta, pbState, 0, null, null);
    }

    private void setMetaAndPBState(MediaMetadataCompat meta, int pbState, int errCode,
                                   CharSequence errMsg, Bundle extra) {
        // first metadata, then pbState
        mediaSes.setMetadata(meta);
        setPBState(pbState, errCode, errMsg, extra);
        //mediaSes.sendSessionEvent(); // could be used
    }

    private void setPBState(int pbState) {
        setPBState(pbState, 0, null, null);
    }

    private void setPBState(int pbState, Bundle extra) {
        setPBState(pbState, 0, null, extra);
    }

    private void setPBState(int pbState, int errCode, CharSequence errMsg, Bundle extra) {
        try {
            mediaSes.setPlaybackState(stateBuilderImplFacility
                    .setState(pbState, -1, 1)
                    .setErrorMessage(errCode, errMsg)
                    .setExtras(extra)
                    .build());
        } catch (IllegalStateException ise) {
            //java.lang.IllegalStateException: beginBroadcast() called while already in a broadcast
            Log.e(LOG_TAG, "AudioService.setPBState() : ", ise);
        }
    }

    /**
     * Starts audio session : start service, make foreground notification, register receiver, ...
     */
    private void startSession() {
        Log.d(LOG_TAG, "startSession()");
        // start this MediaBrowserService, to make it run when UI will be closed
        startService(new Intent(getApplicationContext(), this.getClass()));
        mediaSes.setActive(true);
        registerReceiver(bnReceiver, intentFilter);

        // foreground notification
        MediaControllerCompat controller = mediaSes.getController();
        MediaMetadataCompat metadata = controller.getMetadata();
        MediaDescriptionCompat description = metadata.getDescription();

        notifBuilder = new NotificationCompat.Builder(getApplicationContext());
        notifBuilder.setContentTitle(description.getTitle())
                .setContentText(description.getSubtitle())
                .setSubText(description.getDescription())
                .setTicker(description.getSubtitle())
                .setLargeIcon(description.getIconBitmap())
                //.setContentIntent(controller.getSessionActivity()) // does not work
                .setContentIntent(PendingIntent.getActivity(getApplicationContext(), 1,
                        new Intent(getApplicationContext(), MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(
                        AudioService.this, PlaybackStateCompat.ACTION_STOP))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSmallIcon(R.drawable.ic_notif_record)
                //.setColor(ContextCompat.getColor(AudioService.this, R.color.colorPrimaryDark)) // looks bad
                .setStyle(new NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSes.getSessionToken())
                        .setShowActionsInCompactView(0).setShowCancelButton(true)
                        .setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(
                                AudioService.this, PlaybackStateCompat.ACTION_STOP)));

        if( ! SOURCE_MIC.equals(description.getMediaId()) || isPauseRecordingSupported()) {
            notifBuilder.addAction(new NotificationCompat.Action(IC_NOTIF_PAUSE,
                    getString(R.string.text_pause),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(AudioService.this,
                            PlaybackStateCompat.ACTION_PAUSE)));
        }
        notifBuilder.addAction(new NotificationCompat.Action(IC_NOTIF_STOP,
                getString(R.string.text_stop),
                MediaButtonReceiver.buildMediaButtonPendingIntent(AudioService.this,
                        PlaybackStateCompat.ACTION_STOP)));
        startForeground(SERVICE_ID, notifBuilder.build());
    }

    private void pauseSession() {
        Log.d(LOG_TAG, "pauseSession()");
        try {
            unregisterReceiver(bnReceiver);
        } catch (IllegalArgumentException iae) { // receiver not registered
            Log.w(LOG_TAG, "Exception @ pauseSession : can't unregister receiver ", iae);
        }
        if(notifBuilder != null) {
            notifBuilder.setContentText(getString(R.string.text_paused));
            notifBuilder.mActions.clear();
            String mediaId = mediaSes.getController().getMetadata().getDescription().getMediaId();
            if(SOURCE_AUDIO.equals(mediaId)) {
                notifBuilder.addAction(new NotificationCompat.Action(IC_NOTIF_PLAY,
                        getString(R.string.text_play),
                        MediaButtonReceiver.buildMediaButtonPendingIntent(AudioService.this,
                                PlaybackStateCompat.ACTION_PLAY)));
            } else if(SOURCE_MIC.equals(mediaId)) {
                notifBuilder.addAction(new NotificationCompat.Action(IC_NOTIF_RECORD,
                        getString(R.string.text_record),
                        MediaButtonReceiver.buildMediaButtonPendingIntent(AudioService.this,
                                PlaybackStateCompat.ACTION_PLAY)));
            }
            notifBuilder.addAction(new NotificationCompat.Action(IC_NOTIF_STOP,
                    getString(R.string.text_stop),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(AudioService.this,
                            PlaybackStateCompat.ACTION_STOP)));

            startForeground(SERVICE_ID, notifBuilder.build()); // to update notification
        }
        stopForeground(false); // stop but leave notification
    }

    private void stopSession() {
        Log.d(LOG_TAG, "stopSession()");
        if(audioManager != null) {
            audioManager.abandonAudioFocus(afchListener);
        } else {
            Log.w(LOG_TAG, "stopSession() : audioManager == null");
        }

        try {
            unregisterReceiver(bnReceiver);
        } catch (IllegalArgumentException iae) { // receiver not registered or unregistered onPause
            //Log.w(LOG_TAG, "Exception @ stopSession : can't unregister receiver ", iae);
        }
        // stop service to enable it be terminated when all clients will be unbound
        stopSelf();
        mediaSes.setActive(false);
        notifBuilder = null;
        stopForeground(true); // stop and remove notification
    }

    private void startVisualizer(int audioSesId) {
        Log.d(LOG_TAG, "startVisualizer() with audioSesId " + audioSesId);
        try {
            if(vslr == null) { // start new
                alWaveFormVal = new ArrayList<>(); // prev. list may still be using
                alFftVal = new ArrayList<>();
                vslr = new Visualizer(audioSesId);
                vslr.setCaptureSize(VISUALIZER_CAPTURE_SIZE); // default is 1024, min 128
                //vslr.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED); // test
                Log.d(LOG_TAG, "startVisualizer() : CaptureSize == " + vslr.getCaptureSize()
                        + " / " + Arrays.toString(Visualizer.getCaptureSizeRange()));
                Log.d(LOG_TAG, "startVisualizer() : SamplingRate == " + vslr.getSamplingRate());
                vslr.setDataCaptureListener(vslrListener, VISUALIZER_SAMPLE_RATE, true, true);
            } // else - resume
            vslr.setEnabled(true);
        } catch(IllegalStateException ise) {
            Log.e(LOG_TAG, "startVisualizer() : IllegalStateException ", ise);
        }
    }

    private void pauseVisualizer() {
        Log.d(LOG_TAG, "pauseVisualizer()");
        try {
            if(vslr != null) {
                //vslr.setDataCaptureListener(null, 0, false, false); // could be used
                vslr.setEnabled(false);
            } else {
                Log.d(LOG_TAG, "pauseVisualizer() : visualizer == null");
            }
        } catch(IllegalStateException ise) {
            Log.e(LOG_TAG, "pauseVisualizer() : IllegalStateException ", ise);
        }
    }

    private void stopVisualizer() {
        Log.d(LOG_TAG, "stopVisualizer()");
        final ArrayList<Byte> listWf = alWaveFormVal; // to save ref
        final ArrayList<Byte> listFft = alFftVal;
        try {
            if(vslr != null) {
                //vslr.setDataCaptureListener(null, 0, false, false); // could be used
                vslr.setEnabled(false);
                // vslr.release(); // produces crashes when called often
                vslr = null;
                // write data
                if(saveVslData != null) {
                    saveVslData.cancel(true);
                }
                saveVslData = new SaveVslDataATask();
                // to run another (amplitude) at the same time
                saveVslData.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, new VSLData(listWf, listFft));
                /* old way
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final StringBuilder msg = new StringBuilder();
                        try {
                            if(readyToWrite(listWf, listFft)) {
                                writeJSON(listWf, listFft); // IllegalStateException, IOE
                                writeXML(listWf, listFft); // IllegalStateException, IOE
                                final String filesPath = "" + getExternalFilesDir(null);
                                msg.append(getString(R.string.text_vsl_data_written)).append(filesPath);
                                Log.i(LOG_TAG, "Visualization data written to JSON and XML : "
                                        + filesPath);
                            } else {
                                Log.w(LOG_TAG, "visualization data isn't ready to write ");
                                msg.append(getString(R.string.text_err_vsl_data));
                            }
                        } catch(IllegalStateException | IllegalArgumentException | IOException e) {
                            Log.e(LOG_TAG, "writing virtualization data exception ", e);
                            msg.append(getString(R.string.text_err_vsl_data));
                        } finally {
                            hndlrUI.post(new Runnable() {
                                @Override
                                public void run() {
                                    Log.d(LOG_TAG, "Visualization result runnable " + isUiMsg());
                                    Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }
                }).start();
                */
            } else {
                Log.d(LOG_TAG, "stopVisualizer() : visualizer == null");
            }
        } catch(IllegalStateException e) {
            Log.e(LOG_TAG, "stopVisualizer() : Exception ", e);
        }
    }

    private class VSLData {
        private ArrayList<Byte> wf;
        private ArrayList<Byte> fft;

        public VSLData(ArrayList<Byte> wf, ArrayList<Byte> fft) {
            this.wf = wf;
            this.fft = fft;
        }

        public ArrayList<Byte> getWf() {
            return wf;
        }

        public ArrayList<Byte> getFft() {
            return fft;
        }
    }

    private class SaveVslDataATask extends AsyncTask<VSLData, Void, Integer> {
        final StringBuilder msg = new StringBuilder("");
        @Override
        protected Integer doInBackground(VSLData ... params) {
            Log.d(LOG_TAG, "SaveVslDataATask.doInBackground()");
            if(params == null || params.length < 1) {
                Log.e(LOG_TAG, "SaveVslDataATask.doInBackground() : wrong params");
                return -1;
            }
            if(isCancelled()) {
                return 1;
            }
            try {
                if(readyToWrite(params[0].getWf(), params[0].getFft())) {
                    if(isCancelled()) {
                        return 1;
                    }
                    writeJSON(params[0].getWf(), params[0].getFft()); // IllegalStateException, IOE
                    if(isCancelled()) {
                        return 1;
                    }
                    writeXML(params[0].getWf(), params[0].getFft()); // IllegalStateException, IOE
                    if(isCancelled()) {
                        return 1;
                    }
                    final String filesPath = "" + getExternalFilesDir(null);
                    msg.append(getString(R.string.text_vsl_data_written)).append(filesPath);
                    Log.i(LOG_TAG, "Visualization data written to JSON and XML : "
                            + filesPath);
                } else {
                    Log.w(LOG_TAG, "visualization data isn't ready to write ");
                    msg.append(getString(R.string.text_err_vsl_data));
                }
            } catch(IllegalStateException | IllegalArgumentException | IOException e) {
                Log.e(LOG_TAG, "writing virtualization data exception ", e);
                msg.append(getString(R.string.text_err_vsl_data));
                return 2;
            }
            return 0;
        }

        @Override
        protected void onPostExecute(Integer integer) {
            super.onPostExecute(integer);
            Log.d(LOG_TAG, "SaveVslDataATask.onPostExecute() ");
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
        }

        @Override
        protected void onCancelled(Integer integer) {
            super.onCancelled(integer);
            Log.d(LOG_TAG, "SaveVslDataATask.onCancelled() " + msg);
            //Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show(); // not needed
        }
    }

    /**
     * ready to write JSON or XML data to file
     * @return true if ready
     */
    private boolean readyToWrite(ArrayList<Byte> alWaveFormIn, ArrayList<Byte> alFftIn)  {
        Log.d(LOG_TAG, "readyToWrite() " + isUiMsg());
        if(alWaveFormIn == null || alWaveFormIn.isEmpty() || alFftIn == null || alFftIn.isEmpty()) {
            Log.w(LOG_TAG, "readyToWrite() : data is empty : \n" + alWaveFormIn + "; \n" + alFftIn);
            return false;
        }
        if(alWaveFormIn.size() != alFftIn.size()) {
            Log.w(LOG_TAG, "readyToWrite() : data lists not match : WaveForm.size = " + alWaveFormIn.size()
                    + "; Fft.size = " + alFftIn.size() + " \n " + alWaveFormIn + " \n " + alFftIn);
            return false;
        }
        if(! Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                && ! Environment.MEDIA_MOUNTED_READ_ONLY.equals(Environment.getExternalStorageState())) {
            Log.w(LOG_TAG, "readyToWrite() : media is not mounted");
            return false;
        }
        return true;
    }


    /**
     * writes visualization data to JSON file in external storage.
     * JSON format is:
     *
     * [
     *      {
     *          VSL_PROP_WAVEFORM_NAME : alWaveForm.get(li),
     *          VSL_PROP_FFT_NAME : alFft.get(li)
     *      },
     * ]
     *
     * @throws IllegalStateException on JSON error
     * @throws IOException on file stream error
     */
    private void writeJSON(ArrayList<Byte> alWaveFormIn, ArrayList<Byte> alFftIn)
            throws IllegalStateException, IOException {

        JsonWriter writer = new JsonWriter(new FileWriter(
                new File(getExternalFilesDir(null), VSL_JSON_FILE_NAME)));
        writer.setIndent("\t"); // for human readability

        writer.beginArray();
        for(int li = 0; li < alWaveFormIn.size(); li++) {
            writer.beginObject();
            writer.name(VSL_PROP_WAVEFORM_NAME).value(alWaveFormIn.get(li));
            writer.name(VSL_PROP_FFT_NAME).value(alFftIn.get(li));
            writer.endObject();
        }
        writer.endArray();
        writer.close();
    }

    /**
     * writes visulaizaton data to XML file
     * file structure :
     *
     * <DOCUMENT>
     *     <VLS_PROP_ENTRY_NAME>
     *          <VSL_PROP_WAVEFORM_NAME>
     *              data
     *          </VSL_PROP_WAVEFORM_NAME>
     *          <VSL_PROP_FFT_NAME>
     *              data
     *          </VSL_PROP_FFT_NAME>
     *     </VLS_PROP_ENTRY_NAME>
     * </DOCUMENT>
     *
     * @throws IllegalStateException on XML error
     * @throws IllegalArgumentException on XML error
     * @throws IOException on Filesystem error
     */
    private void writeXML(ArrayList<Byte> alWaveFormIn, ArrayList<Byte> alFftIn)
            throws IllegalStateException, IllegalArgumentException, IOException {

        XmlSerializer xml = Xml.newSerializer();
        xml.setOutput(new FileWriter(new File(getExternalFilesDir(null), VSL_XML_FILE_NAME)));
        xml.startDocument(Xml.Encoding.UTF_8.name(), true);
        for(int li = 0; li < alWaveFormIn.size(); li++) {
            xml.startTag(null, VSL_PROP_ENTRY_NAME)
                    .startTag(null, VSL_PROP_WAVEFORM_NAME)
                    .text(alWaveFormIn.get(li).toString())
                    .endTag(null, VSL_PROP_WAVEFORM_NAME)
                    .startTag(null, VSL_PROP_FFT_NAME)
                    .text(alFftIn.get(li).toString())
                    .endTag(null, VSL_PROP_FFT_NAME)
                    .endTag(null, VSL_PROP_ENTRY_NAME);
        }
        xml.endDocument();
        xml.flush();
    }

    /* can play using content resolver
    private void playFromContentResolver() {
        ContentResolver contentResolver = getContentResolver();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Cursor crs = contentResolver.query(uri, null, null, null, null);
        if(crs == null) {
            // query failed
        } else if(! crs.moveToFirst()) {
            // no media
        } else {
            int titleCol = crs.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int idCol = crs.getColumnIndex(MediaStore.Audio.Media._ID);
            do {
                long curId = crs.getLong(idCol);
                String curTitle = crs.getString(titleCol);
                // proc..
                Uri contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        curId);
                player = new MediaPlayer();
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);
                try {
                    player.setDataSource(getApplicationContext(), contentUri);
                    player.prepare(); // or set onPrepListener and prepare async
                    player.start();
                } catch (IOException e) {
                    // ..
                }
            } while (crs.moveToNext());
        }
    }
    */

    private void actionPlay(@Nullable Uri uri) {
        Log.d(LOG_TAG, "actionPlay " + isUiMsg());
        if(requestFocus()) {
            String metaDescr = (uri == null) ?
                    "" + mediaSes.getController().getMetadata().getDescription().getDescription() :
                    "" + uri;
            setMetaAndPBState(metadataBuilder
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, SOURCE_AUDIO)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, getString(R.string.app_name))
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, getString(R.string.text_playing))
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, metaDescr)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON,
                            BitmapFactory.decodeResource(getResources(), R.drawable.ic_mic))
                    .build(),
                    PlaybackStateCompat.STATE_PLAYING);

            startSession();
            servState = ServiceState.Playing;

            try {
                if(player != null && uri == null) { // resume
                    Log.d(LOG_TAG, "actionPlay : resume");
                    player.start();
                    startVisualizer(0); // resume
                    return;
                } else if(uri == null) {
                    throw new IllegalArgumentException("actionPlay : uri == null");
                }

                Log.d(LOG_TAG, "actionPlay : play " + uri);

                //MediaPlayer.create(..) // could be used, and prepare() is not needed
                player = new MediaPlayer();

                if(Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                        || Environment.MEDIA_MOUNTED_READ_ONLY.equals(Environment.getExternalStorageState())) {
                    // IOExceptions..
                    player.setAudioStreamType(AudioManager.STREAM_MUSIC);

                    player.setDataSource(getApplicationContext(), uri);
                    // do not let device go to sleep while playing
                    player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
                    /* if play over inet, can use this lock too to keep it active
                    WifiManager.WifiLock wifiLock = ((WifiManager) getSystemService(WIFI_SERVICE))
                            .createWifiLock(WifiManager.WIFI_MODE_FULL, "mylock");
                    wifiLock.acquire();
                    // when do not need no more
                    wifiLock.release();
                    */
                    player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(MediaPlayer mp) {
                            Log.d(LOG_TAG, "MediaPlayer.OnCompletionListener ");
                            /* unnecessary
                            mediaSes.setPlaybackState(stateBuilder
                                    .setState(PlaybackStateCompat.STATE_STOPPED, -1, 1)
                                    .setExtra(null) // reset
                                    .build());
                            */
                            actionStopPlaying();
                            //actionSetReady(); // unnecessary
                        }
                    });
                    player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override
                        public boolean onError(MediaPlayer mp, int what, int extra) {
                            Log.e(LOG_TAG, "MediaPlayer.OnErrorListener : " + what + "; " + extra);
                            setPBState(PlaybackStateCompat.STATE_ERROR,
                                    PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR,
                                    getString(R.string.text_err_player_start), null);
                            mp.reset(); // should be initialized then
                            mp.release();
                            actionSetReady();
                            return true;
                        }
                    });
                    player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer mp) {
                            startVisualizer(player.getAudioSessionId());
                            player.start();
                        }
                    });
                    //player.prepare(); // could be slow
                    player.prepareAsync();
                } else {
                    throw new IOException("Wrong External Storage State: "
                            + Environment.getExternalStorageState());
                }
            } catch (IOException | IllegalArgumentException e) {
                Log.e(LOG_TAG, "actionPlay() : can't start player for uri : " + uri, e);
                actionStopPlaying();
                setPBState(PlaybackStateCompat.STATE_ERROR,
                        PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR,
                        getString(R.string.text_err_player_start), null);
            }
        }
    }

    private void actionPausePlaying() {
        pauseSession();
        pauseVisualizer();
        setPBState(PlaybackStateCompat.STATE_PAUSED);
        servState = ServiceState.PausedPlaying;
        try {
            if (player != null) {
                player.pause();
            }
        } catch (IllegalArgumentException e) {
            Log.e(LOG_TAG, "actionPausePlaying() : IllegalArgumentException ", e);
            actionStopPlaying();
            setPBState(PlaybackStateCompat.STATE_ERROR);
        }
    }

    private void actionStopPlaying() {
        stopSession();
        stopVisualizer();
        setPBState(PlaybackStateCompat.STATE_STOPPED);
        servState = ServiceState.StoppedPlaying;
        try {
            if(player != null) {
                player.stop();
                player.release();
                player = null;
            }
        } catch (IllegalArgumentException e) {
            Log.e(LOG_TAG, "actionStopPlaying() : IllegalArgumentException ", e);
            setPBState(PlaybackStateCompat.STATE_ERROR);
        }
    }

    private void actionRecord() {
        recFilePath = audioFilesPath.getAbsolutePath() + "/" + genNewRecordName();

        setMetaAndPBState(metadataBuilder
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, SOURCE_MIC)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, getString(R.string.text_recording))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, recFilePath)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON,
                        BitmapFactory.decodeResource(getResources(), R.drawable.ic_mic))
                .build(),
                PlaybackStateCompat.STATE_PLAYING);

        startSession();
        // startVisualizer(0);
        servState = ServiceState.Recording;

        try {
            if(isPauseRecordingSupported() && recorder != null) {
                // can and should resume
                recorder.resume();
            } else {
                recorder = new MediaRecorder();
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                //TODO better and more reliable to use ogg / vorbis
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC);
                isRecorderInitialized = true;
                aTaskAmplitude = new RecAmplitudeChkAsyncTask();
                // to run another AT (save data) at the same time
                aTaskAmplitude.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void)null);

                if(Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                    //noinspection ResultOfMethodCallIgnored
                    audioFilesPath.mkdirs();
                    recorder.setOutputFile(recFilePath);
                    /* need to test
                    recorder.setAudioChannels(1);
                    recorder.setAudioSamplingRate(44100);
                    recorder.setAudioEncodingBitRate(96000);
                    */
                    /* can set onInfo and onError listeners, in this case Recorder should be created
                    on threads with Looper (Like UI thread) */
                    // IOE
                    recorder.prepare();
                    recorder.start();
                } else {
                    throw new IOException("Wrong External Storage State: "
                            + Environment.getExternalStorageState());
                }
            }

        } catch (IOException | IllegalArgumentException e) {
            Log.e(LOG_TAG, "actionRecord() : can't start recorder ", e);
            actionStopRecording();
            setPBState(PlaybackStateCompat.STATE_ERROR,
                    PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR,
                            getString(R.string.text_err_recorder_start), null);
        }
    }

    private void actionPauseRecording() {
        pauseSession();
        //pauseVisualizer();
        setPBState(PlaybackStateCompat.STATE_PAUSED);
        servState = ServiceState.PausedRecording;
        try {
            if(isPauseRecordingSupported() && recorder != null) {
                recorder.pause();
            }
        } catch (IllegalArgumentException e) {
            Log.e(LOG_TAG, "actionPauseRecording() : IllegalArgumentException ", e);
            actionStopRecording();
            setPBState(PlaybackStateCompat.STATE_ERROR);
        }
    }

    private void actionStopRecording() {
        stopSession();
        //stopVisualizer();
        setPBState(PlaybackStateCompat.STATE_STOPPED);
        servState = ServiceState.StoppedRecording;
        try {
            if(recorder != null) {
                try {
                    recorder.stop();
                    if(aTaskAmplitude != null) {
                        aTaskAmplitude.cancel(true);
                        aTaskAmplitude = null;
                    }
                    isRecorderInitialized = false;
                } catch (RuntimeException stopFailed) {
                    Log.e(LOG_TAG, "actionStopRecording() : MediaRecorder stop Failed" +
                            "(could be no valid audio/video data has been received) ", stopFailed);
                    setPBState(PlaybackStateCompat.STATE_ERROR,
                            PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
                            getString(R.string.text_err_recorder_stop), null);
                    if(recFilePath != null) {
                        File file = new File(recFilePath);
                        if(file.exists()) {
                            if(! file.delete()) {
                                Log.w(LOG_TAG, "actionStopRecording : can't delete " + file);
                            } else {
                                Log.i(LOG_TAG, "actionStopRecording : file deleted " + file);
                            }
                        }
                    }
                }
                recorder.release();
                recorder = null;
            }
        } catch (IllegalArgumentException e) {
            Log.e(LOG_TAG, "actionPauseRecording() : IllegalArgumentException ", e);
            setPBState(PlaybackStateCompat.STATE_ERROR);
        }
        notifyChildrenChanged(MEDIA_ROOT_ID);
        recFilePath = null;
    }

    private void actionSetReady() {
        setMetaAndPBState(metadataBuilder
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, SOURCE_NONE).build(),
                PlaybackStateCompat.STATE_NONE);
        servState = ServiceState.Ready;
    }
}
