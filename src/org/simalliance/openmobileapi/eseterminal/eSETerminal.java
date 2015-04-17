package org.simalliance.openmobileapi.eseterminal;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.nfc.INfcAdapterExtras;
import android.nfc.NfcAdapter;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;


import java.util.MissingResourceException;
import java.util.NoSuchElementException;


import org.simalliance.openmobileapi.service.SmartcardError;
import org.simalliance.openmobileapi.service.ITerminalService;
import org.simalliance.openmobileapi.service.OpenLogicalChannelResponse;

/**
 * Created by sevilser on 18/12/14.
 */
public final class eSETerminal extends Service {

    private static final String TAG = "eSETerminal";

    public static final String ESE_TERMINAL = "eSE";

    public static final String ESE_STATE_CHANGE_ACTION = "org.simalliance.openmobileapi.eSETerminal";

    private final ITerminalService.Stub mTerminalBinder = new TerminalServiceImplementation();

    private INfcAdapterExtras ex;

    private Binder binder = new Binder();

    private BroadcastReceiver mNfcReceiver;

    private boolean mNFCAdapaterOpennedSuccesful = false;

    @Override
    public IBinder onBind(Intent intent) throws SecurityException {
        return mTerminalBinder;
    }

    @Override
    public void onCreate() {
        registerAdapterStateChangedEvent(this);
        NfcAdapter adapter =  NfcAdapter.getDefaultAdapter(this);
        if(adapter == null) {
            return;
        }
        ex = adapter.getNfcAdapterExtrasInterface();
        if(ex == null)  {
            return;
        }
        try {
            Bundle b = ex.open("org.simalliance.openmobileapi.service", binder);
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
        try {
            Bundle b = ex.close("org.simalliance.openmobileapi.service", binder);
            if (b == null) {
                Log.e(TAG, "Close SE failed");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error while closing nfc adapter", e);
        }
        mNFCAdapaterOpennedSuccesful = false;
        unregisterAdapterStateChangedEvent(getApplicationContext());
        super.onDestroy();
    }

    /**
     * Creates a formatted exception message.
     *
     * @param commandName the name of the command. <code>null</code> if not
     *            specified.
     * @param sw the response status word.
     * @return a formatted exception message.
     */
    static String createMessage(String commandName, int sw) {
        StringBuilder message = new StringBuilder();
        if (commandName != null) {
            message.append(commandName).append(" ");
        }
        message.append("SW1/2 error: ");
        message.append(Integer.toHexString(sw | 0x10000).substring(1));
        return message.toString();
    }

    /**
     * Creates a formatted exception message.
     *
     * @param commandName the name of the command. <code>null</code> if not
     *            specified.
     * @param message the message to be formatted.
     * @return a formatted exception message.
     */
    static String createMessage(String commandName, String message) {
        if (commandName == null) {
            return message;
        }
        return commandName + " " + message;
    }

    /**
     * Returns a concatenated response.
     *
     * @param r1 the first part of the response.
     * @param r2 the second part of the response.
     * @param length the number of bytes of the second part to be appended.
     * @return a concatenated response.
     */
    static byte[] appendResponse(byte[] r1, byte[] r2, int length) {
        byte[] rsp = new byte[r1.length + length];
        System.arraycopy(r1, 0, rsp, 0, r1.length);
        System.arraycopy(r2, 0, rsp, r1.length, length);
        return rsp;
    }

    public static String getType() {
        return ESE_TERMINAL;
    }

    private void registerAdapterStateChangedEvent(Context context) {
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
                    Intent i = new Intent(ESE_STATE_CHANGE_ACTION);
                    sendBroadcast(i);
                }
            }
        };
        context.registerReceiver(mNfcReceiver, intentFilter);
    }

    private void unregisterAdapterStateChangedEvent(Context context) {
        if (mNfcReceiver != null) {
            Log.v(TAG, "unregister ADAPTER_STATE_CHANGED event");
            context.unregisterReceiver(mNfcReceiver);
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
        public OpenLogicalChannelResponse
        internalOpenLogicalChannel(byte[] aid,
                                   SmartcardError error)
                throws RemoteException {
            if (!mNFCAdapaterOpennedSuccesful) {
                error.setError(RuntimeException.class, "open SE failed");
                return null;
            }
            byte[] manageChannelCommand = new byte[] {
                    0x00, 0x70, 0x00, 0x00, 0x01
            };
            byte[] rsp = new byte[0];
            rsp = transmit(manageChannelCommand, 2, 0x9000, 0, "MANAGE CHANNEL", error);
            if(error.createException() != null) {
                return null;
            }
            if ((rsp.length == 2) && ((rsp[0] == (byte) 0x68) && (rsp[1] == (byte) 0x81))) {
                error.setError(NoSuchElementException.class, "logical channels not supported");
                return null;
            }
            if (rsp.length == 2 && (rsp[0] == (byte) 0x6A && rsp[1] == (byte) 0x81)) {
                error.setError(MissingResourceException.class, "no free channel available");
                return null;
            }
            if (rsp.length != 3) {
                error.setError(MissingResourceException.class, "unsupported MANAGE CHANNEL response data");
                return null;
            }
            int channelNumber = rsp[0] & 0xFF;
            if (channelNumber == 0 || channelNumber > 19) {
                error.setError(MissingResourceException.class, "invalid logical channel number returned");
                return null;
            }

            if (aid == null) {
                return new OpenLogicalChannelResponse(channelNumber, null);
            }

            byte[] selectCommand = new byte[aid.length + 6];
            selectCommand[0] = (byte) channelNumber;
            if (channelNumber > 3) {
                selectCommand[0] |= 0x40;
            }
            selectCommand[1] = (byte) 0xA4;
            selectCommand[2] = 0x04;
            selectCommand[4] = (byte) aid.length;
            return new OpenLogicalChannelResponse(channelNumber, transmit(selectCommand, 2, 0x9000, 0xFFFF, "SELECT", error));
        }

        @Override
        public void internalCloseLogicalChannel(int channelNumber, SmartcardError error)
                throws RemoteException {
            if (!mNFCAdapaterOpennedSuccesful) {
                error.setError(RuntimeException.class, "open SE failed");
                return;
            }
            if (channelNumber > 0) {
                byte cla = (byte) channelNumber;
                if (channelNumber > 3) {
                    cla |= 0x40;
                }
                byte[] manageChannelClose = new byte[] {
                        cla, 0x70, (byte) 0x80, (byte) channelNumber
                };
                transmit(manageChannelClose, 2, 0x9000, 0xFFFF, "MANAGE CHANNEL", error);
            }
        }

        @Override
        public byte[] internalTransmit(byte[] command, SmartcardError error) throws RemoteException {
            if (!mNFCAdapaterOpennedSuccesful) {
                error.setError(RuntimeException.class, "open SE failed");
                return new byte[0];
            }
            Bundle b = ex.transceive("org.simalliance.openmobileapi.service", command);
            if (b == null) {
                error.setError(RuntimeException.class, "exchange APDU failed");
            }
            return b.getByteArray("out");
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

        /**
         * Transmits the specified command and returns the response. Optionally
         * checks the response length and the response status word. The status word
         * check is implemented as follows (sw = status word of the response):
         * <p>
         * if ((sw & swMask) != (swExpected & swMask)) throw new CardException();
         * </p>
         *
         * @param cmd the command APDU to be transmitted.
         * @param minRspLength the minimum length of received response to be
         *            checked.
         * @param swExpected the response status word to be checked.
         * @param swMask the mask to be used for response status word comparison.
         * @param commandName the name of the smart card command for logging
         *            purposes. May be <code>null</code>.
         * @return the response received.
         */
        public synchronized byte[] transmit(
                byte[] cmd,
                int minRspLength,
                int swExpected,
                int swMask,
                String commandName,
                SmartcardError error) {
            byte[] rsp = null;
            try {
                rsp = protocolTransmit(cmd, error);
            } catch (Exception e) {
                Log.e(TAG, "Exception: ", e);
                if (commandName == null) {
                    error.setError(RuntimeException.class, e.getMessage());
                    return null;
                } else {
                    error.setError(RuntimeException.class, createMessage(commandName, "transmit failed"));
                    return null;
                }
            }
            if (minRspLength > 0 && (rsp == null || rsp.length < minRspLength)) {
                error.setError(RuntimeException.class, createMessage(commandName, "response too small"));
                return null;
            }
            if (swMask != 0) {
                if (rsp == null || rsp.length < 2) {
                    error.setError(RuntimeException.class, createMessage(commandName, "SW1/2 not available"));
                    return null;
                }
                int sw1 = rsp[rsp.length - 2] & 0xFF;
                int sw2 = rsp[rsp.length - 1] & 0xFF;
                int sw = (sw1 << 8) | sw2;
                if ((sw & swMask) != (swExpected & swMask)) {
                    error.setError(RuntimeException.class, createMessage(commandName, sw));
                    return null;
                }
            }
            return rsp;
        }

        /**
         * Protocol specific implementation of the transmit operation. This method
         * is synchronized in order to handle GET RESPONSE and command repetition
         * without interruption by other commands.
         *
         * @param cmd the command to be transmitted.
         * @return the response received.
         */
        protected synchronized byte[] protocolTransmit(byte[] cmd, SmartcardError error)
                throws RemoteException {
            byte[] command = cmd;
            byte[] rsp = null;
            rsp = internalTransmit(command, error);


            if (rsp.length >= 2) {
                int sw1 = rsp[rsp.length - 2] & 0xFF;
                if (sw1 == 0x6C) {
                    command[cmd.length - 1] = rsp[rsp.length - 1];
                    rsp = internalTransmit(command, error);
                } else if (sw1 == 0x61) {
                    byte[] getResponseCmd = new byte[] {
                            command[0], (byte) 0xC0, 0x00, 0x00, 0x00
                    };
                    byte[] response = new byte[rsp.length - 2];
                    System.arraycopy(rsp, 0, response, 0, rsp.length - 2);
                    while (true) {
                        getResponseCmd[4] = rsp[rsp.length - 1];
                        rsp = internalTransmit(getResponseCmd,error);
                        if (rsp.length >= 2 && rsp[rsp.length - 2] == 0x61) {
                            response = appendResponse(
                                    response, rsp, rsp.length - 2);
                        } else {
                            response = appendResponse(response, rsp, rsp.length);
                            break;
                        }
                    }
                    rsp = response;
                }
            }
            return rsp;
        }

        @Override
        public byte[] simIOExchange(int fileID, String filePath, byte[] cmd, SmartcardError error)
                throws RemoteException {
            throw new RemoteException("SIM IO error!");
        }

        @Override
        public String getSEChangeAction() {
            return ESE_STATE_CHANGE_ACTION;
        }
    }
}
