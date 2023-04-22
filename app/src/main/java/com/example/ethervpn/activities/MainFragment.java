package com.example.ethervpn.activities;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.example.ethervpn.R;
import com.example.ethervpn.databinding.FragmentMainBinding;
import com.example.ethervpn.interfaces.ChangeServer;
import com.example.ethervpn.model.Server;
import com.example.ethervpn.services.TimerService;
import com.example.ethervpn.utilities.CheckInternetConnection;
import com.example.ethervpn.utilities.SharedPreference;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import de.blinkt.openvpn.api.APIVpnProfile;
import de.blinkt.openvpn.api.IOpenVPNAPIService;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;
import de.blinkt.openvpn.core.VpnStatus;

public class MainFragment extends Fragment implements View.OnClickListener, ChangeServer, Handler.Callback {

    private Server server;
    private CheckInternetConnection connection;

    boolean vpnStart = false;
    private SharedPreference preference;

    private FragmentMainBinding binding;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_main, container, false);

        View view = binding.getRoot();
        initializeAll();

        return view;
    }

    private static final int MSG_UPDATE_STATE = 0;

    private static final int ICS_OPENVPN_PERMISSION = 7;

    private static final int NOTIFICATIONS_PERMISSION_REQUEST_CODE = 11;

    protected IOpenVPNAPIService mService = null;

    protected TimerService mTimerService = null;

    boolean mBound = false;

    private Handler mHandler;

    private Boolean auth_failed = false;

    /**
     * Initialize all variable and object
     */
    private void initializeAll() {
        preference = new SharedPreference(getContext());
        server = preference.getServer();

        // Update current selected server icon
        updateCurrentServerIcon(server.getFlagUrl());

        connection = new CheckInternetConnection();
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.vpnBtn.setOnClickListener(this);

        // Checking is vpn already running or not
        isServiceRunning();
        VpnStatus.initLogCache(getActivity().getCacheDir());
    }

    /**
     * @param v: click listener view
     */
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.vpnBtn:
                // Vpn is running, user would like to disconnect current connection.
                if (vpnStart) {
                    confirmDisconnect();
                }else {
                    try{
                        prepareVpn();
                    } catch(RemoteException e){
                        e.printStackTrace();
                    }
                }
        }
    }

    /**
     * Show show disconnect confirm dialog
     */
    public void confirmDisconnect(){
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setMessage(getActivity().getString(R.string.connection_close_confirm));

        builder.setPositiveButton(getActivity().getString(R.string.yes), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                stopVpn();
            }
        });
        builder.setNegativeButton(getActivity().getString(R.string.no), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                // User cancelled the dialog
            }
        });

        // Create the AlertDialog
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * Prepare for vpn connect with required permission
     */
    private void prepareVpn() throws RemoteException {
        if (!vpnStart) {
            if (getInternetStatus()) {

                // Checking permission for network monitor
                Intent intent = mService.prepareVPNService();

                if (intent != null) {
                    startActivityForResult(intent, 1);
                } else {
                    startVpn();//have already permission
                }

                // Update confection status
                status("connecting");

            } else {

                // No internet connection available
                showToast("you have no internet connection !!");
            }

        } else if (stopVpn()) {

            // VPN is stopped, show a Toast message.
            showToast("Disconnect Successfully");
        }
    }

    /**
     * Stop vpn
     * @return boolean: VPN status
     */
    public boolean stopVpn() {
        try {
            mService.disconnect();
            status("connect");
            vpnStart = false;
            return true;
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        return false;
    }

    /**
     * Taking permission for network access
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ICS_OPENVPN_PERMISSION) {
            try {
                mService.registerStatusCallback(mCallback);
            } catch (RemoteException e) {
                e.printStackTrace();
            }

        }
    }

    /**
     * Internet connection status.
     */
    public boolean getInternetStatus() {
        return connection.netCheck(getContext());
    }

    /**
     * Get service status
     */
    public void isServiceRunning() {
        setStatus((String) binding.logTv.getText());
    }

    /**
     * Start the VPN
     */
    private void startVpn() {
        try {
            // .ovpn file
            InputStream conf = getActivity().getAssets().open(server.getOvpn());
            InputStreamReader isr = new InputStreamReader(conf);
            BufferedReader br = new BufferedReader(isr);
            String config = "";
            String line;

            while (true) {
                line = br.readLine();
                if (line == null) break;
                config += line + "\n";
            }

            br.readLine();
            APIVpnProfile profile = mService.addNewVPNProfile(server.getCountry(), false, config.toString());
            mService.startProfile(profile.mUUID);
            mService.startVPN(config.toString());

            // Update log
            binding.logTv.setText("Connecting...");

        } catch (IOException | RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * Status change with corresponding vpn connection status
     * @param connectionState
     */
    public void setStatus(String connectionState) {
        if (connectionState!= null)
            switch (connectionState) {
                case "NOPROCESS":
                    binding.logTv.setText("No network connection");
                    status("connect");
                    vpnStart = false;
                    break;
                case "CONNECTED":
                    vpnStart = true;// it will use after restart this activity
                    status("connected");
                    binding.logTv.setText("");
                    break;
                case "WAIT":
                    status("connecting");
                    binding.logTv.setText("Waiting for server connection!!");
                    break;
                case "AUTH":
                    status("connecting");
                    binding.logTv.setText("Server authenticating!!");
                    break;
                case "CONNECTRETRY":
                    status("connecting");
                    binding.logTv.setText("Reconnecting...");
                    break;
                case "AUTH_FAILED":
                    status("connect");
                    binding.logTv.setText("Authorization failed!!");
                    break;
                case "EXITING":
                    status("connect");
                    binding.logTv.setText("Unable to connect to server!!");
                    break;
                default:
                    status("connecting");
                    binding.logTv.setText("Connection in progress!!");
                    vpnStart = false;
                    break;
            }

    }

    /**
     * Change button background color and text
     * @param status: VPN current status
     */
    public void status(String status) {

        if (status.equals("connect")) {
            binding.vpnBtn.setText(getContext().getString(R.string.connect));
            binding.connectionStateTv.setText("state: NONE");
            binding.durationTv.setText("duration: 00:00:00");
        } else if (status.equals("connected")) {
            binding.vpnBtn.setText(getContext().getString(R.string.disconnect));
        } else if (status.equals("connecting")) {
            binding.vpnBtn.setText(getContext().getString(R.string.connecting));
        }

    }

    /**
     * Update status UI
     * @param state: running time
     */
    public void updateConnectionStatus(String state) {
        if(!("NOPROCESS").equals(state))
            binding.connectionStateTv.setText("state: " + state);
    }

    /**
     * Show toast message
     * @param message: toast message
     */
    public void showToast(String message) {
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    /**
     * VPN server country icon change
     * @param serverIcon: icon URL
     */
    public void updateCurrentServerIcon(String serverIcon) {
        Glide.with(getContext())
                .load(serverIcon)
                .into(binding.selectedServerIcon);
    }

    /**
     * Change server when user select new server
     * @param server ovpn server details
     */
    @Override
    public void newServer(Server server) {
        this.server = server;
        updateCurrentServerIcon(server.getFlagUrl());

        // Stop previous connection
        if (vpnStart) {
            stopVpn();
        }

        try{
            prepareVpn();
        } catch(RemoteException e){
            e.printStackTrace();
        }

    }

    @Override
    public void onStart() {
        super.onStart();

        mHandler = new Handler(this);
        bindService();
    }

    private TimerService.TimerServiceCallback mTimerServiceCallback = new TimerService.TimerServiceCallback() {
        @Override
        public void onDurationChanged(String duration) {
            // Update UI with new duration value
            if(mBound)
                binding.durationTv.setText("duration: " + duration);
        }
    };

    private ServiceConnection mTimerServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            // This will be called when the service is connected
            TimerService.LocalBinder binder = (TimerService.LocalBinder) iBinder;
            mTimerService = binder.getService();
            binder.setCallback(mTimerServiceCallback);
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            // This will be called when the service is disconnected unexpectedly
            mBound = false;
        }
    };

    private IOpenVPNStatusCallback mCallback = new IOpenVPNStatusCallback.Stub() {
        /**
         * This is called by the remote service regularly to tell us about
         * new values.  Note that IPC calls are dispatched through a thread
         * pool running in each process, so the code executing here will
         * NOT be running in our main thread like most other things -- so,
         * to update the UI, we need to use a Handler to hop over there.
         */

        @Override
        public void newStatus(String uuid, String state, String message, String level) throws RemoteException {
            Message msg = Message.obtain(mHandler, MSG_UPDATE_STATE, state + "|" + message);

            if (state.equals("EXITING")){
                auth_failed = true;
            }
            try {
                setStatus(state);
                updateConnectionStatus(state);
            } catch (Exception e) {
                e.printStackTrace();
            }

            if(auth_failed && state.equals("NOPROCESS"))
                binding.logTv.setText("AUTHORIZATION FAILED!!");
            if (state.equals("CONNECTED")) {
                if (ActivityCompat.checkSelfPermission(getContext(), android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(getActivity(), new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS_PERMISSION_REQUEST_CODE);
                }
                bindTimerService();
            } else {
                unbindTimerService();
            }

            msg.sendToTarget();

        }

    };


    /**
     * Class for interacting with the main interface of the service.
     */
    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  We are communicating with our
            // service through an IDL interface, so get a client-side
            // representation of that from the raw service object.

            mService = IOpenVPNAPIService.Stub.asInterface(service);

            try {
                // Request permission to use the API
                Intent i = mService.prepare(getActivity().getPackageName());
                if (i!=null) {
                    startActivityForResult(i, ICS_OPENVPN_PERMISSION);
                } else {
                    onActivityResult(ICS_OPENVPN_PERMISSION, Activity.RESULT_OK,null);
                }

            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            mService = null;

        }
    };

    private void bindService() {

        Intent icsopenvpnService = new Intent(IOpenVPNAPIService.class.getName());
        icsopenvpnService.setPackage("com.example.ethervpn");

        getActivity().bindService(icsopenvpnService, mConnection, Context.BIND_AUTO_CREATE);

    }

    private void bindTimerService() {
        Intent serviceIntent = new Intent(getActivity(), TimerService.class);
        serviceIntent.setPackage("com.example.ethervpn");
        getActivity().bindService(serviceIntent, mTimerServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onResume() {
        if (server == null) {
            server = preference.getServer();
        }
        super.onResume();
        bindService();
    }

    @Override
    public void onPause() {
        if (mService != null) {
            unbindService();
        }
        super.onPause();
    }

    /**
     * Save current selected server on local shared preference
     */
    @Override
    public void onStop() {
        if (server != null) {
            preference.saveServer(server);
        }
        super.onStop();
    }

    private void unbindService() {
        getActivity().unbindService(mConnection);
    }

    private void unbindTimerService(){
        getActivity().unbindService(mTimerServiceConnection);
        mBound = false;
    }

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        if(msg.what == MSG_UPDATE_STATE) {
            binding.logTv.setText((CharSequence) msg.obj);
        }
        return true;
    }
}