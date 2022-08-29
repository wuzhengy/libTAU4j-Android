package io.taucoin.torrent.publishing.core.utils.bus;

import java.util.ArrayList;

import io.taucoin.torrent.publishing.core.model.data.CommunityAndFriend;

public class HomeAllData {

    private ArrayList<CommunityAndFriend> list;

    public HomeAllData(ArrayList<CommunityAndFriend> list) {
        this.list = list;
    }

    public ArrayList<CommunityAndFriend> getList() {
        if (list != null) {
            return list;
        }
        return new ArrayList<>();
    }
}
