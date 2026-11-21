package com.fileman.app;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.util.Arrays;

public class TapService extends HostApduService {
    private static byte[] join(byte[] a, byte[] b) {
        byte[] o = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4 || !TapShare.emulating()) return TapShare.NOT_FOUND;
        int ins = apdu[1] & 0xFF;
        if (ins == 0xA4) {
            if (apdu.length < 5 + TapShare.AID.length) return TapShare.NOT_FOUND;
            for (int i = 0; i < TapShare.AID.length; i++) {
                if (apdu[5 + i] != TapShare.AID[i]) return TapShare.NOT_FOUND;
            }
            TapShare.touched();
            return join(new byte[]{(byte) (TapShare.link() == null ? 0 : 1)}, TapShare.OK);
        }
        if (ins == 0xB0) {
            int off = ((apdu[2] & 0xFF) << 8) | (apdu[3] & 0xFF);
            return TapShare.chunk(off);
        }
        return TapShare.UNKNOWN;
    }

    @Override
    public void onDeactivated(int reason) {
    }
}
