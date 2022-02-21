package io.taucoin.torrent.publishing.core.model.data;

import android.content.Context;

import com.noober.menu.MenuItem;

import io.taucoin.torrent.publishing.MainApplication;

public class OperationMenuItem extends MenuItem {
    private int resId;

    public OperationMenuItem(int resId) {
        Context context = MainApplication.getInstance();
        this.resId = resId;
        setItem(context.getResources().getString(resId));
    }

    public int getResId() {
        return resId;
    }
}
