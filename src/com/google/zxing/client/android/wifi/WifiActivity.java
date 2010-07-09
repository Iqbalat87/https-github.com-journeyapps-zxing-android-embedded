/*
 * Copyright (C) 2010 ZXing authors
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

package com.google.zxing.client.android.wifi;

import java.util.List;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.google.zxing.client.android.Intents;
import com.google.zxing.client.android.R;

/**
 * A new activity showing the progress of Wifi connection
 * 
 * @author Vikram Aggarwal
 */
public class WifiActivity extends Activity  {

  private static final String TAG = WifiActivity.class.getSimpleName();
  private WifiManager wifiManager;
  private TextView statusView;
  private WifiReceiver wifiReceiver;
  private boolean receiverRegistered;
  private int networkId;
  private static int errorCount;
  private IntentFilter mWifiStateFilter;

  static {
    errorCount = 0;
  }

  public void gotError(){
    final int maxErrorCount = 3;
    errorCount++;
    Log.d(TAG, "Encountered another error.  Errorcount = " + errorCount);
    if (errorCount > maxErrorCount){
      errorCount = 0;
      doError(R.string.wifi_connect_failed);
    }
  }

  public enum NetworkType {
    NETWORK_WEP, NETWORK_WPA, NETWORK_NOPASS, NETWORK_INVALID,
  }

  private int changeNetwork(NetworkSetting setting) {
    // If the SSID is empty, throw an error and return
    if (setting.getSsid() == null || setting.getSsid().length() == 0) {
      return doError(R.string.wifi_ssid_missing);
    }
    // If the network type is invalid
    if (setting.getNetworkType() == NetworkType.NETWORK_INVALID){
      return doError(R.string.wifi_type_incorrect);
    }

    // If the password is empty, this is an unencrypted network
    if (setting.getPassword() == null || setting.getPassword().length() == 0 ||
        setting.getNetworkType() == null ||
        setting.getNetworkType() == NetworkType.NETWORK_NOPASS) {
      return changeNetworkUnEncrypted(setting);
    }
    if (setting.getNetworkType() == NetworkType.NETWORK_WPA) {
      return changeNetworkWPA(setting);
    } else {
      return changeNetworkWEP(setting);
    }
  }

  private int doError(int resource_string) {
    statusView.setText(resource_string);
    // Give up on the connection
    wifiManager.disconnect();
    if (networkId > 0) {
      wifiManager.removeNetwork(networkId);
      networkId = -1;
    }
    if (receiverRegistered) {
      unregisterReceiver(wifiReceiver);
      receiverRegistered = false;
    }
    return -1;
  }

  private WifiConfiguration changeNetworkCommon(NetworkSetting input){
    statusView.setText(R.string.wifi_creating_network);
    Log.d(TAG, "Adding new configuration: \nSSID: " + input.getSsid() + "\nType: " + input.getNetworkType());
    final WifiConfiguration config = new WifiConfiguration();

    config.allowedAuthAlgorithms.clear();
    config.allowedGroupCiphers.clear();
    config.allowedKeyManagement.clear();
    config.allowedPairwiseCiphers.clear();
    config.allowedProtocols.clear();

    // Android API insists that an ascii SSID must be quoted to be correctly handled.
    config.SSID = NetworkUtil.convertToQuotedString(input.getSsid());
    config.hiddenSSID = true;
    return config;
  }

  private int requestNetworkChange(WifiConfiguration config){
    statusView.setText(R.string.wifi_changing_network);
    return updateNetwork(config, false);
  }

  // Adding a WEP network
  private int changeNetworkWEP(NetworkSetting input) {
    final WifiConfiguration config = changeNetworkCommon(input);
    final String pass = input.getPassword();
    if (NetworkUtil.isHexWepKey(pass)) {
      config.wepKeys[0] = pass;
    } else {
      config.wepKeys[0] = NetworkUtil.convertToQuotedString(pass);
    }
    config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
    config.wepTxKeyIndex = 0;
    return requestNetworkChange(config);
  }

  // Adding a WPA or WPA2 network
  private int changeNetworkWPA(NetworkSetting input) {
    final WifiConfiguration config = changeNetworkCommon(input);
    final String pass = input.getPassword();
    // Hex passwords that are 64 bits long are not to be quoted.
    if (pass.matches("[0-9A-Fa-f]{64}")){
      Log.d(TAG, "A 64 bit hex password entered.");
      config.preSharedKey = pass;
    } else {
      Log.d(TAG, "A normal password entered: I am quoting it.");
      config.preSharedKey = NetworkUtil.convertToQuotedString(pass);
    }
    config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
    // For WPA
    config.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
    // For WPA2
    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
    config.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
    return requestNetworkChange(config);
  }

  // Adding an open, unsecured network
  private int changeNetworkUnEncrypted(NetworkSetting input){
    Log.d(TAG, "Empty password prompting a simple account setting");
    WifiConfiguration config = changeNetworkCommon(input);
    config.wepKeys[0] = "";
    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
    config.wepTxKeyIndex = 0;
    return requestNetworkChange(config);
  }

  /**
   * If the given ssid name exists in the settings, then change its password to the one given here, and save
   * @param ssid
   */
  private WifiConfiguration findNetworkInExistingConfig(String ssid){
    final List <WifiConfiguration> existingConfigs = wifiManager.getConfiguredNetworks();
    for (final WifiConfiguration existingConfig : existingConfigs) {
      if (existingConfig.SSID.equals(ssid)) {
        return existingConfig;
      }
    }
    return null;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final Intent intent = getIntent();
    if (intent == null || (!intent.getAction().equals(Intents.WifiConnect.ACTION))) {
      finish();
      return;
    }

    final String ssid = intent.getStringExtra(Intents.WifiConnect.SSID);
    String password = intent.getStringExtra(Intents.WifiConnect.PASSWORD);
    final String networkType = intent.getStringExtra(Intents.WifiConnect.TYPE);
    setContentView(R.layout.network);
    statusView = (TextView) findViewById(R.id.networkStatus);

    NetworkType networkT;
    if (networkType.equals("WPA")) {
      networkT = NetworkType.NETWORK_WPA;
    } else if (networkType.equals("WEP")) {
      networkT = NetworkType.NETWORK_WEP;
    } else if (networkType.equals("nopass")) {
     networkT = NetworkType.NETWORK_NOPASS;
    } else {
      doError(R.string.wifi_type_incorrect);
      return;
    }

    // This is not available before onCreate
    wifiManager = (WifiManager) this.getSystemService(WIFI_SERVICE);
    // Start WiFi, otherwise nothing will work
    wifiManager.setWifiEnabled(true);

    // So we know when the network changes
    wifiReceiver = new WifiReceiver(wifiManager, this, statusView, ssid);

    // The order matters!
    mWifiStateFilter = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
    mWifiStateFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
    mWifiStateFilter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
    mWifiStateFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
    registerReceiver(wifiReceiver, mWifiStateFilter);
    receiverRegistered = true;

    if (password == null) {
      password = "";
    }
    Log.d(TAG, "Adding new configuration: \nSSID: " + ssid + "Type: " + networkT);
    NetworkSetting setting = new NetworkSetting(ssid, password, networkT);
    changeNetwork(setting);
  }

  public void pause() {
    if (receiverRegistered) {
      unregisterReceiver(wifiReceiver);
      receiverRegistered = false;
    }
  }

  public void resume() {
    if (wifiReceiver != null && mWifiStateFilter != null && !receiverRegistered) {
      registerReceiver(wifiReceiver, mWifiStateFilter);
      receiverRegistered = true;
    }
  }

  @Override
  protected void onDestroy() {
    if (wifiReceiver != null) {
      if (receiverRegistered) {
	unregisterReceiver(wifiReceiver);
	receiverRegistered = false;
      }
      wifiReceiver = null;
    }
    super.onDestroy();
  }

  /**
   * Update the network: either create a new network or modify an existing network
   * @param config the new network configuration
   * @param disableOthers true if other networks must be disabled
   * @return network ID of the connected network.
   */
  private int updateNetwork(WifiConfiguration config, boolean disableOthers){
    final int FAILURE = -1;
    WifiConfiguration found = findNetworkInExistingConfig(config.SSID);
    wifiManager.disconnect();
    if (found == null){
      statusView.setText(R.string.wifi_creating_network);
    } else {
      statusView.setText(R.string.wifi_modifying_network);
      Log.d(TAG, "Removing network " + found.networkId);
      wifiManager.removeNetwork(found.networkId);
      wifiManager.saveConfiguration();
     }
    networkId = wifiManager.addNetwork(config);
    Log.d(TAG, "Inserted/Modified network " + networkId);
    if (networkId < 0)
      return FAILURE;

    // Try to disable the current network and start a new one.
    if (!wifiManager.enableNetwork(networkId, disableOthers)) {
      networkId = -1;
      return FAILURE;
    }
    errorCount = 0;
    wifiManager.reassociate();
    return networkId;
  }
}