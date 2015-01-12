package org.simalliance.openmobileapi.eseterminal;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Created by sevilser on 23/12/14.
 */
public class OpenLogicalChannelResponse implements Parcelable {

    private int mChannelNumber;
    private byte[] mSelectResponse;

    public static final Creator<org.simalliance.openmobileapi.eseterminal.OpenLogicalChannelResponse> CREATOR = new Creator<org.simalliance.openmobileapi.eseterminal.OpenLogicalChannelResponse>() {
        public org.simalliance.openmobileapi.eseterminal.OpenLogicalChannelResponse createFromParcel(Parcel in) {
            return new org.simalliance.openmobileapi.eseterminal.OpenLogicalChannelResponse(in);
        }

        public org.simalliance.openmobileapi.eseterminal.OpenLogicalChannelResponse[] newArray(int size) {
            return new org.simalliance.openmobileapi.eseterminal.OpenLogicalChannelResponse[size];
        }
    };

    public OpenLogicalChannelResponse(int channelNumber, byte[] selectResponse) {
        mChannelNumber = channelNumber;
        mSelectResponse = selectResponse;
    }

    private OpenLogicalChannelResponse(Parcel in) {
        mChannelNumber = in.readInt();
        in.readByteArray(mSelectResponse);
    }

    public int getChannel() {
        return mChannelNumber;
    }

    public byte[] getSelectResponse() {
        return mSelectResponse;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mChannelNumber);
        out.writeByteArray(mSelectResponse);
    }
}
