package org.simalliance.openmobileapi.eseterminal;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.INfcAdapterExtras;
import android.nfc.NfcAdapter;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;

import java.util.NoSuchElementException;

import org.simalliance.openmobileapi.service.OpenLogicalChannelResponse;
import org.simalliance.openmobileapi.service.SmartcardError;
import org.simalliance.openmobileapi.service.ITerminalService;

public final class eSETerminal extends Service {

    private static final String TAG = "eSETerminal";

    public static final String ESE_TERMINAL = "eSE";

    public static final String ACTION_ESE_STATE_CHANGED = "org.simalliance.openmobileapi.action.ESE_STATE_CHANGED";

    private final ITerminalService.Stub mTerminalBinder = new TerminalServiceImplementation();

    private INfcAdapterExtras ex;

    private Binder binder = new Binder();

    private BroadcastReceiver mNfcReceiver;

    private boolean mNFCAdapaterOpennedSuccesful = false;

    @Override
    public IBinder onBind(Intent intent) {
        return mTerminalBinder;
    }

    @Override
    public void onCreate() {
        registerAdapterStateChangedEvent();
        NfcAdapter adapter =  NfcAdapter.getDefaultAdapter(this);
        if(adapter == null) {
            return;
        }
        ex = adapter.getNfcAdapterExtrasInterface();
        if(ex == null)  {
            return;
        }
        try {
            Bundle b = ex.open("org.simalliance.openmobileapi.eseterminal", binder);
            if (b == null) {
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error while opening nfc adapter", e);
            return;
        }
        mNFCAdapaterOpennedSuccesful = true;
    }

    @Override
    public void onDestroy() {
        if (ex != null) {
            try {
                Bundle b = ex.close("org.simalliance.openmobileapi.eseterminal", binder);
                if (b == null) {
                    Log.e(TAG, "Close SE failed");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error while closing nfc adapter", e);
            }
        }
        mNFCAdapaterOpennedSuccesful = false;
        unregisterAdapterStateChangedEvent();
        super.onDestroy();
    }

    public static String getType() {
        return ESE_TERMINAL;
    }

    private byte[] transmit(byte[] cmd) throws Exception {
        if (!mNFCAdapaterOpennedSuccesful) {
            throw new IllegalStateException("Open SE failed");
        }
        Bundle b = ex.transceive("org.simalliance.openmobileapi.eseterminal", cmd);
        if (b == null) {
            throw new IOException ("Exchange APDU failed");
        }
        return b.getByteArray("out");
    }

    private void registerAdapterStateChangedEvent() {
        Log.v(TAG, "register ADAPTER_STATE_CHANGED event");

        IntentFilter intentFilter = new IntentFilter(
                "android.nfc.action.ADAPTER_STATE_CHANGED");
        mNfcReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final boolean nfcAdapterAction = "android.nfc.action.ADAPTER_STATE_CHANGED".equals(
                        intent.getAction());
                // Is NFC Adapter turned on?
                final boolean nfcAdapterOn
                        = nfcAdapterAction && intent.getIntExtra(
                        "android.nfc.extra.ADAPTER_STATE", 1) == 3;
                if (nfcAdapterOn) {
                    Log.i(TAG, "NFC Adapter is ON. Checking access rules for"
                            + " updates.");
                    Intent i = new Intent(ACTION_ESE_STATE_CHANGED);
                    sendBroadcast(i);
                }
            }
        };
        registerReceiver(mNfcReceiver, intentFilter);
    }

    private void unregisterAdapterStateChangedEvent() {
        if (mNfcReceiver != null) {
            Log.v(TAG, "unregister ADAPTER_STATE_CHANGED event");
            unregisterReceiver(mNfcReceiver);
            mNfcReceiver = null;
        }
    }

    /**
     * The Terminal service interface implementation.
     */
    final class TerminalServiceImplementation extends ITerminalService.Stub {
        @Override
        public String getType() {
            return eSETerminal.getType();
        }


        @Override
        public OpenLogicalChannelResponse internalOpenLogicalChannel(
                byte[] aid,
                byte p2,
                SmartcardError error) throws RemoteException {
            try {
                if (!mNFCAdapaterOpennedSuccesful) {
                    throw new IllegalStateException("Open SE failed");
                }
                byte[] manageChannelCommand = new byte[]{
                        0x00, 0x70, 0x00, 0x00, 0x01
                };
                byte[] rsp = eSETerminal.this.transmit(manageChannelCommand);
                if ((rsp.length == 2) && ((rsp[0] == (byte) 0x68) && (rsp[1] == (byte) 0x81))) {
                    throw new NoSuchElementException("Logical channels not supported");
                }
                if (rsp.length == 2 && (rsp[0] == (byte) 0x6A && rsp[1] == (byte) 0x81)) {
                    throw new NoSuchElementException("no free channel available");
                }
                if (rsp.length != 3) {
                    throw new NoSuchElementException("unsupported MANAGE CHANNEL response data");
                }
                int channelNumber = rsp[0] & 0xFF;
                if (channelNumber == 0 || channelNumber > 19) {
                    throw new NoSuchElementException("invalid logical channel number returned");
                }

                byte[] selectResponse = null;
                if (aid != null) {
                    byte[] selectCommand = new byte[aid.length + 6];
                    selectCommand[0] = (byte) channelNumber;
                    if (channelNumber > 3) {
                        selectCommand[0] |= 0x40;
                    }
                    selectCommand[1] = (byte) 0xA4;
                    selectCommand[2] = 0x04;
                    selectCommand[3] = p2;
                    selectCommand[4] = (byte) aid.length;
                    System.arraycopy(aid, 0, selectCommand, 5, aid.length);
                    try {
                        selectResponse = eSETerminal.this.transmit(selectCommand);
                        int length = selectResponse.length;
                        if (!(selectResponse[length - 2] == 0x90 && selectResponse[length - 1] == 0x00)
                                && !(selectResponse[length - 2] == 0x62)
                                && !(selectResponse[length - 2] == 0x63)) {
                            throw new NoSuchElementException("Select command failed");
                        }
                    } catch (Exception e) {
                        internalCloseLogicalChannel(channelNumber, error);
                        throw e;
                    }
                }

                return new OpenLogicalChannelResponse(channelNumber, selectResponse);
            } catch (Exception e) {
                error.set(e);
                return null;
            }
        }

        @Override
        public void internalCloseLogicalChannel(int channelNumber, SmartcardError error)
                throws RemoteException {
            try {
                if (!mNFCAdapaterOpennedSuccesful) {
                    throw new IOException("open SE failed");
                }
                if (channelNumber > 0) {
                    byte cla = (byte) channelNumber;
                    if (channelNumber > 3) {
                        cla |= 0x40;
                    }
                    byte[] manageChannelClose = new byte[]{
                            cla, 0x70, (byte) 0x80, (byte) channelNumber
                    };
                    eSETerminal.this.transmit(manageChannelClose);
                }
            } catch (Exception e) {
                error.set(e);
            }
        }

        @Override
        public byte[] internalTransmit(byte[] command, SmartcardError error) throws RemoteException {
            try {
                return eSETerminal.this.transmit(command);
            } catch (Exception e) {
                error.set(e);
                return null;
            }
        }

        @Override
        public byte[] getAtr() {
            return null;
        }

        @Override
        public boolean isCardPresent() throws RemoteException {
            NfcAdapter adapter =  NfcAdapter.getDefaultAdapter(eSETerminal.this);
            if(adapter == null) {
                Log.e(TAG, "Cannot get NFC Default Adapter");
                return false;
            }
            return adapter.isEnabled();
        }

        @Override
        public byte[] simIOExchange(int fileID, String filePath, byte[] cmd, SmartcardError error)
                throws RemoteException {
            throw new RemoteException("SIM IO error!");
        }

        @Override
        public String getSeStateChangedAction() {
            return ACTION_ESE_STATE_CHANGED;
        }
    }
}
