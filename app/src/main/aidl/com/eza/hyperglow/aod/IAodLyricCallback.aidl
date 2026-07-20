package com.eza.hyperglow.aod;

import android.os.Bundle;

oneway interface IAodLyricCallback {
    void onState(in Bundle state);
    void onConfiguration(in Bundle configuration);
}
