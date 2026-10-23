package com.chinatsp.threescreenmiddleservice;

import com.chinatsp.threescreenmiddleservice.INotifyShowInterface;

interface ITSmiddleService {
    void notifyShowView(String type);
    void notifyHideView(String type);
    void registerNotifyListener(INotifyShowInterface listener);
    void unRegisterNotifyListener(INotifyShowInterface listener);
    void sendViewShowMessage(String type, boolean show);
    void sendViewHideMessage(String type);
    String getCurrentView();
    void requestViewShow(String type);
    void registerListenerByPackage(INotifyShowInterface listener, String pkg);
    void unRegisterListenerByPackage(INotifyShowInterface listener, String pkg);
    boolean isHighWindowView();
}
