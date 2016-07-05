/*
Copyright (c) 2016, Apps4Av Inc. (apps4av.com)
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
    *     * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
    *
    *     THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.ds.avare;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ConfigurationInfo;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.location.GpsStatus;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.ds.avare.adsb.Traffic;
import com.ds.avare.gps.GpsInterface;
import com.ds.avare.gps.GpsParams;
import com.ds.avare.place.Boundaries;
import com.ds.avare.place.Obstacle;
import com.ds.avare.shapes.Tile;
import com.ds.avare.storage.Preferences;
import com.ds.avare.threed.AreaMapper;
import com.ds.avare.threed.TerrainRenderer;
import com.ds.avare.threed.data.Vector4d;
import com.ds.avare.utils.BitmapHolder;
import com.ds.avare.utils.GenericCallback;
import com.ds.avare.utils.Helper;
import com.ds.avare.utils.OptionButton;
import com.ds.avare.views.GlassView;
import com.ds.avare.views.ThreeDSurfaceView;

import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @author zkhan
 */
public class ThreeDActivity extends Activity {

    /**
     * Service that keeps state even when activity is dead
     */
    private StorageService mService;

    /**
     * App preferences
     */
    private Preferences mPref;

    private Toast mToast;

    private Context mContext;

    private AreaMapper mAreaMapper;

    private Button mCenterButton;
    private TextView mText;

    private GlassView mGlassView;


    private Vector4d mObstacles[];

    /**
     * For performing periodic activities.
     */
    private Timer mTimer;
    private UpdateTask mTimerTask;
    private Location mLocation;
    private long mTime;


    /**
     * Hold a reference to our GLSurfaceView
     */
    private ThreeDSurfaceView mGlSurfaceView;
    private TerrainRenderer mRenderer = null;

    private static final int MESSAGE_INIT = 0;
    private static final int MESSAGE_TEXT = 1;
    private static final int MESSAGE_ERROR = 2;
    private static final int MESSAGE_OBSTACLES = 3;
    private static final int MESSAGE_AGL = 4;

    /**
     * GPS calls
     */
    private GpsInterface mGpsInfc = new GpsInterface() {

        @Override
        public void statusCallback(GpsStatus gpsStatus) {
        }

        @Override
        public void locationCallback(Location location) {
            synchronized (ThreeDActivity.this) {
                mLocation = location;
            }
        }

        @Override
        public void timeoutCallback(boolean timeout) {
            if (mPref.isSimulationMode()) {
                Message m = mHandler.obtainMessage();
                m.what = MESSAGE_ERROR;
                m.obj = getString(R.string.SimulationMode);
                mHandler.sendMessage(m);
            } else if (timeout) {
                Message m = mHandler.obtainMessage();
                m.what = MESSAGE_ERROR;
                m.obj = getString(R.string.GPSLost);
                mHandler.sendMessage(m);
            }
        }

        @Override
        public void enabledCallback(boolean enabled) {
        }

    };

    /**
     *
     */
    private void setCenterButton() {
        // Button colors to be synced across activities
        if (mPref.isFirstPerson()) {
            mCenterButton.getBackground().setColorFilter(0xFF00FF00, PorterDuff.Mode.MULTIPLY);
            mToast.setText(getString(R.string.FirstPerson));
            mRenderer.getCamera().setFirstPerson(true);
            mGlSurfaceView.init();
        } else {
            mCenterButton.getBackground().setColorFilter(0xFF444444, PorterDuff.Mode.MULTIPLY);
            mToast.setText(getString(R.string.BirdEye));
            mRenderer.getCamera().setFirstPerson(false);
            mGlSurfaceView.init();
        }
        mToast.show();
    }

    /*
     * (non-Javadoc)
     * @see android.app.Activity#onBackPressed()
     */
    @Override
    public void onBackPressed() {
        ((MainActivity) this.getParent()).showMapTab();
    }

    /* (non-Javadoc)
     * @see android.app.Activity#onCreate(android.os.Bundle)
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {

        Helper.setTheme(this);
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        mPref = new Preferences(this);

        mContext = this;

        /*
         * Create toast beforehand so multiple clicks dont throw up a new toast
         */
        mToast = Toast.makeText(this, "", Toast.LENGTH_SHORT);

        LayoutInflater layoutInflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = layoutInflater.inflate(R.layout.threed, null);
        setContentView(view);

        mGlSurfaceView = (ThreeDSurfaceView) view.findViewById(R.id.threed_surface);

        mGlassView = (GlassView) view.findViewById(R.id.threed_overlay_view);

        // Check if the system supports OpenGL ES 2.0.
        ActivityManager activityManager =
                (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ConfigurationInfo configurationInfo = activityManager
                .getDeviceConfigurationInfo();
        // Even though the latest emulator supports OpenGL ES 2.0,
        // it has a bug where it doesn't set the reqGlEsVersion so
        // the above check doesn't work. The below will detect if the
        // app is running on an emulator, and assume that it supports
        // OpenGL ES 2.0.
        final boolean supportsEs2 =
                configurationInfo.reqGlEsVersion >= 0x20000
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
                        && (Build.FINGERPRINT.startsWith("generic")
                        || Build.FINGERPRINT.startsWith("unknown")
                        || Build.MODEL.contains("google_sdk")
                        || Build.MODEL.contains("Emulator")
                        || Build.MODEL.contains("Android SDK built for x86")));

        if (supportsEs2) {
            // Request an OpenGL ES 2.0 compatible context.
            mGlSurfaceView.setEGLContextClientVersion(2);
            //r,g,b,a,depth,stencil
            mGlSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);

            // Assign our renderer.
            mRenderer = new TerrainRenderer(this, new GenericCallback() {
                @Override
                public Object callback(Object o, Object o1) {

                    /*
                     * This runs in opengl thread context.
                     */
                    if (((String) o1).equals(TerrainRenderer.SURFACE_CREATED)) {
                        // cannot call widgets from opengl thread so handler
                        mHandler.sendEmptyMessage(MESSAGE_INIT);

                        mAreaMapper = new AreaMapper();

                        mTime = System.currentTimeMillis();
                    }
                    else if (((String) o1).equals(TerrainRenderer.DRAW_FRAME)) {

                        // Draw traffic every so many frames
                        if ((System.currentTimeMillis() - 1000) > mTime) {

                            Location location = null;
                            // Simulate destination in sim mode and get altitude from terrain
                            if (mPref.isSimulationMode() && mService != null && mService.getDestination() != null) {
                                Location l = mService.getDestination().getLocation();
                                l.setAltitude(Helper.ALTITUDE_FT_ELEVATION_PER_PIXEL_SLOPE / 2.0 +  // give margin for rounding in chart so we dont go underground
                                        getElevation(l.getLongitude(), l.getLatitude()) / Preferences.heightConversion);
                                location = l;
                            }
                            else {
                                synchronized (ThreeDActivity.this) {
                                    if(mLocation != null) {
                                        location = new Location(mLocation);
                                    }
                                }
                            }

                            if(location != null) {
                                mAreaMapper.setGpsParams(new GpsParams(location));
                                double lon = mAreaMapper.getGpsParams().getLongitude();
                                double lat = mAreaMapper.getGpsParams().getLatitude();
                                double alt = mAreaMapper.getGpsParams().getAltitude();

                                /**
                                 * Elevation here
                                 */
                                double elev = getElevation(lon, lat);
                                if (elev > Helper.ALTITUDE_FT_ELEVATION_PER_PIXEL_INTERCEPT - 1) {
                                    // Write out AGL
                                    Message m = mHandler.obtainMessage();
                                    m.what = MESSAGE_AGL;
                                    m.obj = Math.round(alt - elev) + "ft";
                                    mHandler.sendMessage(m);
                                }

                                Tile tm;
                                Tile te;

                                /*
                                 * Set tiles on new location.
                                 * Match so that elevation and map tiles have common level
                                 */
                                int mZoomM = Tile.getMaxZoom(mContext, mPref.getChartType3D());
                                int mZoomE = Tile.getMaxZoom(mContext, "6");  // 6 is elevation tile index
                                if (mZoomE > mZoomM) {
                                    tm = new Tile(mContext, mPref, lon, lat, 0, mPref.getChartType3D());
                                    te = new Tile(mContext, mPref, lon, lat, mZoomE - mZoomM, "6"); // lower res elev tile
                                }
                                else {
                                    tm = new Tile(mContext, mPref, lon, lat, mZoomM - mZoomE, mPref.getChartType3D()); // lower res map tile
                                    te = new Tile(mContext, mPref, lon, lat, 0, "6");
                                }

                                mAreaMapper.setMapTile(tm);
                                mAreaMapper.setElevationTile(te);

                                if (mAreaMapper.isMapTileNew() || mAreaMapper.isElevationTileNew()) {
                                    Message m = mHandler.obtainMessage();
                                    m.obj = mContext.getString(R.string.LoadingMaps);
                                    m.what = MESSAGE_TEXT;
                                    mHandler.sendMessage(m);

                                    // load tiles but give feedback as it hangs
                                    // Main bitmap used for creating map terrain and
                                    BitmapHolder mainBitmap = new BitmapHolder(BitmapHolder.WIDTH * 2, BitmapHolder.HEIGHT * 2);


                                    Tile tout = mAreaMapper.getMapTile();
                                    loadTiles(tout, mainBitmap);
                                    mRenderer.setTexture(mainBitmap);
                                    tout = mAreaMapper.getElevationTile();
                                    loadTiles(tout, mainBitmap);
                                    mRenderer.setTerrain(mainBitmap, mAreaMapper.getTerrainRatio());
                                    mainBitmap.recycle();

                                    // show errors
                                    m = mHandler.obtainMessage();
                                    m.what = MESSAGE_TEXT;
                                    if (!mRenderer.isMapSet()) {
                                        m.obj = mContext.getString(R.string.MissingElevation);
                                    } else if (!mRenderer.isTextureSet()) {
                                        m.obj = mContext.getString(R.string.MissingMaps);
                                    } else {
                                        m.obj = mContext.getString(R.string.Ready);
                                    }

                                    mHandler.sendMessage(m);
                                }
                            }


                            // Draw traffic
                            Traffic.draw(mService, mAreaMapper, mRenderer);

                            // Draw obstacles
                            if (mObstacles != null && mObstacles.length != 0) {
                                mRenderer.setObstacles(mObstacles);
                            }

                            // Our position
                            mRenderer.setOwnShip(mAreaMapper.getSelfLocation());

                            // For one second run
                            mTime = System.currentTimeMillis();
                        }
                    }


                    // Set orientation
                    mRenderer.getOrientation().set(mGlSurfaceView);
                    // tell renderer that we have new area
                    mRenderer.getCamera().set(mAreaMapper, mRenderer.getOrientation());

                    return null;
                }
            });

            // Set renderer
            mGlSurfaceView.setRenderer(mRenderer);
        } else {
            /*
             * This is where you could create an OpenGL ES 1.x compatible
             * renderer if you wanted to support both ES 1 and ES 2. Since
             * we're not doing anything, the app will crash if the device
             * doesn't support OpenGL ES 2.0. If we publish on the market, we
             * should also add the following to AndroidManifest.xml:
             *
             * <uses-feature android:glEsVersion="0x00020000"
             * android:required="true" />
             *
             * This hides our app from those devices which don't support OpenGL
             * ES 2.0.
             */
            mToast.setText("This device does not support OpenGL ES 2.0.");
            mToast.show();
            return;
        }

        mAreaMapper = new AreaMapper();

        mCenterButton = (Button) view.findViewById(R.id.threed_button_center);
        mCenterButton.getBackground().setAlpha(255);
        mCenterButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                if (mRenderer != null) {
                    // Orientation init
                    mGlSurfaceView.init();
                }
            }

        });

        mCenterButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                // long press on mCenterButton button sets first person toggle
                mPref.setFirstPerson(!mPref.isFirstPerson());
                setCenterButton();
                return true;
            }
        });

        mText = (TextView) view.findViewById(R.id.threed_text);

        // Charts different from main view
        OptionButton chartOption = (OptionButton) view.findViewById(R.id.threed_spinner_chart);
        chartOption.setCallback(new GenericCallback() {
            @Override
            public Object callback(Object o, Object o1) {
                mPref.setChartType3D("" + (int) o1);
                return null;
            }
        });
        chartOption.setOptions(Boundaries.getChartTypes());
        chartOption.setCurrentSelectionIndex(Integer.parseInt(mPref.getChartType3D()));

    }

    /**
     * Make a tile map
     * @param center
     */
    private void loadTiles(Tile center, BitmapHolder bitmap) {

        String path = mPref.mapsFolder() + "/";
        bitmap.getBitmap().eraseColor(0);

        // load tiles
        String name;
        Rect src;
        Rect dst;
        BitmapHolder b;

        // load nine tiles in an arranged bitmap

        // top left
        name = path + center.getTileNeighbor(-1, 1);
        b = new BitmapHolder(name, Bitmap.Config.ARGB_8888);
        src = new Rect(BitmapHolder.WIDTH / 2, BitmapHolder.HEIGHT / 2, BitmapHolder.WIDTH, BitmapHolder.HEIGHT);
        dst = new Rect(0, 0, BitmapHolder.WIDTH / 2, BitmapHolder.HEIGHT / 2);
        bitmap.drawInBitmap(b, "", src, dst);
        b.recycle();

        // top middle
        name = path + center.getTileNeighbor(0, 1);
        b = new BitmapHolder(name, Bitmap.Config.ARGB_8888);
        src = new Rect(0, BitmapHolder.HEIGHT / 2, BitmapHolder.WIDTH, BitmapHolder.HEIGHT);
        dst = new Rect(BitmapHolder.WIDTH / 2, 0, (BitmapHolder.WIDTH * 3) / 2, BitmapHolder.HEIGHT / 2);
        bitmap.drawInBitmap(b, "", src, dst);
        b.recycle();

        // top right
        name = path + center.getTileNeighbor(1, 1);
        b = new BitmapHolder(name, Bitmap.Config.ARGB_8888);
        src = new Rect(0, BitmapHolder.HEIGHT / 2, BitmapHolder.WIDTH / 2, BitmapHolder.HEIGHT);
        dst = new Rect((BitmapHolder.WIDTH * 3) / 2, 0, BitmapHolder.WIDTH * 2, BitmapHolder.HEIGHT / 2);
        bitmap.drawInBitmap(b, "", src, dst);
        b.recycle();

        // middle left
        name = path + center.getTileNeighbor(-1, 0);
        b = new BitmapHolder(name, Bitmap.Config.ARGB_8888);
        src = new Rect(BitmapHolder.WIDTH / 2, 0, BitmapHolder.WIDTH, BitmapHolder.HEIGHT);
        dst = new Rect(0, BitmapHolder.HEIGHT / 2, BitmapHolder.WIDTH / 2, (BitmapHolder.HEIGHT * 3) / 2);
        bitmap.drawInBitmap(b, "", src, dst);
        b.recycle();

        // middle
        name = path + center.getTileNeighbor(0, 0);
        b = new BitmapHolder(name, Bitmap.Config.ARGB_8888);
        src = new Rect(0, 0, BitmapHolder.WIDTH, BitmapHolder.HEIGHT);
        dst = new Rect(BitmapHolder.WIDTH / 2, BitmapHolder.HEIGHT / 2, (BitmapHolder.WIDTH * 3) / 2, (BitmapHolder.HEIGHT * 3) / 2);
        bitmap.drawInBitmap(b, "", src, dst);
        b.recycle();

        // middle right
        name = path + center.getTileNeighbor(1, 0);
        b = new BitmapHolder(name, Bitmap.Config.ARGB_8888);
        src = new Rect(0, 0, BitmapHolder.WIDTH / 2, BitmapHolder.HEIGHT);
        dst = new Rect((BitmapHolder.WIDTH * 3) / 2, BitmapHolder.HEIGHT / 2, BitmapHolder.WIDTH * 2, (BitmapHolder.HEIGHT * 3) / 2);
        bitmap.drawInBitmap(b, "", src, dst);
        b.recycle();

        // bottom left
        name = path + center.getTileNeighbor(-1, -1);
        b = new BitmapHolder(name, Bitmap.Config.ARGB_8888);
        src = new Rect(BitmapHolder.WIDTH / 2, 0, BitmapHolder.WIDTH, BitmapHolder.HEIGHT / 2);
        dst = new Rect(0, (BitmapHolder.HEIGHT * 3) / 2, BitmapHolder.WIDTH / 2, BitmapHolder.HEIGHT * 2);
        bitmap.drawInBitmap(b, "", src, dst);
        b.recycle();

        // bottom middle
        name = path + center.getTileNeighbor(0, -1);
        b = new BitmapHolder(name, Bitmap.Config.ARGB_8888);
        src = new Rect(0, 0, BitmapHolder.WIDTH, BitmapHolder.HEIGHT / 2);
        dst = new Rect(BitmapHolder.WIDTH / 2, (BitmapHolder.HEIGHT * 3) / 2, (BitmapHolder.WIDTH * 3) / 2, BitmapHolder.HEIGHT * 2);
        bitmap.drawInBitmap(b, "", src, dst);
        b.recycle();

        //bottom right
        name = path + center.getTileNeighbor(1, -1);
        b = new BitmapHolder(name, Bitmap.Config.ARGB_8888);
        src = new Rect(0, 0, BitmapHolder.WIDTH / 2, BitmapHolder.HEIGHT / 2);
        dst = new Rect((BitmapHolder.WIDTH * 3) / 2, (BitmapHolder.HEIGHT * 3) / 2, BitmapHolder.WIDTH * 2, BitmapHolder.HEIGHT * 2);
        bitmap.drawInBitmap(b, "", src, dst);
        b.recycle();

    }

    /** Defines callbacks for service binding, passed to bindService() */
    /**
     *
     */
    private ServiceConnection mConnection = new ServiceConnection() {

        /* (non-Javadoc)
         * @see android.content.ServiceConnection#onServiceConnected(android.content.ComponentName, android.os.IBinder)
         */
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {

            /*
             * We've bound to LocalService, cast the IBinder and get LocalService instance
             */
            StorageService.LocalBinder binder = (StorageService.LocalBinder) service;
            mService = binder.getService();
            mService.registerGpsListener(mGpsInfc);
            mGlassView.setService(mService);
        }

        /* (non-Javadoc)
         * @see android.content.ServiceConnection#onServiceDisconnected(android.content.ComponentName)
         */
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
        }
    };

    /**
     * Get elevation at this location
     * @return
     */
    private double getElevation(double lon, double lat) {
        int x = (int)mAreaMapper.getXForLon(lon) + BitmapHolder.WIDTH / 2;
        int y = (int)mAreaMapper.getYForLat(lat) + BitmapHolder.HEIGHT / 2;
        // Find from Map
        double elev = mRenderer.getElevationNormalized(y, x, mAreaMapper.getTerrainRatio());
        if(elev <= -1) {
            return Helper.ALTITUDE_FT_ELEVATION_PER_PIXEL_INTERCEPT - 1;
        }

        return Helper.findElevationFromNormalizedElevation(elev);
    }


    /* (non-Javadoc)
     * @see android.app.Activity#onStart()
     */
    @Override
    protected void onStart() {
        super.onStart();
    }

    /* (non-Javadoc)
     * @see android.app.Activity#onResume()
     */
    @Override
    public void onResume() {
        super.onResume();
        Helper.setOrientationAndOn(this);

        // Clean messages
        mText.setText("");
        mGlassView.setStatus(null);
        mGlassView.setAgl("");

        /*
         * Registering our receiver
         * Bind now.
         */
        Intent intent = new Intent(this, StorageService.class);
        getApplicationContext().bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        if (mRenderer != null) {
            mGlSurfaceView.onResume();
        }

        // Periodic not time critical activities
        mTimer = new Timer();
        mTimerTask = new UpdateTask();
        mTimer.schedule(mTimerTask, 0, 1000);

    }

    /* (non-Javadoc)
     * @see android.app.Activity#onPause()
     */
    @Override
    protected void onPause() {
        super.onPause();

        if (null != mService) {
            mService.unregisterGpsListener(mGpsInfc);
        }

        /*
         * Clean up on pause that was started in on resume
         */
        getApplicationContext().unbindService(mConnection);

        if (mRenderer != null) {
            mGlSurfaceView.onPause();
        }
        mTimer.cancel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MESSAGE_INIT) {
                setCenterButton();
            }
            else if (msg.what == MESSAGE_TEXT) {
                mText.setText((String) msg.obj);
            }
            else if (msg.what == MESSAGE_ERROR) {
                mGlassView.setStatus((String) msg.obj);
            }
            else if (msg.what == MESSAGE_OBSTACLES) {
                mObstacles = (Vector4d[]) msg.obj;
            }
            else if (msg.what == MESSAGE_AGL) {
                mGlassView.setAgl((String) msg.obj);
            }
        }
    };

    /**
     * Do stuff in background
     */
    private class UpdateTask extends TimerTask {

        @Override
        public void run() {

            Thread.currentThread().setName("Background");

            if (null == mService || null == mService.getDBResource() || mAreaMapper == null || mAreaMapper.getGpsParams() == null) {
                return;
            }
            LinkedList<Obstacle> obs = null;
            obs = mService.getDBResource().findObstacles(mAreaMapper.getGpsParams().getLongitude(),
                    mAreaMapper.getGpsParams().getLatitude(), 0);

            Vector4d obstacles[] = new Vector4d[obs.size()];
            int count = 0;
            for (Obstacle ob : obs) {
                obstacles[count++] = mAreaMapper.gpsToAxis(ob.getLongitude(), ob.getLatitude(), ob.getHeight(), 0);
            }
            Message m = mHandler.obtainMessage();
            m.what = MESSAGE_OBSTACLES;
            m.obj = obstacles;
            mHandler.sendMessage(m);
        }
    }
}