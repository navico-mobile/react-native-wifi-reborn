package com.reactlibrary.rnwifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.PatternMatcher;
import android.text.format.Formatter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.uimanager.IllegalViewOperationException;
import com.reactlibrary.utils.LocationUtils;
import com.reactlibrary.utils.PermissionUtils;
import com.thanosfisherman.wifiutils.WifiUtils;
import com.thanosfisherman.wifiutils.wifiConnect.ConnectionSuccessListener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class RNWifiModule extends ReactContextBaseJavaModule {
    private final WifiManager wifi;
    private final ReactApplicationContext context;
    private final String TAG= "RNWifi";
    private ConnectivityManager.NetworkCallback networkCallback = null;

    RNWifiModule(ReactApplicationContext context) {
        super(context);

        // TODO: get when needed
        wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.context = context;
    }

    @Override
    public String getName() {
        return "WifiManager";
    }

    /**
     * Method to load wifi list into string via Callback. Returns a stringified JSONArray
     *
     * @param successCallback
     * @param errorCallback
     */
    @ReactMethod
    public void loadWifiList(Callback successCallback, Callback errorCallback) {
        try {
            List<ScanResult> results = wifi.getScanResults();
            JSONArray wifiArray = new JSONArray();

            for (ScanResult result : results) {
                JSONObject wifiObject = new JSONObject();
                if (!result.SSID.equals("")) {
                    try {
                        wifiObject.put("SSID", result.SSID);
                        wifiObject.put("BSSID", result.BSSID);
                        wifiObject.put("capabilities", result.capabilities);
                        wifiObject.put("frequency", result.frequency);
                        wifiObject.put("level", result.level);
                        wifiObject.put("timestamp", result.timestamp);
                    } catch (JSONException e) {
                        errorCallback.invoke(e.getMessage());
                    }
                    wifiArray.put(wifiObject);
                }
            }
            successCallback.invoke(wifiArray.toString());
        } catch (IllegalViewOperationException e) {
            errorCallback.invoke(e.getMessage());
        }
    }

    /**
     * Use this to execute api calls to a wifi network that does not have internet access.
     *
     * Useful for commissioning IoT devices.
     *
     * This will route all app network requests to the network (instead of the mobile connection).
     * It is important to disable it again after using as even when the app disconnects from the wifi
     * network it will keep on routing everything to wifi.
     *
     * @param useWifi boolean to force wifi off or on
     */
    @ReactMethod
    public void forceWifiUsage(final boolean useWifi, final Promise promise) {
        final ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager == null) {
            promise.reject(ForceWifiUsageErrorCodes.couldNotGetConnectivityManager.toString(), "Failed to get the ConnectivityManager.");
            return;
        }

        if (useWifi) {
            NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            connectivityManager.requestNetwork(networkRequest, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull final Network network) {
                    super.onAvailable(network);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        connectivityManager.bindProcessToNetwork(network);
                    } else {
                        ConnectivityManager.setProcessDefaultNetwork(network);
                    }

                    connectivityManager.unregisterNetworkCallback(this);

                    promise.resolve(null);
                }
            });
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivityManager.bindProcessToNetwork(null);
            } else {
                ConnectivityManager.setProcessDefaultNetwork(null);
            }

            promise.resolve(null);
        }
    }

    /**
     * Method to check if wifi is enabled
     *
     * @param isEnabled
     */
    @ReactMethod
    public void isEnabled(Callback isEnabled) {
        isEnabled.invoke(wifi.isWifiEnabled());
    }

    /**
     * Method to connect/disconnect wifi service
     *
     * @param enabled
     */
    @ReactMethod
    public void setEnabled(Boolean enabled) {
        wifi.setWifiEnabled(enabled);
    }

    public void verifyNetworkSwitched(final String SSID, final Promise promise){
      Log.d(TAG, "verifyNetworkSwitched");

      // Timeout if there is no other saved WiFi network reachable
      ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1);

      // Verify the connection
      final IntentFilter intentFilter = new IntentFilter();
      intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
      final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
          final NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
          if (info != null && info.isConnected()) {
            final WifiInfo wifiInfo = wifi.getConnectionInfo();
            String ssid = wifiInfo.getSSID();
            // This value should be wrapped in double quotes, so we need to unwrap it.
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
              ssid = ssid.substring(1, ssid.length() - 1);
            }
            Log.d(TAG, "connect to "+ssid);

            context.unregisterReceiver(this);
            exec.shutdownNow();
            if (ssid.equals(SSID)) {
              final String routerIP = Formatter.formatIpAddress(wifi.getDhcpInfo().gateway);
              final String localIP = Formatter.formatIpAddress(wifi.getDhcpInfo().ipAddress);
              Log.d(TAG, String.format("Network %s ip %s router %s", SSID, localIP,routerIP));
              promise.resolve(null);
            }
            else {
              promise.reject("connectNetworkFailed", String.format("Could not connect to network with SSID: %s", SSID));
            }
          }
        }
      };
      exec.schedule(new Runnable() {
        public void run() {
          Log.d(TAG, "timeout");
          context.unregisterReceiver(receiver);//make sure promise only resolve once
          promise.reject("connectNetworkFailed", String.format("Timeout connecting to network with SSID: %s", SSID));
        }
      }, 10, TimeUnit.SECONDS);
      context.registerReceiver(receiver, intentFilter);
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void androidQConnectToProtectedSSID(@NonNull final String SSID, @NonNull final String password, final boolean isWep, final Promise promise) {
      Log.d(TAG, String.format("call androidQConnectToProtectedSSID with %s %s", SSID, password));

      final NetworkSpecifier specifier =
        new WifiNetworkSpecifier.Builder().setSsid(SSID).setWpa2Passphrase(password)
          .build();

      final NetworkRequest request =
        new NetworkRequest.Builder()
          .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
          .setNetworkSpecifier(specifier)
          .build();

      final ConnectivityManager connectivityManager = (ConnectivityManager)
        context.getSystemService(Context.CONNECTIVITY_SERVICE);

      if(connectivityManager == null){
        Log.d(TAG, "Can not get ConnectivityManager");
        promise.reject("failed", "Can not get ConnectivityManager");
        return;
      }

      networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
          super.onAvailable(network);

          Log.d(TAG, String.format("AndroidQ+ request to wifi %s",network.toString()));
          boolean binded = connectivityManager.bindProcessToNetwork(network);
          Log.d(TAG, String.format("AndroidQ+ bind to wifi %b", binded));

          verifyNetworkSwitched(SSID, promise);
        }

        @Override
        public void onUnavailable() {
          super.onUnavailable();

          Log.d(TAG, "AndroidQ+ could not connect to wifi");
          promise.reject("failed", "AndroidQ+ could not connect to wifi");

        }
      };
      connectivityManager.requestNetwork(request, networkCallback);
    }

    /**
     * Use this to connect with a wifi network.
     * Example:  wifi.findAndConnect(ssid, password, false);
     * The promise will resolve with the message 'connected' when the user is connected on Android.
     *
     * @param SSID     name of the network to connect with
     * @param password password of the network to connect with
     * @param isWep    only for iOS
     * @param promise  to send success/error feedback
     */
    @ReactMethod
    public void connectToProtectedSSID(@NonNull final String SSID, @NonNull final String password, final boolean isWep, final Promise promise) {
        final boolean locationPermissionGranted = PermissionUtils.isLocationPermissionGranted(context);
        if (!locationPermissionGranted) {
            promise.reject("location permission missing", "Location permission is not granted");
            return;
        }

        final boolean isLocationOn = LocationUtils.isLocationOn(context);
        if (!isLocationOn) {
            promise.reject("location off", "Location service is turned off");
            return;
        }
        if(isAndroid10OrLater()){
          Log.d(TAG,"androidQConnectToProtectedSSID");
          androidQConnectToProtectedSSID(SSID,password,isWep,promise);
          return;
        }
        WifiUtils.enableLog(true);
        WifiUtils.withContext(context).connectWith(SSID, password).onConnectionResult(new ConnectionSuccessListener() {
            @Override
            public void isSuccessful(boolean isSuccess) {
                if (isSuccess) {
                    promise.resolve("connected");
                } else {
                    promise.reject("failed", "Could not connect to network");
                }
            }
        }).start();

    }

    /**
     * Use this method to check if the device is currently connected to Wifi.
     *
     * @param connectionStatusResult
     */
    @ReactMethod
    public void connectionStatus(Callback connectionStatusResult) {
        ConnectivityManager connManager = (ConnectivityManager) getReactApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        if (mWifi.isConnected()) {
            connectionStatusResult.invoke(true);
        } else {
            connectionStatusResult.invoke(false);
        }
    }

    /**
     * Disconnect current Wifi.
     */
    @ReactMethod
    public void disconnect() {
      if(isAndroid10OrLater()){
        ConnectivityManager connManager = (ConnectivityManager) getReactApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if(connManager!=null) {
          if(networkCallback!=null) {
            Log.d(TAG,"unregisterNetworkCallback");
            connManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
          }
          Log.d(TAG,"bindProcessToNetwork to null");
          connManager.bindProcessToNetwork(null);
        }
      }else{
        wifi.disconnect();
      }
    }

    /**
     * This method will return current SSID
     *
     * @param promise
     */
    @ReactMethod
    public void getCurrentWifiSSID(final Promise promise) {
        WifiInfo info = wifi.getConnectionInfo();

        // This value should be wrapped in double quotes, so we need to unwrap it.
        String ssid = info.getSSID();
        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }

        promise.resolve(ssid);
    }

    /**
     * ]
     * This method will return the basic service set identifier (BSSID) of the current access point
     *
     * @param callback
     */
    @ReactMethod
    public void getBSSID(final Callback callback) {
        WifiInfo info = wifi.getConnectionInfo();

        String bssid = info.getBSSID();

        callback.invoke(bssid.toUpperCase());
    }

    /**
     * This method will return current wifi signal strength
     *
     * @param callback
     */
    @ReactMethod
    public void getCurrentSignalStrength(final Callback callback) {
        int linkSpeed = wifi.getConnectionInfo().getRssi();
        callback.invoke(linkSpeed);
    }

    /**
     * This method will return current wifi frequency
     *
     * @param callback
     */
    @ReactMethod
    public void getFrequency(final Callback callback) {
        WifiInfo info = wifi.getConnectionInfo();
        int frequency = info.getFrequency();
        callback.invoke(frequency);
    }

    /**
     * This method will return current IP
     *
     * @param callback
     */
    @ReactMethod
    public void getIP(final Callback callback) {
        WifiInfo info = wifi.getConnectionInfo();
        String stringIP = longToIP(info.getIpAddress());
        callback.invoke(stringIP);
    }

    /**
     * This method will remove the wifi network as per the passed SSID from the device list
     *
     * @param ssid
     * @param promise true means the ssid has been removed or did not existed in configured network list
     *                false means the ssid removed failed.
     */
    @ReactMethod
    public void isRemoveWifiNetwork(String ssid, final Promise promise) {
        List<WifiConfiguration> mWifiConfigList = wifi.getConfiguredNetworks();
        for (WifiConfiguration wifiConfig : mWifiConfigList) {
            String comparableSSID = ('"' + ssid + '"'); //Add quotes because wifiConfig.SSID has them
            if (wifiConfig.SSID.equals(comparableSSID)) {
                boolean success = wifi.removeNetwork(wifiConfig.networkId);
                wifi.saveConfiguration();
                promise.resolve(success);
                return;
            }
        }
        promise.resolve(true);
    }

    /**
     * This method will check if the Location service is on
     *
     * @param promise true means the location service is on
     *                false means the location service is off
     */
    @ReactMethod
    public void isLocationServiceOn(final Promise promise) {
      LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
      boolean gps_enabled = false;
      boolean network_enabled = false;
      if(lm==null){
        promise.reject("isLocationServiceOnFailed","can not get location manager");
        return;
      }
      try {
        gps_enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        network_enabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
      } catch (SecurityException e) {
        promise.reject(e);
      }

      promise.resolve(gps_enabled || network_enabled);
    }
    /**
     * This method is similar to `loadWifiList` but it forcefully starts the wifi scanning on android and in the callback fetches the list
     *
     * @param successCallback
     * @param errorCallback
     */
    @ReactMethod
    public void reScanAndLoadWifiList(Callback successCallback, Callback errorCallback) {
        WifiReceiver receiverWifi = new WifiReceiver(wifi, successCallback, errorCallback);
        getReactApplicationContext().registerReceiver(receiverWifi, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        wifi.startScan();
    }

    private static String longToIP(int longIp) {
        StringBuilder sb = new StringBuilder();
        String[] strip = new String[4];
        strip[3] = String.valueOf((longIp >>> 24));
        strip[2] = String.valueOf((longIp & 0x00FFFFFF) >>> 16);
        strip[1] = String.valueOf((longIp & 0x0000FFFF) >>> 8);
        strip[0] = String.valueOf((longIp & 0x000000FF));
        sb.append(strip[0]);
        sb.append(".");
        sb.append(strip[1]);
        sb.append(".");
        sb.append(strip[2]);
        sb.append(".");
        sb.append(strip[3]);
        return sb.toString();
    }

    class WifiReceiver extends BroadcastReceiver {

        private final Callback successCallback;
        private final Callback errorCallback;
        private final WifiManager wifi;

        public WifiReceiver(final WifiManager wifi, Callback successCallback, Callback errorCallback) {
            super();
            this.successCallback = successCallback;
            this.errorCallback = errorCallback;
            this.wifi = wifi;
        }

        // This method call when number of wifi connections changed
        public void onReceive(Context c, Intent intent) {
            c.unregisterReceiver(this);
            try {
                List<ScanResult> results = this.wifi.getScanResults();
                JSONArray wifiArray = new JSONArray();

                for (ScanResult result : results) {
                    JSONObject wifiObject = new JSONObject();
                    if (!result.SSID.equals("")) {
                        try {
                            wifiObject.put("SSID", result.SSID);
                            wifiObject.put("BSSID", result.BSSID);
                            wifiObject.put("capabilities", result.capabilities);
                            wifiObject.put("frequency", result.frequency);
                            wifiObject.put("level", result.level);
                            wifiObject.put("timestamp", result.timestamp);
                        } catch (JSONException e) {
                            this.errorCallback.invoke(e.getMessage());
                            return;
                        }
                        wifiArray.put(wifiObject);
                    }
                }
                this.successCallback.invoke(wifiArray.toString());
            } catch (IllegalViewOperationException e) {
                this.errorCallback.invoke(e.getMessage());
            }
        }
    }

    private static String formatWithBackslashes(final String value) {
        return String.format("\"%s\"", value);
    }

    /**
     * @return true if the current sdk is above or equal to Android M
     */
    private static boolean isAndroidLollipopOrLater() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    /**
     * @return true if the current sdk is above or equal to Android Q
     */
    private static boolean isAndroid10OrLater() {
         return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }
}
