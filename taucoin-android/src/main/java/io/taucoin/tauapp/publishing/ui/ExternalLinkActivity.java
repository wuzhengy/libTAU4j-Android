package io.taucoin.tauapp.publishing.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import io.taucoin.tauapp.publishing.BuildConfig;
import io.taucoin.tauapp.publishing.core.utils.LinkUtil;
import io.taucoin.tauapp.publishing.ui.constant.IntentExtra;
import io.taucoin.tauapp.publishing.ui.main.MainActivity;

/**
 * 外部点击TAUchain link跳转页面
 */

public class ExternalLinkActivity extends BaseActivity {
    public static final String ACTION_CHAIN_LINK_CLICK = BuildConfig.APPLICATION_ID + ".ui.ACTION_CHAIN_LINK_CLICK";
    public static final String ACTION_AIRDROP_LINK_CLICK = BuildConfig.APPLICATION_ID + ".ui.ACTION_AIRDROP_LINK_CLICK";
    public static final String ACTION_FRIEND_LINK_CLICK = BuildConfig.APPLICATION_ID + ".ui.ACTION_FRIEND_LINK_CLICK";
    public static final String ACTION_ERROR_LINK_CLICK = BuildConfig.APPLICATION_ID + ".ui.ACTION_ERROR_LINK_CLICK";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        setIsFullScreen(false);
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        Uri uri = intent.getData();
        if (null != uri) {
            String urlLink = uri.toString();
            LinkUtil.Link link = LinkUtil.decode(urlLink);
            if (link.isAirdropLink()) {
                onAirdropLinkClick(link);
            } else if (link.isChainLink()) {
                onChainLinkClick(link);
            } else if (link.isFriendLink()) {
                onFriendLinkClick(link);
            } else {
                if (LinkUtil.isTauUrl(urlLink)) {
                    Intent mainIntent = new Intent(this.getApplicationContext(), MainActivity.class);
                    mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    mainIntent.setAction(ACTION_ERROR_LINK_CLICK);
                    this.startActivity(mainIntent);
                }
            }
        }

        finish();
        overridePendingTransition(0, 0);
    }

    private void onAirdropLinkClick(LinkUtil.Link link) {
        Intent mainIntent = new Intent(this.getApplicationContext(), MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mainIntent.setAction(ACTION_AIRDROP_LINK_CLICK);
        mainIntent.putExtra(IntentExtra.LINK, link.getLink());
        this.startActivity(mainIntent);
    }

    private void onChainLinkClick(LinkUtil.Link link) {
        Intent mainIntent = new Intent(this.getApplicationContext(), MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mainIntent.setAction(ACTION_CHAIN_LINK_CLICK);
        mainIntent.putExtra(IntentExtra.LINK, link.getLink());
        this.startActivity(mainIntent);
    }

    private void onFriendLinkClick(LinkUtil.Link link) {
        Intent mainIntent = new Intent(this.getApplicationContext(), MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mainIntent.setAction(ACTION_FRIEND_LINK_CLICK);
        mainIntent.putExtra(IntentExtra.LINK, link.getLink());
        this.startActivity(mainIntent);
    }
}