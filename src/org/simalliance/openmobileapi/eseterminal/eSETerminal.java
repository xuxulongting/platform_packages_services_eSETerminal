package org.simalliance.openmobileapi.eseterminal;

import android.app.Service;
import android.content.Intent;
import android.nfc.INfcAdapterExtras;
import android.nfc.NfcAdapter;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.MissingResourceException;
import java.util.NoSuchElementException;


import org.simalliance.openmobileapi.service.CardException;
import org.simalliance.openmobileapi.service.SmartcardError;
import org.simalliance.openmobileapi.service.ITerminalService;

/**
 * Created by sevilser on 18/12/14.
 */
public final class eSETerminal extends Service {

    private static final String TAG = "eSETerminal";

    public static final String eSE_TERMINAL = "eSE";

    private final ITerminalService.Stub mTerminalBinder = new TerminalServiceImplementation();

    private INfcAdapterExtras ex;

    private Binder binder = new Binder();

    private boolean mNFCAdapaterOpennedSuccesful = false;

    @Override
    public IBinder onBind(Intent intent) {
        return mTerminalBinder;
    }

    @Override
    public void onCreate() {
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
                throw new CardException("close SE failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error while closing nfc adapter", e);
        }
        mNFCAdapaterOpennedSuccesful = false;
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
        return eSE_TERMINAL;
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
        public org.simalliance.openmobileapi.service.OpenLogicalChannelResponse internalOpenLogicalChannel(byte[] aid, org.simalliance.openmobileapi.service.SmartcardError error) throws RemoteException {
            byte[] manageChannelCommand = new byte[] {
                    0x00, 0x70, 0x00, 0x00, 0x01
            };
            byte[] rsp = new byte[0];
            try {
                rsp = transmit(manageChannelCommand, 2, 0x9000, 0, "MANAGE CHANNEL", error);
            } catch (org.simalliance.openmobileapi.service.CardException e) {
                Log.e(TAG, "Error while transmitting", e);
                throw new RemoteException();
            }
            if ((rsp.length == 2) && ((rsp[0] == (byte) 0x68) && (rsp[1] == (byte) 0x81))) {
                throw new NoSuchElementException("logical channels not supported");
            }
            if (rsp.length == 2 && (rsp[0] == (byte) 0x6A && rsp[1] == (byte) 0x81)) {
                throw new MissingResourceException("no free channel available", "", "");
            }
            if (rsp.length != 3) {
                throw new MissingResourceException("unsupported MANAGE CHANNEL response data", "", "");
            }
            int channelNumber = rsp[0] & 0xFF;
            if (channelNumber == 0 || channelNumber > 19) {
                throw new MissingResourceException("invalid logical channel number returned", "", "");
            }

            if (aid == null) {
                return new org.simalliance.openmobileapi.service.OpenLogicalChannelResponse(channelNumber, null);
            }

            byte[] selectCommand = new byte[aid.length + 6];
            selectCommand[0] = (byte) channelNumber;
            if (channelNumber > 3) {
                selectCommand[0] |= 0x40;
            }
            selectCommand[1] = (byte) 0xA4;
            selectCommand[2] = 0x04;
            selectCommand[4] = (byte) aid.length;
            System.arraycopy(aid, 0, selectCommand, 5, aid.length);
            try {
                return new org.simalliance.openmobileapi.service.OpenLogicalChannelResponse(channelNumber, transmit(selectCommand, 2, 0x9000, 0xFFFF, "SELECT", error));
            } catch (org.simalliance.openmobileapi.service.CardException exp) {
                Log.e(TAG, "Error while creating openLogicalChannel response", exp);
                internalCloseLogicalChannel(channelNumber, error);
                throw new NoSuchElementException(exp.getMessage());
            }
        }

        @Override
        public void internalCloseLogicalChannel(int channelNumber, org.simalliance.openmobileapi.service.SmartcardError error)
                throws RemoteException {
            if (channelNumber > 0) {
                byte cla = (byte) channelNumber;
                if (channelNumber > 3) {
                    cla |= 0x40;
                }
                byte[] manageChannelClose = new byte[] {
                        cla, 0x70, (byte) 0x80, (byte) channelNumber
                };
                try {
                    transmit(manageChannelClose, 2, 0x9000, 0xFFFF, "MANAGE CHANNEL", error);
                } catch (org.simalliance.openmobileapi.service.CardException e) {
                    Log.e(TAG, "Error while transmitting manage channel", e);
                    throw new RemoteException();
                }
            }
        }

        @Override
        public byte[] internalTransmit(byte[] command, org.simalliance.openmobileapi.service.SmartcardError error) throws RemoteException {
            try {
                Bundle b = ex.transceive("org.simalliance.openmobileapi.service", command);
                if (b == null) {
                    throw new org.simalliance.openmobileapi.service.CardException("exchange APDU failed");
                }
                return b.getByteArray("out");
            } catch (Exception e) {
                Log.e(TAG, "Error while transmit", e);
                error.setError(org.simalliance.openmobileapi.service.CardException.class, "exchange APDU failed");
                throw new RemoteException();
            }
        }

        @Override
        public byte[] getAtr() {
            return null;
        }

        @Override
        public boolean isCardPresent() throws RemoteException {
            try {
                NfcAdapter adapter =  NfcAdapter.getDefaultAdapter(eSETerminal.this);
                if(adapter == null) {
                    throw new org.simalliance.openmobileapi.service.CardException("Cannot get NFC Default Adapter");
                }
                return adapter.isEnabled();
            } catch (Exception e) {
                Log.e(TAG, "Error on NFCAdapter", e);
                return false;
            }
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
         * @throws org.simalliance.openmobileapi.service.CardException if the transmit operation or the minimum response
         *             length check or the status word check failed.
         */
        public synchronized byte[] transmit(
                byte[] cmd,
                int minRspLength,
                int swExpected,
                int swMask,
                String commandName,
                org.simalliance.openmobileapi.service.SmartcardError error)
                throws org.simalliance.openmobileapi.service.CardException {
            byte[] rsp = null;
            try {
                rsp = protocolTransmit(cmd, error);
            } catch (Exception e) {
                if (commandName == null) {
                    throw new org.simalliance.openmobileapi.service.CardException(e.getMessage());
                } else {
                    throw new org.simalliance.openmobileapi.service.CardException(
                            createMessage(commandName, "transmit failed"), e);
                }
            }
            if (minRspLength > 0 && (rsp == null || rsp.length < minRspLength)) {
                throw new org.simalliance.openmobileapi.service.CardException(
                        createMessage(commandName, "response too small"));
            }
            if (swMask != 0) {
                if (rsp == null || rsp.length < 2) {
                    throw new org.simalliance.openmobileapi.service.CardException(
                            createMessage(commandName, "SW1/2 not available"));
                }
                int sw1 = rsp[rsp.length - 2] & 0xFF;
                int sw2 = rsp[rsp.length - 1] & 0xFF;
                int sw = (sw1 << 8) | sw2;
                if ((sw & swMask) != (swExpected & swMask)) {
                    throw new org.simalliance.openmobileapi.service.CardException(createMessage(commandName, sw));
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
         * @throws org.simalliance.openmobileapi.service.CardException if the transmit operation failed.
         */
        protected synchronized byte[] protocolTransmit(byte[] cmd, SmartcardError error)
                throws org.simalliance.openmobileapi.service.CardException {
            byte[] command = cmd;
            byte[] rsp = null;
            try {
                rsp = internalTransmit(command, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error while internal transmit", e);
                throw new org.simalliance.openmobileapi.service.CardException(error.getMessage());
            }

            if (rsp.length >= 2) {
                int sw1 = rsp[rsp.length - 2] & 0xFF;
                int sw2 = rsp[rsp.length - 1] & 0xFF;
                if (sw1 == 0x6C) {
                    command[cmd.length - 1] = rsp[rsp.length - 1];
                    try {
                        rsp = internalTransmit(command, error);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error while internal transmit", e);
                        throw new org.simalliance.openmobileapi.service.CardException(error.getMessage());
                    }
                } else if (sw1 == 0x61) {
                    byte[] getResponseCmd = new byte[] {
                            command[0], (byte) 0xC0, 0x00, 0x00, 0x00
                    };
                    byte[] response = new byte[rsp.length - 2];
                    System.arraycopy(rsp, 0, response, 0, rsp.length - 2);
                    while (true) {
                        getResponseCmd[4] = rsp[rsp.length - 1];
                        try {
                            rsp = internalTransmit(getResponseCmd,error);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error while internal transmit", e);
                            throw new CardException(error.getMessage());
                        }
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
        public byte[] simIOExchange(int fileID, String filePath, byte[] cmd, org.simalliance.openmobileapi.service.SmartcardError error)
                throws RemoteException {
            throw new RemoteException("SIM IO error!");
        }
    }
}
