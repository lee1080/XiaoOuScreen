package com.chinatsp.smartlights.aidl;

interface ILightingClient {
    void onProgressChanged(int type, int progress);
    void onSwitchChanged(int type, boolean isOpen);
}
