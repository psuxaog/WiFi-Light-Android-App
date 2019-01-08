package com.example.linxsong.wifilight;

import android.Manifest;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.espressif.iot.esptouch.EsptouchTask;
import com.espressif.iot.esptouch.IEsptouchListener;
import com.espressif.iot.esptouch.IEsptouchResult;
import com.espressif.iot.esptouch.IEsptouchTask;
import com.espressif.iot.esptouch.task.__IEsptouchTask;
import com.espressif.iot.esptouch.util.ByteUtil;
import com.espressif.iot.esptouch.util.EspNetUtil;

import java.lang.ref.WeakReference;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, IGetMessageCallBack {
    private static final String TAG = "WiFiLight";
    public String sendData;
    private static final int REQUEST_PERMISSION = 0x01;
    public ServerSocket serverSocket;
    private TextView tvMessage;
    private EditText etSSID;
    private EditText etBSSID;
    private EditText etPassword;
    private Button btnConfig;
    private Button btnSend;
    private SeekBar sbRed;
    private SeekBar sbGreen;
    private SeekBar sbBlue;
    private MyServiceConnection serviceConnection;
    private MQTTService mqttService;
    private UdpClient client = null;
    private final MyHandler myHandler = new MyHandler(this);
    private static TcpServer tcpServer = null;
    private MyBroadcastReceiver myBroadcastReceiver = new MyBroadcastReceiver();
    ExecutorService exec = Executors.newCachedThreadPool();
    public static Context context;
    private IEsptouchListener myListener = new IEsptouchListener() {
        public void onEsptouchResultAdded(final IEsptouchResult result) {
            onEsptoucResultAddedPerform(result);
        }
    };
    private EsptouchAsyncTask4 mTask;
    private boolean mReceiverRegistered = false;

    public void setMessage(String message) {
        mqttService = serviceConnection.getMqttService();
        mqttService.toCreateNotification(message);
    }

    private class MyHandler extends android.os.Handler {
        private final WeakReference<MainActivity> mActivity;

        MyHandler(MainActivity activity) {
            mActivity = new WeakReference<MainActivity>(activity);
        }

        public void handleMessage(Message msg) {
            MainActivity activity = mActivity.get();
            if (activity != null) {
                switch (msg.what) {
                    case TcpServer.MSG_TCP_CONNECT:
                        Log.i(TAG, "msg=" + msg.obj.toString());
                        Toast.makeText(MainActivity.this, msg.obj.toString(), Toast.LENGTH_SHORT).show();
                        break;
                }
            }
        }
    }

    private void bindReceiver() {
        IntentFilter intentFilter = new IntentFilter("tcpServerHasConnect");
        registerReceiver(myBroadcastReceiver, intentFilter);
    }

    private class MyBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String mAction = intent.getAction();
            switch (mAction) {
                case "tcpServerHasConnect":
                    String msg = intent.getStringExtra("tcpServerHasConnect");
                    Message message = Message.obtain();
                    message.what = TcpServer.MSG_TCP_CONNECT;
                    message.obj = msg;
                    myHandler.sendMessage(message);
                    break;
            }
        }
    }

    private BroadcastReceiver mReceviver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
            assert wifiManager != null;

            switch (action) {
                case WifiManager.NETWORK_STATE_CHANGED_ACTION:
                    WifiInfo wifiInfo;
                    if (intent.hasExtra(WifiManager.EXTRA_WIFI_INFO)) {
                        wifiInfo = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
                    } else {
                        wifiInfo = wifiManager.getConnectionInfo();
                    }
                    onWifiChanged(wifiInfo);
                    break;
                case LocationManager.PROVIDERS_CHANGED_ACTION:
                    onWifiChanged(wifiManager.getConnectionInfo());
                    onLocationChanged();
                    break;
            }
        }
    };
    private boolean mDestroyed = false;

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQUEST_PERMISSION:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (!mDestroyed) {
                        registerBroadcastReceiver();
                    }
                }
                break;
        }
    }

    protected void onDestroy() {
        unbindService(serviceConnection);
        super.onDestroy();
        mDestroyed = true;
        if (mReceiverRegistered) {
            unregisterReceiver(mReceviver);
        }
    }

    private boolean isSDKAtLeastP() {
        return Build.VERSION.SDK_INT >= 28;
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        if (isSDKAtLeastP()) {
            filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        }
        registerReceiver(mReceviver, filter);
        mReceiverRegistered = true;
    }

    private void onWifiChanged(WifiInfo info) {
        if (info == null) {
            etSSID.setText("");
            etSSID.setTag(null);
            etBSSID.setText("");
            etBSSID.setTag("");
            btnConfig.setEnabled(false);
            if (mTask != null) {
                mTask.cancelEsptouch();
                mTask = null;
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("Wifi disconnected or changed")
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        } else {
            String ssid = info.getSSID();
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            etSSID.setText(ssid);
            if (ssid.equals("LinxWiFi")) {
                etPassword.setText("1335125wifi");
            }
            if (ssid.equals("PG0100")) {
                etPassword.setText("12345678");
            }
            if (ssid.equals("app_test")) {
                etPassword.setText("aa654321");
            }
            if (ssid.equals("Conference Room5")) {
                etPassword.setText("gdlg@vip5");
            }
            if (ssid.equals("conference room5")) {
                etPassword.setText("gdlg@vip5");
            }
            if (ssid.equals("lab_test")) {
                etPassword.setText("LG@98765");
            }
            etSSID.setTag(ByteUtil.getBytesByString(ssid));
            byte[] ssidOriginalData = EspUtils.getOriginalSsidBytes(info);
            etSSID.setTag(ssidOriginalData);
            String bssid = info.getBSSID();
            etBSSID.setText(bssid);
            tvMessage.setText("");
            if (etBSSID.getText().toString().isEmpty() || etBSSID.getText().toString().equals("")) {
                btnConfig.setEnabled(false);
                etPassword.setEnabled(false);
                etPassword.setText("");
            } else {
                btnConfig.setEnabled(true);
                etPassword.setEnabled(true);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                int frequence = info.getFrequency();
                if (frequence > 4900 && frequence < 5900) {
                    tvMessage.setText("Device dose not support 5G Wifi");
                }
            }
        }
    }

    private void onLocationChanged() {
        boolean enable;
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            enable = false;
        } else {
            boolean locationGPS = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean locationNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            enable = locationGPS || locationNetwork;
        }
        if (!enable) {
            tvMessage.setText("Location(GPS) is disable");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        etSSID = findViewById(R.id.etSSID);
        etBSSID = findViewById(R.id.etBSSID);
        etPassword = findViewById(R.id.etPassword);
        btnConfig = findViewById(R.id.btnConfig);
        btnSend = findViewById(R.id.btnSend);
        sbRed = findViewById(R.id.sbRed);
        sbGreen = findViewById(R.id.sbGreen);
        sbBlue = findViewById(R.id.sbBlue);
        tvMessage = findViewById(R.id.tvMessage);
        btnSend.setOnClickListener(this);
        btnConfig.setOnClickListener(this);
        btnConfig.setEnabled(false);
        serviceConnection = new MyServiceConnection();
        serviceConnection.setIGetMessageCallBack((IGetMessageCallBack) MainActivity.this);
        Intent intent = new Intent(this, MQTTService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        int rgb;
                        rgb = sbBlue.getProgress() & 0xFF;
                        rgb |= (sbGreen.getProgress() << 8) & 0xFF00;
                        rgb |= (sbRed.getProgress() << 16) & 0xFF0000;
                        sendData = String.format("{\"rgb\":%d}", rgb);
                        MQTTService.publish(sendData);
                    }
                });
                thread.start();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        sendData = String.format("{\"red\":%d,\"green\":%d,\"blue\":%d}", sbRed.getProgress(), sbGreen.getProgress(), sbBlue.getProgress());
                        MQTTService.publish(sendData);
                    }
                });
                thread.start();
            }
        };
        sbRed.setOnSeekBarChangeListener(seekListener);
        sbGreen.setOnSeekBarChangeListener(seekListener);
        sbBlue.setOnSeekBarChangeListener(seekListener);
        context = this;
        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener()

        {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        bindReceiver();
//        tcpServer = new TcpServer(8888);
//        exec.execute(tcpServer);
//        client = new
//
//        UdpClient();
//        exec.execute(client);
        if (

                isSDKAtLeastP())

        {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                String[] permissions = {
                        Manifest.permission.ACCESS_COARSE_LOCATION
                };

                ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSION);
            } else {
                registerBroadcastReceiver();
            }

        } else

        {
            registerBroadcastReceiver();
        }
    }

    public void onClick(View v) {
        if (v == btnConfig) {
            byte[] ssid = etSSID.getTag() == null ? ByteUtil.getBytesByString(etBSSID.getText().toString()) :
                    (byte[]) etSSID.getTag();
            byte[] password = ByteUtil.getBytesByString(etPassword.getText().toString());
            byte[] bssid = EspNetUtil.parseBssid2bytes(etBSSID.getText().toString());
            byte[] deviceCount = "1".getBytes();
            byte[] broadcast = {(byte) 1};
            if (mTask != null) {
                mTask.cancelEsptouch();
            }
            mTask = new EsptouchAsyncTask4(this);
            mTask.execute(ssid, bssid, password, deviceCount, broadcast);
        }
        if (v == btnSend) {
            sendData = String.format("{\"red\":%d,\"green\":%d,\"blue\":%d}", sbRed.getProgress(), sbGreen.getProgress(), sbBlue.getProgress());
//            if (!tcpServer.SST.isEmpty()) {
//                Log.i(TAG, "SST Size=" + tcpServer.SST.size());
//                exec.execute(new Runnable() {
//                    @Override
//                    public void run() {
//                        tcpServer.SST.get(tcpServer.SST.size() - 1).send(sendData);
//                    }
//                });
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    MQTTService.publish(sendData);
                }
            });
            thread.start();
        }
    }

    private void onEsptoucResultAddedPerform(final IEsptouchResult result) {
        runOnUiThread(new Runnable() {

            @Override
            public void run() {
                String text = result.getBssid() + " is connected to the wifi";
                Toast.makeText(MainActivity.this, text,
                        Toast.LENGTH_LONG).show();
            }

        });
    }

    private static class EsptouchAsyncTask4 extends AsyncTask<byte[], Void, List<IEsptouchResult>> {
        private WeakReference<MainActivity> mActivity;

        // without the lock, if the user tap confirm and cancel quickly enough,
        // the bug will arise. the reason is follows:
        // 0. task is starting created, but not finished
        // 1. the task is cancel for the task hasn't been created, it do nothing
        // 2. task is created
        // 3. Oops, the task should be cancelled, but it is running
        private final Object mLock = new Object();
        private ProgressDialog mProgressDialog;
        private AlertDialog mResultDialog;
        private IEsptouchTask mEsptouchTask;

        EsptouchAsyncTask4(MainActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        void cancelEsptouch() {
            cancel(true);
            if (mProgressDialog != null) {
                mProgressDialog.dismiss();
            }
            if (mResultDialog != null) {
                mResultDialog.dismiss();
            }
            if (mEsptouchTask != null) {
                mEsptouchTask.interrupt();
            }
        }

        @Override
        protected void onPreExecute() {
            Activity activity = mActivity.get();
            mProgressDialog = new ProgressDialog(activity);
            mProgressDialog.setMessage("正在搜索设备，请等待...");
            mProgressDialog.setCanceledOnTouchOutside(false);
            mProgressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    synchronized (mLock) {
                        if (__IEsptouchTask.DEBUG) {
                            Log.i(TAG, "progress dialog back pressed canceled");
                        }
                        if (mEsptouchTask != null) {
                            mEsptouchTask.interrupt();
                        }
                    }
                }
            });
            mProgressDialog.setButton(DialogInterface.BUTTON_NEGATIVE, activity.getText(android.R.string.cancel),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            synchronized (mLock) {
                                if (__IEsptouchTask.DEBUG) {
                                    Log.i(TAG, "progress dialog cancel button canceled");
                                }
                                if (mEsptouchTask != null) {
                                    mEsptouchTask.interrupt();
                                }
                            }
                        }
                    });
            mProgressDialog.show();
        }

        @Override
        protected List<IEsptouchResult> doInBackground(byte[]... params) {
            MainActivity activity = mActivity.get();
            int taskResultCount;
            synchronized (mLock) {
                byte[] apSsid = params[0];
                byte[] apBssid = params[1];
                byte[] apPassword = params[2];
                byte[] deviceCountData = params[3];
                byte[] broadcastData = params[4];
                taskResultCount = deviceCountData.length == 0 ? -1 : Integer.parseInt(new String(deviceCountData));
                Context context = activity.getApplicationContext();
                mEsptouchTask = new EsptouchTask(apSsid, apBssid, apPassword, context);
                mEsptouchTask.setPackageBroadcast(broadcastData[0] == 1);
                mEsptouchTask.setEsptouchListener(activity.myListener);
            }
            return mEsptouchTask.executeForResults(taskResultCount);
        }

        @Override
        protected void onPostExecute(List<IEsptouchResult> result) {
            MainActivity activity = mActivity.get();
            mProgressDialog.dismiss();
            mResultDialog = new AlertDialog.Builder(activity)
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
            mResultDialog.setCanceledOnTouchOutside(false);
            if (result == null) {
                mResultDialog.setMessage("Create Esptouch task failed, the esptouch port could be used by other thread");
                mResultDialog.show();
                return;
            }

            IEsptouchResult firstResult = result.get(0);
            // check whether the task is cancelled and no results received
            if (!firstResult.isCancelled()) {
                int count = 0;
                // max results to be displayed, if it is more than maxDisplayCount,
                // just show the count of redundant ones
                final int maxDisplayCount = 5;
                // the task received some results including cancelled while
                // executing before receiving enough results
                if (firstResult.isSuc()) {
                    StringBuilder sb = new StringBuilder();
                    for (IEsptouchResult resultInList : result) {
                        sb.append("连接设备成功！, 设备名 = ")
                                .append(resultInList.getBssid())
                                .append(", 设备IP = ")
                                .append(resultInList.getInetAddress().getHostAddress())
                                .append("\n");
                        count++;
                        if (count >= maxDisplayCount) {
                            break;
                        }
                    }
                    if (count < result.size()) {
                        sb.append("\nthere's ")
                                .append(result.size() - count)
                                .append(" more result(s) without showing\n");
                    }
                    mResultDialog.setMessage(sb.toString());
                } else {
                    mResultDialog.setMessage("配网失败");
                }

                mResultDialog.show();
            }
            activity.mTask = null;
        }

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
