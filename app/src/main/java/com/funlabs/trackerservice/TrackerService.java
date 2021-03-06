package com.funlabs.trackerservice;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.gcm.GcmNetworkManager;
import com.google.android.gms.gcm.OneoffTask;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;

import java.io.File;
import java.io.FileWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class TrackerService extends Service implements LocationListener {

    public static final String STATUS_INTENT = "status";
    private static final String TAG = TrackerService.class.getSimpleName();
    private static final int NOTIFICATION_ID = 1;
    private static final int FOREGROUND_SERVICE_ID = 1;
    private static final int CONFIG_CACHE_EXPIRY = 600;  // 10 minutes.
    private static double previousLat = 0.0;
    private static double previousLng = 0.0;
    private static long previousTime = 0;

    private GoogleApiClient mGoogleApiClient;
    private DatabaseReference mFirebaseTransportMovementRef;
    private DatabaseReference mFirebaseTransportMoveHistoryRef;
    private FirebaseRemoteConfig mFirebaseRemoteConfig;
    private NotificationManager mNotificationManager;
    private NotificationCompat.Builder mNotificationBuilder;
    private PowerManager.WakeLock mWakelock;

    private SharedPreferences mPrefs;
    private GoogleApiClient.ConnectionCallbacks mLocationRequestCallback = new GoogleApiClient
            .ConnectionCallbacks() {

        @SuppressLint({"InvalidWakeLockTag", "MissingPermission"})
        @Override
        public void onConnected(Bundle bundle) {
            LocationRequest request = new LocationRequest();
            request.setInterval(mFirebaseRemoteConfig.getLong("LOCATION_REQUEST_INTERVAL"));
            request.setFastestInterval(mFirebaseRemoteConfig.getLong
                    ("LOCATION_REQUEST_INTERVAL_FASTEST"));
            request.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient,
                    request, TrackerService.this);
            setStatusMessage(R.string.tracking);

            // Hold a partial wake lock to keep CPU awake when the we're tracking location.
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            mWakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyWakelockTag");
            mWakelock.acquire();
        }

        @Override
        public void onConnectionSuspended(int reason) {
            // TODO: Handle gracefully
        }
    };

    public TrackerService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        buildNotification();
        setStatusMessage(R.string.connecting);

        mFirebaseRemoteConfig = FirebaseRemoteConfig.getInstance();
        FirebaseRemoteConfigSettings configSettings = new FirebaseRemoteConfigSettings.Builder()
                .setDeveloperModeEnabled(BuildConfig.DEBUG)
                .build();
        mFirebaseRemoteConfig.setConfigSettings(configSettings);
        mFirebaseRemoteConfig.setDefaults(R.xml.remote_config_defaults);

        mPrefs = getSharedPreferences(getString(R.string.prefs), MODE_PRIVATE);
        String email = mPrefs.getString(getString(R.string.email), "");
        String password = mPrefs.getString(getString(R.string.password), "");
        authenticate(email, password);
    }

    @Override
    public void onDestroy() {
        // Set activity title to not tracking.
        setStatusMessage(R.string.not_tracking);
        // Stop the persistent notification.
        mNotificationManager.cancel(NOTIFICATION_ID);
        // Stop receiving location updates.
        if (mGoogleApiClient != null) {
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient,
                    TrackerService.this);
        }
        // Release the wakelock
        if (mWakelock != null) {
            mWakelock.release();
        }
        super.onDestroy();
    }

    private void authenticate(String email, String password) {
        final FirebaseAuth mAuth = FirebaseAuth.getInstance();
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(Task<AuthResult> task) {
                        Log.i(TAG, "authenticate: " + task.isSuccessful());
                        if (task.isSuccessful()) {
                            SharedPreferences.Editor editor = mPrefs.edit();
                            editor.putString(getString(R.string.uid), task.getResult().getUser().getUid().toString());
                            editor.apply();
                            fetchRemoteConfig();
                            initiateTracking();
                        } else {
                            Toast.makeText(TrackerService.this, R.string.auth_failed,
                                    Toast.LENGTH_SHORT).show();
                            stopSelf();
                        }
                    }
                });
    }

    private void fetchRemoteConfig() {
        long cacheExpiration = CONFIG_CACHE_EXPIRY;
        if (mFirebaseRemoteConfig.getInfo().getConfigSettings().isDeveloperModeEnabled()) {
            cacheExpiration = 0;
        }
        mFirebaseRemoteConfig.fetch(cacheExpiration)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Log.i(TAG, "Remote config fetched");
                        mFirebaseRemoteConfig.activateFetched();
                    }
                });
    }

    private void initiateTracking() {
        previousLat = 0.0;
        previousLat = 0.0;
        previousTime = 0;
        String transportId = mPrefs.getString(getString(R.string.transport_id), "");
        String uid = mPrefs.getString(getString(R.string.uid), "");
        FirebaseAnalytics.getInstance(this).setUserProperty("transportID", transportId);
        String movementPath = getString(R.string.firebase_movement_path) + uid + "/" + transportId;
        mFirebaseTransportMovementRef = FirebaseDatabase.getInstance().getReference(movementPath);
        String movementHisPath = getString(R.string.firebase_movement_his_path) + uid + "/" + transportId;
        mFirebaseTransportMoveHistoryRef = FirebaseDatabase.getInstance().getReference(movementHisPath);
        startLocationTracking();
    }

    /**
     * Starts location tracking by creating a Google API client, and
     * requesting location updates.
     */
    private void startLocationTracking() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(mLocationRequestCallback)
                .addApi(LocationServices.API)
                .build();
        mGoogleApiClient.connect();
    }

    private float distanceTo(Location location) {
        Location locationForStatus = new Location("");
        locationForStatus.setLatitude(previousLat);
        locationForStatus.setLongitude(previousLng);
        float distance = location.distanceTo(locationForStatus);
        return distance;
    }

    private float getBatteryLevel() {
        Intent batteryStatus = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int batteryLevel = -1;
        int batteryScale = 1;
        if (batteryStatus != null) {
            batteryLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, batteryLevel);
            batteryScale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, batteryScale);
        }
        return batteryLevel / (float) batteryScale * 100;
    }

    private void logStatusToStorage(Map<String, Object> transportStatus) {
        try {
            File path = new File(Environment.getExternalStoragePublicDirectory(""),
                    "transport-tracker-log.txt");
            if (!path.exists()) {
                path.createNewFile();
            }
            FileWriter logFile = new FileWriter(path.getAbsolutePath(), true);
            logFile.append(transportStatus.toString() + "\n");
            logFile.close();
        } catch (Exception e) {
            Log.e(TAG, "Log file error", e);
        }
    }

    private void shutdownAndScheduleStartup(int when) {
        Log.i(TAG, "overnight shutdown, seconds to startup: " + when);
        com.google.android.gms.gcm.Task task = new OneoffTask.Builder()
                .setService(TrackerTaskService.class)
                .setExecutionWindow(when, when + 60)
                .setUpdateCurrent(true)
                .setTag(TrackerTaskService.TAG)
                .setRequiredNetwork(com.google.android.gms.gcm.Task.NETWORK_STATE_ANY)
                .setRequiresCharging(false)
                .build();
        GcmNetworkManager.getInstance(this).schedule(task);
        stopSelf();
    }

    /**
     * Pushes a new status to Firebase when location changes.
     */
    @Override
    public void onLocationChanged(Location location) {
        fetchRemoteConfig();

        Toast.makeText(getApplicationContext(), "Acc :" + String.valueOf(location.getAccuracy()) +
                " Speed :" + location.getSpeed(), Toast.LENGTH_SHORT).show();

       /* long hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int startupSeconds = (int) (mFirebaseRemoteConfig.getDouble("SLEEP_HOURS_DURATION") * 3600);
        if (hour == mFirebaseRemoteConfig.getLong("SLEEP_HOUR_OF_DAY")) {
            shutdownAndScheduleStartup(startupSeconds);
            return;
        }*/

        Map<String, Object> transportStatus = new HashMap<>();
        transportStatus.put("lat", location.getLatitude());
        transportStatus.put("lng", location.getLongitude());
        transportStatus.put("time", new Date().getTime());
        transportStatus.put("power", getBatteryLevel());
        transportStatus.put("bearing", location.getBearing());
        transportStatus.put("speed", location.getSpeed());
        transportStatus.put("calspeed", 0);
        transportStatus.put("distance", 0);

        float distance = 0.0f;
        double speed_kph = 0;
        if (previousLat != 0.0 && previousLng != 0.0 && previousTime != 0) {
            distance = distanceTo(location);
            if (distance < 500) { // 500 meter
                double time_s = (location.getTime() - previousTime) / 1000.0;
                double speed_mps = distance / time_s;
                speed_kph = (speed_mps * 3600.0) / 1000.0;
                transportStatus.put("calspeed", speed_kph);
                transportStatus.put("distance", distance);
            }
            if (location.getBearing() <= 0) {
                transportStatus.put("bearing", bearing(location));
            }
            Toast.makeText(getApplicationContext(), "Dis :" + String.valueOf(distance) +
                    " Speed Calc:" + speed_kph + " Bear: " + bearing(location), Toast.LENGTH_SHORT).show();
        }
        previousTime = location.getTime();
        previousLat = location.getLatitude();
        previousLng = location.getLongitude();

        if (location.hasAccuracy()) {
            if (location.getAccuracy() <= 20.0f) {

                if (distance >= 20.0 && speed_kph > 10) {
                    if (speed_kph >= 120) {
                        transportStatus.put("calspeed", 120);
                    }
                    transportStatus.put("motion", "RUNNING");

                } else {
                    if (speed_kph <= 10) {
                        transportStatus.put("motion", "PARKED");
                    }
                }
                mFirebaseTransportMovementRef.child("0").setValue(transportStatus);
                mFirebaseTransportMoveHistoryRef.push().setValue(transportStatus);
            }
        } else {
            mFirebaseTransportMovementRef.child("0").setValue(transportStatus);
            mFirebaseTransportMoveHistoryRef.push().setValue(transportStatus);
        }

        if (BuildConfig.DEBUG) {
            logStatusToStorage(transportStatus);
        }

        NetworkInfo info = ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE))
                .getActiveNetworkInfo();
        boolean connected = info != null && info.isConnectedOrConnecting();
        setStatusMessage(connected ? R.string.tracking : R.string.not_tracking);
    }

    @SuppressLint("NewApi")
    private void buildNotification() {
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        PendingIntent resultPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, TrackerActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        mNotificationBuilder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.bus_white)
                .setColor(getColor(R.color.colorPrimary))
                .setContentTitle(getString(R.string.app_name))
                .setOngoing(true)
                .setContentIntent(resultPendingIntent);
        startForeground(FOREGROUND_SERVICE_ID, mNotificationBuilder.build());
    }

    /**
     * Sets the current status message (connecting/tracking/not tracking).
     */
    private void setStatusMessage(int stringId) {

        mNotificationBuilder.setContentText(getString(stringId));
        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());

        // Also display the status message in the activity.
        Intent intent = new Intent(STATUS_INTENT);
        intent.putExtra(getString(R.string.status), stringId);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }


    private double bearing(Location location) {
        double dLon = toRad(location.getLongitude() - previousLng);
        double y = Math.sin(dLon) * Math.cos(this.toRad(location.getLatitude()));
        double x = Math.cos(this.toRad(previousLat)) * Math.sin(this.toRad(location.getLatitude())) -
                Math.sin(this.toRad(previousLat)) * Math.cos(this.toRad(location.getLatitude())) * Math.cos(dLon);
        double brng = this.toDeg(Math.atan2(y, x));
        return ((brng + 360) % 360);
    }

    private double toRad(double deg) {
        return deg * Math.PI / 180;
    }

    private double toDeg(double rad) {
        return rad * 180 / Math.PI;
    }
}
