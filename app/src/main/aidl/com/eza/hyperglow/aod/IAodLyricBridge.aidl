package com.eza.hyperglow.aod;

import android.os.Bundle;
import com.eza.hyperglow.aod.IAodLyricCallback;

interface IAodLyricBridge {
    oneway void registerCallback(IAodLyricCallback callback);
    oneway void unregisterCallback(IAodLyricCallback callback);
    oneway void reportCapabilities(in Bundle report);
}
