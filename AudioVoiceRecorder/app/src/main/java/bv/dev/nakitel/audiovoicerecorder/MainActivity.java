package bv.dev.nakitel.audiovoicerecorder;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SimpleAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "bv_log";
    private static final int PERM_RECORD_N_WRITE = 1;
    private static final String LIST_ITEM_KEY_NAME = "LIST_ITEM_KEY_NAME";

    private static final int CHART_TYPE_AMPLITUDE = 0;
    private static final int CHART_TYPE_MAGNITUDE = 1;
    private static final int CHART_TYPE_SPEC_DENSITY = 2;

    private MediaBrowserCompat mediaBrowser;

    private ListView lvRecords;
    private ImageButton ibStart;
    private ImageButton ibStop;
    private TextView tvState;
    private TextView tvAmp;
    private ProgressBar pbAmp;
    private Spinner spinCharType;
    private PlotView plotView;

    private SimpleAdapter lvRecAdapter;
    private List<MediaBrowserCompat.MediaItem> listMediaItems;
    private List<Map<String, String>> listItemsRecords = new ArrayList<>();
    private DecimalFormat decFormat = new DecimalFormat("#.##");

    private MediaBrowserCompat.SubscriptionCallback subscriptCallback = new MediaBrowserCompat.SubscriptionCallback() {
        @Override
        public void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaBrowserCompat.MediaItem> children) {
            onChildrenLoaded(parentId, children, new Bundle());
        }

        @Override
        public void onChildrenLoaded(@NonNull String parentId,
                                     @NonNull List<MediaBrowserCompat.MediaItem> children,
                                     @NonNull Bundle options) {

            if(children.size() == 0) {
                Log.w(LOG_TAG, "MediaBrowser.SubscriptionCallback.onChildrenLoaded() : "
                        + "children.size() == 0 ; parentId == " + parentId);
                listMediaItems = children;
                listItemsRecords.clear();
                setLocalStateStoppedReady();
                //setStateError(); // not an error
            } else {
                Log.d(LOG_TAG, "MediaBrowser.SubscriptionCallback.onChildrenLoaded() : " + children);
                listMediaItems = children;
                listItemsRecords.clear();
                for (MediaBrowserCompat.MediaItem item : children) {
                    HashMap<String, String> mapItem = new HashMap<>(1);
                    mapItem.put(LIST_ITEM_KEY_NAME, "" + item.getDescription().getDescription());
                    listItemsRecords.add(mapItem);
                }
            }
            lvRecAdapter.notifyDataSetChanged();
        }

        @Override
        public void onError(@NonNull String parentId) {
            onError(parentId, new Bundle());
        }

        @Override
        public void onError(@NonNull String parentId, @NonNull Bundle options) {
            Log.w(LOG_TAG, "MediaBrowser.SubscriptionCallback.onError() : parentId == " + parentId);
            callStopReady();
            setLocalStateError();
        }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // android:launchMode="singleTop"
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        /*
        No landscape orientation yet.
        android:screenOrientation="sensorPortrait" (~like "portrait")
        used to disable changing orientation for now.
        */
        setContentView(R.layout.activity_main);

        ibStart = (ImageButton) findViewById(R.id.ibStart);
        ibStop = (ImageButton) findViewById(R.id.ibStop);
        tvState = (TextView) findViewById(R.id.tvState);
        lvRecords = (ListView) findViewById(R.id.lvRecords);
        tvAmp = (TextView) findViewById(R.id.tvAmplitude);
        pbAmp = (ProgressBar) findViewById(R.id.pbAmplitude);
        spinCharType = (Spinner) findViewById(R.id.spinChartType);
        plotView = (PlotView) findViewById(R.id.plotView);

        ibStop.setVisibility(View.INVISIBLE);
        ibStart.setVisibility(View.INVISIBLE);
        tvAmp.setText("");
        pbAmp.setProgress(0);
        spinCharType.setSelection(1); // magnitude
        spinCharType.setVisibility(View.GONE);
        plotView.setVisibility(View.GONE);

        lvRecAdapter = new SimpleAdapter(this, listItemsRecords, R.layout.list_item_record,
                new String[] {LIST_ITEM_KEY_NAME}, new int[] {R.id.tvItemText});
        lvRecords.setAdapter(lvRecAdapter);

        // to make it call need to add 'android:descendantFocusability="blocksDescendants"'
        // to root layout view of item layout file
        // and remove all "clickable" properties from item layout
        lvRecords.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                Log.d(LOG_TAG, "---------------------------------------------------");
                if(lvRecAdapter == null) {
                    Log.w(LOG_TAG, "ListView records onItemClick() : list view records adapter == null");
                    return;
                }
                Map data = (Map) lvRecAdapter.getItem(pos);
                String name = (String) data.get(LIST_ITEM_KEY_NAME);
                Log.i(LOG_TAG, "ListView records onItemClick() : searching for " + name);
                for(MediaBrowserCompat.MediaItem item : listMediaItems) {
                    if(name.equals(item.getDescription().getDescription())) {
                        callPlay(item.getDescription().getMediaUri());
                        break;
                    }
                }
            }
        });

        mediaBrowser = new MediaBrowserCompat(this, new ComponentName(this, AudioService.class),
                connCallbacks,
                null); // optional bundle

        // java.lang.IllegalStateException: connect() called while not disconnected (state=CONNECT_STATE_CONNECTED)
        if(! mediaBrowser.isConnected()) {
            // not in onStart : http://stackoverflow.com/questions/43169875/mediabrowser-subscribe-doesnt-work-after-i-get-back-to-activity-1-from-activity
            mediaBrowser.connect();
        }

        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // check permissions
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ArrayList<String> alPermissions = new ArrayList<>(2);
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                alPermissions.add(Manifest.permission.RECORD_AUDIO);
                if(shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    // can show dialog
                    Toast.makeText(this, R.string.text_perm_record_required, Toast.LENGTH_LONG).show();
                }
            }
            if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                alPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                if(shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    // can show dialog
                    Toast.makeText(this, R.string.text_perm_write_required, Toast.LENGTH_LONG).show();
                }
            }
            if(alPermissions.size() != 0) {
                requestPermissions(alPermissions.toArray(new String[alPermissions.size()]), PERM_RECORD_N_WRITE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch(requestCode) {
            case PERM_RECORD_N_WRITE:
                boolean permDenied = (grantResults.length == 0);
                if(grantResults.length != 0) {
                    for(int res : grantResults) {
                        permDenied = permDenied || res != PackageManager.PERMISSION_GRANTED;
                    }
                }
                if(permDenied) {
                    // permissions not granted
                    Toast.makeText(this, R.string.text_perm_required, Toast.LENGTH_LONG).show();
                    // can't work without permissions
                    finish();
                }
                break;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // to fix same problem as with subscription
        MediaControllerCompat cntrlr = MediaControllerCompat.getMediaController(this);
        if(cntrlr != null) {
            cntrlr.unregisterCallback(cntrlrCallback);
        }
        if(mediaBrowser.isConnected()) {
            mediaBrowser.unsubscribe(mediaBrowser.getRoot());
            mediaBrowser.disconnect();
        }
    }

    public void tvRecordsClick(View v) {
        Log.d(LOG_TAG, "tvRecordsClick");
        reloadFilesList();
    }

    public void ibItemDelClick(View v) {
        Log.d(LOG_TAG, "---------------------------------------------------");
        File file = getFile4LvRecItem(v);
        if(file != null) {
            if(! file.delete()) {
                Log.w(LOG_TAG, "ibItemDelClick() : can't delete file \"" + file + "\"");
            }
            Log.i(LOG_TAG, "ibItemDelClick() : file \"" + file + "\" deleted : "
                    + file.exists());
        }
        reloadFilesList();
    }

    public void ibItemOpenExtClick(View v) {
        File file = getFile4LvRecItem(v);
        if(file != null) {
            Intent inOpen = new Intent(Intent.ACTION_VIEW);
            //inOpen.addCategory() // not needed
            inOpen.setDataAndType(Uri.fromFile(file), AudioService.VOICE_REC_MIME);
            ComponentName cn = inOpen.resolveActivity(getPackageManager());
            if(cn != null) {
                Log.w(LOG_TAG, "ibItemOpenExtClick() : Resolved activity for intent " + cn);
                startActivity(inOpen);
            } else {
                Log.w(LOG_TAG, "ibItemOpenExtClick() : Can't resolve activity for intent " + inOpen);
                Toast.makeText(this, R.string.text_err_no_audio_player, Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * get audio record item which was clicked in the list
     * @param v view
     */
    @Nullable
    public File getFile4LvRecItem(View v) {
        View parent;
        try {
            parent = (View) v.getParent();
            int pos = lvRecords.getPositionForView(parent);
            if(pos == AdapterView.INVALID_POSITION) {
                Log.e(LOG_TAG, "Error @ getFile4LvRecItem : invalid position ");
                return null;
            }
            Map data = (Map) lvRecAdapter.getItem(pos);
            String name = (String) data.get(LIST_ITEM_KEY_NAME);
            Log.i(LOG_TAG, "getFile4LvRecItem() : searching for \"" + name + "\"");
            for(MediaBrowserCompat.MediaItem item : listMediaItems) {
                if(name.equals(item.getDescription().getDescription())) {
                    File file = new File("" + item.getDescription().getMediaUri());
                    Log.d(LOG_TAG, "getFile4LvRecItem() : file found : \"" + file.toString() + "\"");
                    if(file.exists()) {
                        return file;
                    } else {
                        Log.w(LOG_TAG, "getFile4LvRecItem() : file \"" + file + "\" not exists");
                    }
                    break;
                }
            }
        } catch(ClassCastException | NullPointerException e) {
            Log.e(LOG_TAG, "Error @ getFile4LvRecItem", e);
        }
        return null;
    }

    private void reloadFilesList() {
        if(mediaBrowser.isConnected()) {
            mediaBrowser.unsubscribe(mediaBrowser.getRoot());
            mediaBrowser.subscribe(mediaBrowser.getRoot(), subscriptCallback); // reload
            lvRecAdapter.notifyDataSetChanged();
        } else {
            Log.w(LOG_TAG, "reloadFilesList() : mediaBrowser is not connected");
        }
    }

    private final MediaBrowserCompat.ConnectionCallback connCallbacks =
            new MediaBrowserCompat.ConnectionCallback() {
                @Override
                public void onConnected() {
                    Log.i(LOG_TAG, "MediaBrowserService connected");

                    // can produce java.lang.IllegalStateException: getRoot() called while not connected(state=CONNECT_STATE_DISCONNECTED)
                    mediaBrowser.subscribe(mediaBrowser.getRoot(), new Bundle(), subscriptCallback);

                    MediaSessionCompat.Token sesTok = mediaBrowser.getSessionToken();
                    try {
                        MediaControllerCompat mediaCntrlr = new MediaControllerCompat(MainActivity.this, sesTok);
                        MediaControllerCompat.setMediaController(MainActivity.this, mediaCntrlr);
                    } catch (RemoteException re) {
                        Log.e(LOG_TAG, "Error while creating MediaController", re);
                    }
                    buildTransportControls();
                }

                @Override
                public void onConnectionSuspended() {
                    super.onConnectionSuspended();
                    Log.w(LOG_TAG, "MediaBrowserService connection suspended");
                }

                @Override
                public void onConnectionFailed() {
                    super.onConnectionFailed();
                    Log.e(LOG_TAG, "MediaBrowserService connection failed");
                }
            };

    private void logErrPlaybackState(PlaybackStateCompat cntrlPbState) {
        Log.i(LOG_TAG, "PlaybackState code : " + cntrlPbState.getState()
                + "; Error code = " + cntrlPbState.getErrorCode()
                + "; Error msg: " + cntrlPbState.getErrorMessage()
                + "\n PlaybackState " + cntrlPbState);
    }

    private void logPlaybackState(PlaybackStateCompat cntrlPbState) {
        int pbState = cntrlPbState.getState();
        switch(pbState) {
            case PlaybackStateCompat.STATE_PLAYING:
                Log.d(LOG_TAG, "PlaybackState == Playing / Recording");
                break;
            case PlaybackStateCompat.STATE_NONE:
                Log.d(LOG_TAG, "PlaybackState == None / Ready");
                break;
            case PlaybackStateCompat.STATE_PAUSED:
                Log.d(LOG_TAG, "PlaybackState == Paused / Ready");
                break;
            case PlaybackStateCompat.STATE_STOPPED:
                Log.d(LOG_TAG, "PlaybackState == Stopped / Ready");
                break;
            default:
                logErrPlaybackState(cntrlPbState);
                break;
        }
    }

    private final MediaControllerCompat.Callback cntrlrCallback = new MediaControllerCompat.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            MediaControllerCompat mediaCntrlr = MediaControllerCompat.getMediaController(MainActivity.this);
            if(mediaCntrlr == null) {
                Log.e(LOG_TAG, "MediaController.Callback.onPlaybackStateChanged() : mediaCntrlr == null");
                setLocalStateError();
                tvState.setText(R.string.text_service_conn_err);
                return;
            }

            String mediaID = mediaCntrlr.getMetadata().getDescription().getMediaId();
            if(mediaID == null) {
                Log.e(LOG_TAG, "MediaController.Callback.onPlaybackStateChanged() : mediaID == null");
                setLocalStateError();
                tvState.setText(R.string.text_service_conn_err);
                return;
            }

            Bundle bndl = state.getExtras();
            if(state.getState() == PlaybackStateCompat.STATE_PLAYING && bndl != null) {
                // update of extra, not state
                double amp = bndl.getDouble(AudioService.EXTRA_KEY_AMPL, -1);
                double[] arFreq = bndl.getDoubleArray(AudioService.EXTRA_KEY_AR_FREQ);
                if(amp != -1 && ! Double.isInfinite(amp) && ! Double.isNaN(amp)) {
                    // update db meter
                    tvAmp.setText(decFormat.format(amp));
                    tvAmp.append(" dB");
                    pbAmp.setProgress((int) amp);
                    return; // handled
                } else if(arFreq != null) {
                    // waveform or fft
                    double[] arAmp = bndl.getDoubleArray(AudioService.EXTRA_KEY_AR_AMPL);
                    double[] arMagn = bndl.getDoubleArray(AudioService.EXTRA_KEY_AR_MAGN);
                    double[] arSpecDens = bndl.getDoubleArray(AudioService.EXTRA_KEY_AR_DENS);
                    int chartType = spinCharType.getSelectedItemPosition();
                    if(arAmp != null && chartType == CHART_TYPE_AMPLITUDE) {
                        // waveform
                        plotView.setArX(arFreq);
                        plotView.setArY(arAmp);
                        plotView.setXTitle("Hz");
                        plotView.setYTitle("dB");
                        plotView.invalidate(); // redraw
                    } else if(arMagn != null && chartType == CHART_TYPE_MAGNITUDE) {
                        // fft
                        plotView.setArX(arFreq);
                        plotView.setArY(arMagn);
                        plotView.setXTitle("Hz");
                        plotView.setYTitle("");
                        plotView.invalidate(); // redraw
                    } else if(arSpecDens != null && chartType == CHART_TYPE_SPEC_DENSITY) {
                        // fft
                        plotView.setArX(arFreq);
                        plotView.setArY(arSpecDens);
                        plotView.setXTitle("Hz");
                        plotView.setYTitle("");
                        plotView.invalidate(); // redraw
                    }
                    return; // handled
                } else {
                    Log.w(LOG_TAG, "MediaController.Callback.onPlaybackStateChanged() : "
                            + "wrong bundle content : " + bndl);
                }
            }
            Log.d(LOG_TAG, "MediaController.Callback.onPlaybackStateChanged() : mediaID == " + mediaID);

            switch (state.getState()) {
                case PlaybackStateCompat.STATE_ERROR:
                    setLocalStateError();
                    break;
                case PlaybackStateCompat.STATE_STOPPED:
                case PlaybackStateCompat.STATE_NONE:
                    setLocalStateStoppedReady();
                    break;
                case PlaybackStateCompat.STATE_PAUSED:
                    switch (mediaID) {
                        case AudioService.SOURCE_AUDIO:
                            setLocalStatePausedPlaying();
                            break;
                        case AudioService.SOURCE_MIC:
                            setLocalStatePausedRecording();
                            break;
                    }
                    break;
                case PlaybackStateCompat.STATE_PLAYING:
                    switch (mediaID) {
                        case AudioService.SOURCE_AUDIO:
                            setLocalStatePlaying();
                            break;
                        case AudioService.SOURCE_MIC:
                            setLocalStateRecording();
                            break;
                    }
                    break;
            }
            logPlaybackState(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            super.onMetadataChanged(metadata);
            Log.i(LOG_TAG, "MediaControllerCompat.Callback.onMetadataChanged() : "
                    + metadata.getDescription().getMediaId());
        }
    };

    private void callPlay(Uri uri) {
        final MediaControllerCompat mediaCntrlr = MediaControllerCompat.getMediaController(this);
        if(mediaCntrlr != null) {
            //mediaCntrlr.getTransportControls().playFromMediaId(AudioService.SOURCE_AUDIO, null);
            // should play from uri
            if(uri != null) {
                mediaCntrlr.getTransportControls().playFromUri(uri, null);
            } else {
                mediaCntrlr.getTransportControls().playFromMediaId(AudioService.SOURCE_AUDIO, null);
            }
        }
    }

    private void callRecord() {
        final MediaControllerCompat mediaCntrlr = MediaControllerCompat.getMediaController(this);
        if(mediaCntrlr != null) {
            /* another way
            mediaCntrlr.sendCommand(AudioService.SOURCE_MIC, null, null);
            mediaCntrlr.getTransportControls().play();
            */
            mediaCntrlr.getTransportControls().playFromMediaId(AudioService.SOURCE_MIC, null);
        }
    }

    private void callPausePlaying() {
        final MediaControllerCompat mediaCntrlr = MediaControllerCompat.getMediaController(this);
        if(mediaCntrlr != null) {
            mediaCntrlr.getTransportControls().pause();
        }
    }

    private void callPauseRecording() {
        final MediaControllerCompat mediaCntrlr = MediaControllerCompat.getMediaController(this);
        if(mediaCntrlr != null) {
            mediaCntrlr.getTransportControls().pause();
        }
    }

    private void callStopReady() {
        final MediaControllerCompat mediaCntrlr = MediaControllerCompat.getMediaController(this);
        if (mediaCntrlr != null) {
            mediaCntrlr.getTransportControls().stop();
            mediaCntrlr.sendCommand(AudioService.SOURCE_NONE, null, null);
        }
    }

    private void setLocalStatePlaying() {
        tvState.setText(R.string.text_playing);
        ibStart.setOnClickListener(ibPlayOCL);
        ibStart.setImageResource(R.drawable.ic_pause);
        ibStart.setVisibility(View.VISIBLE);
        ibStart.setEnabled(true);
        ibStart.setPressed(true);
        //ibStop.setImageResource(R.drawable.ic_stop); // redundant
        ibStop.setVisibility(View.VISIBLE);
        tvAmp.setText("");
        pbAmp.setProgress(0);
        spinCharType.setVisibility(View.VISIBLE);
        plotView.setVisibility(View.VISIBLE);
    }

    private void setLocalStateRecording() {
        tvState.setText(R.string.text_recording);
        ibStart.setOnClickListener(ibRecordOCL);
        if(AudioService.isPauseRecordingSupported()) {
            ibStart.setImageResource(R.drawable.ic_pause);
            ibStart.setPressed(true);
        } else {
            //ibStart.setVisibility(View.GONE); // looks bad
            ibStart.setEnabled(false);
            ibStart.setPressed(true);
        }
        //ibStop.setImageResource(R.drawable.ic_stop); // redundant
        ibStop.setVisibility(View.VISIBLE);
        tvAmp.setText("");
        // pbAmp.setProgress(0); // redundant
        spinCharType.setVisibility(View.GONE);
        plotView.setVisibility(View.GONE);
    }

    private void setLocalStatePausedPlaying() {
        tvState.setText(R.string.text_paused_play);
        ibStart.setOnClickListener(ibPlayOCL);
        ibStart.setImageResource(R.drawable.ic_play);
        ibStart.setEnabled(true);
        ibStart.setPressed(false);
        ibStart.setVisibility(View.VISIBLE);
        //ibStop.setImageResource(R.drawable.ic_stop); // redundant
        ibStop.setVisibility(View.VISIBLE);
        tvAmp.setText("");
        pbAmp.setProgress(0);
        spinCharType.setVisibility(View.VISIBLE);
        plotView.setVisibility(View.VISIBLE);
    }

    private void setLocalStatePausedRecording() {
        tvState.setText(R.string.text_paused_rec);
        ibStart.setOnClickListener(ibRecordOCL);
        ibStart.setImageResource(R.drawable.ic_mic);
        ibStart.setEnabled(true);
        ibStart.setPressed(false);
        ibStart.setVisibility(View.VISIBLE);
        //ibStop.setImageResource(R.drawable.ic_stop); // redundant
        ibStop.setVisibility(View.VISIBLE);
        spinCharType.setVisibility(View.GONE);
        plotView.setVisibility(View.GONE);
    }

    private void setLocalStateStoppedReady() {
        tvState.setText(R.string.text_ready);
        ibStart.setOnClickListener(ibRecordOCL);
        ibStart.setImageResource(R.drawable.ic_mic);
        ibStart.setEnabled(true);
        ibStart.setPressed(false);
        ibStart.setVisibility(View.VISIBLE);
        //ibStop.setImageResource(R.drawable.ic_stop); // redundant
        ibStop.setVisibility(View.INVISIBLE);
        tvAmp.setText("");
        pbAmp.setProgress(0);
        spinCharType.setVisibility(View.GONE);
        plotView.setVisibility(View.GONE);
    }

    private void setLocalStateError() {
        tvState.setText(R.string.text_error);
        ibStart.setOnClickListener(ibRecordOCL);
        ibStart.setImageResource(R.drawable.ic_mic);
        ibStart.setEnabled(true);
        ibStart.setPressed(false);
        ibStart.setVisibility(View.VISIBLE);
        //ibStop.setImageResource(R.drawable.ic_stop); // redundant
        ibStop.setVisibility(View.INVISIBLE);
        tvAmp.setText("");
        pbAmp.setProgress(0);
        spinCharType.setVisibility(View.GONE);
        plotView.setVisibility(View.GONE);

        //state.getErrorCode() == PlaybackStateCompat.ERROR_CODE_APP_ERROR // could be used
        MediaControllerCompat mediaCntrlr = MediaControllerCompat.getMediaController(this);
        if(mediaCntrlr != null) {
            Toast.makeText(MainActivity.this,
                    mediaCntrlr.getPlaybackState().getErrorMessage(),
                    Toast.LENGTH_LONG).show();
            logPlaybackState(mediaCntrlr.getPlaybackState());
        }
    }

    private void buildTransportControls(){
        MediaControllerCompat mediaCntrlr = MediaControllerCompat.getMediaController(this);
        if(mediaCntrlr == null) {
            Log.e(LOG_TAG, "buildTransportControls() : mediaCntrlr == null");
            tvState.setText(R.string.text_service_conn_err);
            return;
        }
        mediaCntrlr.registerCallback(cntrlrCallback); // can pass Handler for worker thread

        String mediaID = mediaCntrlr.getMetadata().getDescription().getMediaId();
        if(mediaID == null) {
            Log.e(LOG_TAG, "buildTransportControls() : mediaID == null");
            tvState.setText(R.string.text_service_conn_err);
            return;
        }
        Log.d(LOG_TAG, "buildTransportControls() : mediaID == " + mediaID);

        int pbState = mediaCntrlr.getPlaybackState().getState();
        logPlaybackState(mediaCntrlr.getPlaybackState());

        // set initial UI state
        ibStart.setVisibility(View.VISIBLE);
        ibStart.setOnClickListener(ibRecordOCL);
        ibStop.setOnClickListener(ibStopOCL);

        if(pbState == PlaybackStateCompat.STATE_ERROR) {
            Log.d(LOG_TAG, "buildTransportControls() : PlaybackState == Error");
            setLocalStateError();
            return;
        }

        // can call onPBStateChanged callback instead
        switch(mediaID) {
            case AudioService.SOURCE_NONE:
                setLocalStateStoppedReady();
                break;
            case AudioService.SOURCE_AUDIO:
                switch(pbState) {
                    case PlaybackStateCompat.STATE_PLAYING:
                        setLocalStatePlaying();
                        break;
                    case PlaybackStateCompat.STATE_PAUSED:
                        setLocalStatePausedPlaying();
                        break;
                    case PlaybackStateCompat.STATE_NONE:
                    case PlaybackStateCompat.STATE_STOPPED:
                        setLocalStateStoppedReady();
                        break;
                }
                break;
            case AudioService.SOURCE_MIC:
                switch(pbState) {
                    case PlaybackStateCompat.STATE_PLAYING:
                        setLocalStateRecording();
                        break;
                    case PlaybackStateCompat.STATE_PAUSED:
                        setLocalStatePausedRecording();
                        break;
                    case PlaybackStateCompat.STATE_NONE:
                    case PlaybackStateCompat.STATE_STOPPED:
                        setLocalStateStoppedReady();
                        break;
                }
                break;
            default:
                Log.e(LOG_TAG, "buildTransportControls() : unknown mediaID : " + mediaID);
                tvState.setText(R.string.text_service_conn_err);
        }
    }

    private final View.OnClickListener ibRecordOCL = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            Log.d(LOG_TAG, "---------------------------------------------------");
            MediaControllerCompat mediaCntrlr = MediaControllerCompat.getMediaController(MainActivity.this);
            if(mediaCntrlr == null) {
                Log.e(LOG_TAG, "ibRecordOCL.onClick() : mediaCntrlr == null");
                tvState.setText(R.string.text_service_conn_err);
                return;
            }
            String mediaID = mediaCntrlr.getMetadata().getDescription().getMediaId();
            if(mediaID == null) {
                Log.e(LOG_TAG, "ibRecordOCL.onClick() : mediaID == null");
                tvState.setText(R.string.text_service_conn_err);
                return;
            }
            int pbState = mediaCntrlr.getPlaybackState().getState();

            if(pbState == PlaybackStateCompat.STATE_ERROR) {
                Log.d(LOG_TAG, "btn Record click : PlaybackState == Error");
                callStopReady();
                callRecord();
                return;
            }

            // current state -> new state
            switch(mediaID) {
                case AudioService.SOURCE_NONE:
                case AudioService.SOURCE_MIC:
                    switch(pbState) {
                        case PlaybackStateCompat.STATE_PLAYING:
                            Log.d(LOG_TAG, "btn Record click : new PlaybackState == Paused Recording");
                            callPauseRecording(); // maybe should again check is pause rec able
                            break;
                        case PlaybackStateCompat.STATE_NONE:
                        case PlaybackStateCompat.STATE_PAUSED:
                        case PlaybackStateCompat.STATE_STOPPED:
                            Log.d(LOG_TAG, "btn Record click : new PlaybackState == Recording");
                            callRecord();
                            break;
                        default:
                            Log.e(LOG_TAG, "btn Record click : wrong playback state");
                            logPlaybackState(mediaCntrlr.getPlaybackState());
                            break;
                    }
                    break;
                case AudioService.SOURCE_AUDIO:
                    switch(pbState) {
                        case PlaybackStateCompat.STATE_PLAYING:
                        case PlaybackStateCompat.STATE_PAUSED:
                            Log.d(LOG_TAG, "btn Record click : new PlaybackState == Recording");
                            callStopReady();
                            callRecord();
                            break;
                        case PlaybackStateCompat.STATE_NONE:
                        case PlaybackStateCompat.STATE_STOPPED:
                            Log.d(LOG_TAG, "btn Record click : new PlaybackState == Recording");
                            callRecord();
                            break;
                        default:
                            Log.e(LOG_TAG, "btn Record click : unknown playback state");
                            logPlaybackState(mediaCntrlr.getPlaybackState());
                            break;
                    }
                    break;
                default:
                    Log.e(LOG_TAG, "ibRecordOCL.onClick() : wrong mediaID : " + mediaID);
                    tvState.setText(R.string.text_service_conn_err);
                    break;
            }
        }
    };

    private final View.OnClickListener ibPlayOCL = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            Log.d(LOG_TAG, "---------------------------------------------------");
            MediaControllerCompat mediaCntrlr = MediaControllerCompat.getMediaController(MainActivity.this);
            if(mediaCntrlr == null) {
                Log.e(LOG_TAG, "ibPlayOCL.onClick() : mediaCntrlr == null");
                tvState.setText(R.string.text_service_conn_err);
                return;
            }
            String mediaID = mediaCntrlr.getMetadata().getDescription().getMediaId();
            if(mediaID == null) {
                Log.e(LOG_TAG, "ibPlayOCL.onClick() : mediaID == null");
                tvState.setText(R.string.text_service_conn_err);
                return;
            }
            int pbState = mediaCntrlr.getPlaybackState().getState();

            if(pbState == PlaybackStateCompat.STATE_ERROR) {
                Log.d(LOG_TAG, "btn Play click : PlaybackState == Error");
                callStopReady();
                callPlay(null);
                return;
            }

            // current state -> new state
            switch(mediaID) {
                case AudioService.SOURCE_NONE:
                case AudioService.SOURCE_AUDIO:
                    switch(pbState) {
                        case PlaybackStateCompat.STATE_PLAYING:
                            Log.d(LOG_TAG, "btn Play click : new PlaybackState == Paused Playing");
                            callPausePlaying();
                            break;
                        case PlaybackStateCompat.STATE_NONE:
                        case PlaybackStateCompat.STATE_PAUSED:
                        case PlaybackStateCompat.STATE_STOPPED:
                            Log.d(LOG_TAG, "btn Play click : new PlaybackState == Playing");
                            callPlay(null);
                            break;
                        default:
                            Log.e(LOG_TAG, "btn Play click : unknown playback state");
                            logPlaybackState(mediaCntrlr.getPlaybackState());
                            break;
                    }
                    break;
                case AudioService.SOURCE_MIC:
                    /* unsuitable now
                    switch(pbState) {
                        case PlaybackStateCompat.STATE_PLAYING:
                        case PlaybackStateCompat.STATE_PAUSED:
                            callStopReady();
                            callPlay();
                            Log.d(LOG_TAG, "btn Play click : new PlaybackState == Playing");
                            break;
                        case PlaybackStateCompat.STATE_NONE:
                        case PlaybackStateCompat.STATE_STOPPED:
                            callPlay();
                            Log.d(LOG_TAG, "btn Play click : new PlaybackState == Playing");
                            break;
                        default:
                            Log.e(LOG_TAG, "btn Play click : unknown playback state");
                            logPlaybackState(mediaCntrlr.getPlaybackState());
                            break;
                    }
                    break;
                    */
                default:
                    Log.e(LOG_TAG, "ibPlayOCL.onClick() : wrong mediaID : " + mediaID);
                    tvState.setText(R.string.text_service_conn_err);
                    break;
            }
        }
    };

    private final View.OnClickListener ibStopOCL = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            Log.d(LOG_TAG, "---------------------------------------------------");
            MediaControllerCompat mediaCntrlr = MediaControllerCompat.getMediaController(MainActivity.this);
            if(mediaCntrlr == null) {
                Log.e(LOG_TAG, "ibStop.onClick() : mediaCntrlr == null");
                tvState.setText(R.string.text_service_conn_err);
                return;
            }
            String mediaID = mediaCntrlr.getMetadata().getDescription().getMediaId();
            if(mediaID == null) {
                Log.e(LOG_TAG, "ibStop.onClick() : mediaID == null");
                tvState.setText(R.string.text_service_conn_err);
                return;
            }
            int pbState = mediaCntrlr.getPlaybackState().getState();

            if(pbState == PlaybackStateCompat.STATE_ERROR) {
                Log.d(LOG_TAG, "ibStopOCL click : PlaybackState == Error");
                setLocalStateError();
                //setStateStoppedReady(); // to do not hide error
                return;
            }

            // current state -> new state
            switch(mediaID) {
                case AudioService.SOURCE_AUDIO:
                case AudioService.SOURCE_MIC:
                    switch(pbState) {
                        case PlaybackStateCompat.STATE_PLAYING:
                        case PlaybackStateCompat.STATE_PAUSED:
                            Log.d(LOG_TAG, "ibStopOCL click : new PlaybackState == Stopped / Ready");
                            callStopReady();
                            break;
                        case PlaybackStateCompat.STATE_NONE:
                        case PlaybackStateCompat.STATE_STOPPED:
                        default:
                            Log.e(LOG_TAG, "ibStopOCL click : wrong playback state");
                            logPlaybackState(mediaCntrlr.getPlaybackState());
                            break;
                    }
                    break;
                case AudioService.SOURCE_NONE:
                default:
                    Log.e(LOG_TAG, "ibStopOCL.onClick() : wrong mediaID : " + mediaID);
                    // tvState.setText(R.string.text_service_conn_err); // redundant
                    break;
            }
        }
    };
}
