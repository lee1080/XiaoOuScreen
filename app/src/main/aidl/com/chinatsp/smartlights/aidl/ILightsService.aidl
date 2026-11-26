package com.chinatsp.smartlights.aidl;

import com.chinatsp.smartlights.aidl.ILightingClient;

interface ILightsService {
    void addLightingClient(ILightingClient client);
    void removeLightingClient(ILightingClient client);
    void refreshUi();
    int getSaveAccompanyMeHome();
    int getSaveAccompanyMeHome2();
    int getSaveLightLaneTurnState();
    int getSaveColorSelector();
    boolean isSaveCollisionReminder();
    boolean isSaveOverSpeedReminder();
    boolean isSaveLeaveRoadReminder();
    boolean isSaveDrivingMode();
    boolean isSaveAirConditionerMode();
    boolean isSaveFatigueReminder();
    boolean isSaveOpenDoorReminder();
    boolean isSaveColorRandom();
    boolean isSaveCallCallReminder();
    boolean isSaveMusicRhythm();
    boolean isSaveVoiceInteraction();
    boolean isSaveHeadlightsSwitch();
    boolean isMusicColorRandomModeEnable();
    void setControlSwitch(int switchType, boolean isOpen);
    void setHeadlampsHeight(int height);
    void setLuminance(int luminance);
    void setColorSelector(int color, boolean isAuto);
    void setAccompanyMeHone(int time);
    void setLightLaneTurnState(int state);
    boolean getSwitchType();
    void setAmbientLightBreathingCycle(int cycle);
    void setVoiceColor(int color);
    void setVoiceBrightness(int brightness);
    void setUserSelectBrightness(int brightness);
    void setUserSelectColor(int color);
    void setMusicColorRandomMode(boolean enable);
}
