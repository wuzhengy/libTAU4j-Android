package io.taucoin.tauapp.publishing.ui.community;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.tabs.TabLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import io.taucoin.tauapp.publishing.R;
import io.taucoin.tauapp.publishing.core.model.TauDaemon;
import io.taucoin.tauapp.publishing.core.model.TauDaemonAlertHandler;
import io.taucoin.tauapp.publishing.core.model.data.Statistics;
import io.taucoin.tauapp.publishing.core.storage.RepositoryHelper;
import io.taucoin.tauapp.publishing.core.storage.sp.SettingsRepository;
import io.taucoin.tauapp.publishing.core.utils.ActivityUtil;
import io.taucoin.tauapp.publishing.core.utils.ChainIDUtil;
import io.taucoin.tauapp.publishing.core.utils.KeyboardUtils;
import io.taucoin.tauapp.publishing.core.utils.StringUtil;
import io.taucoin.tauapp.publishing.databinding.ExternalAirdropLinkDialogBinding;
import io.taucoin.tauapp.publishing.databinding.FragmentCommunityBinding;
import io.taucoin.tauapp.publishing.ui.BaseFragment;
import io.taucoin.tauapp.publishing.ui.customviews.CommonDialog;
import io.taucoin.tauapp.publishing.ui.customviews.FragmentStatePagerAdapter;
import io.taucoin.tauapp.publishing.ui.transaction.CommunityTabFragment;
import io.taucoin.tauapp.publishing.ui.constant.IntentExtra;
import io.taucoin.tauapp.publishing.ui.main.MainActivity;
import io.taucoin.tauapp.publishing.ui.transaction.MarketTabFragment;
import io.taucoin.tauapp.publishing.ui.transaction.NotesTabFragment;
import io.taucoin.tauapp.publishing.ui.transaction.TransactionsTabFragment;

/**
 * 单个群组页面
 */
public class CommunityFragment extends BaseFragment implements View.OnClickListener {

    public static final int MEMBERS_REQUEST_CODE = 0x100;
    private MainActivity activity;
    private FragmentCommunityBinding binding;
    private CommunityViewModel communityViewModel;
    private SettingsRepository settingsRepo;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final CommunityTabFragment[] fragments = new CommunityTabFragment[3];
    private String chainID;
    private boolean nearExpired = false;
    private boolean chainStopped = false;
    private boolean isJoined = false;
    private boolean isNoBalance = true;
    private Statistics memberStatistics;
    private int onlinePeers;
    private long nodes = 0;
    private long miningTime = -1;
    private CommonDialog chainStoppedDialog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_community, container, false);
        binding.setListener(this);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (MainActivity) getActivity();
        ViewModelProvider provider = new ViewModelProvider(this);
        communityViewModel = provider.get(CommunityViewModel.class);
        settingsRepo = RepositoryHelper.getSettingsRepository(activity.getApplicationContext());
        binding.setListener(this);
        binding.toolbarInclude.setListener(this);
        initParameter();
        initLayout();
        communityViewModel.connectChain(chainID);
    }

    /**
     * 初始化参数
     */
    private void initParameter() {
        if (getArguments() != null) {
            chainID = getArguments().getString(IntentExtra.ID);
        }
    }

    /**
     * 初始化布局
     */
    private void initLayout() {
        Context context = activity.getApplicationContext();
        TauDaemonAlertHandler tauDaemonHandler = TauDaemon.getInstance(context).getTauDaemonHandler();
        this.onlinePeers = tauDaemonHandler.getOnlinePeersCount(chainID);
        showCommunityTitle();
        showCommunitySubtitle();
        binding.toolbarInclude.ivBack.setOnClickListener(v -> {
            KeyboardUtils.hideSoftInput(activity);
            activity.goBack();
        });
        binding.toolbarInclude.tvSubtitle.setVisibility(View.VISIBLE);
        binding.toolbarInclude.ivAction.setVisibility(View.VISIBLE);
        binding.toolbarInclude.ivAction.setImageResource(R.mipmap.icon_community_detail);
        binding.toolbarInclude.ivAction.setOnClickListener(v -> {
            if (StringUtil.isEmpty(chainID)) {
                return;
            }
            Intent intent = new Intent();
            intent.putExtra(IntentExtra.CHAIN_ID, chainID);
            intent.putExtra(IntentExtra.IS_JOINED, isJoined);
            intent.putExtra(IntentExtra.NO_BALANCE, isNoBalance);
            ActivityUtil.startActivityForResult(intent, activity, CommunityDetailActivity.class, MEMBERS_REQUEST_CODE);
        });

        // 自定义的Adapter继承自FragmentPagerAdapter
        StateAdapter stateAdapter = new StateAdapter(this.getChildFragmentManager(),
                binding.tabLayout.getTabCount());
        // ViewPager设置Adapter
        binding.viewPager.setAdapter(stateAdapter);
        binding.viewPager.setOffscreenPageLimit(3);
        binding.tabLayout.setupWithViewPager(binding.viewPager);

        binding.tabLayout.addOnTabSelectedListener(onTabSelectedListener);

        // 检测区块链是否因为获取数据失败而停止
        tauDaemonHandler.getChainStoppedData()
                .observe(this.getViewLifecycleOwner(), set -> {
                    boolean chainStopped = set != null && set.contains(chainID);
                    if (this.chainStopped != chainStopped) {
                        this.chainStopped = chainStopped;
                        showWarningView();
                    }
                });

        tauDaemonHandler.getOnlinePeerData()
                .observe(this.getViewLifecycleOwner(), set -> {
                    int peers = tauDaemonHandler.getOnlinePeersCount(chainID);
                    if (this.onlinePeers != peers) {
                        this.onlinePeers = peers;
//                        showCommunitySubtitle();
                    }
                });
    }

    private final TabLayout.OnTabSelectedListener onTabSelectedListener = new TabLayout.OnTabSelectedListener() {

        @Override
        public void onTabSelected(TabLayout.Tab tab) {
            int currentTab = tab.getPosition();
            int currentItem = binding.viewPager.getCurrentItem();
            if (currentTab != currentItem) {
                binding.viewPager.setCurrentItem(currentTab);
            }
            KeyboardUtils.hideSoftInput(activity);
        }

        @Override
        public void onTabUnselected(TabLayout.Tab tab) {

        }

        @Override
        public void onTabReselected(TabLayout.Tab tab) {

        }
    };

    private void handleSettingsChanged(String key) {
        if (StringUtil.isEquals(key, getString(R.string.pref_key_dht_nodes))) {
            nodes = settingsRepo.getLongValue(key, 0);
//            showCommunitySubtitle();
        }
    }

    /**
     * 显示顶部警告视图
     */
    private void showWarningView() {
        binding.llWarning.setVisibility(chainStopped || nearExpired ? View.VISIBLE : View.GONE);
        if (chainStopped) {
            binding.tvWarning.setText(R.string.community_stopped_running_tips);
        } else if (nearExpired) {
            binding.tvWarning.setText(R.string.community_near_expiry_tips);
        }
    }

    private void showCommunityTitle() {
        long total = 0;
        if (memberStatistics != null) {
            total = memberStatistics.getTotal();
        }
        if (StringUtil.isNotEmpty(chainID)) {
            String communityName = ChainIDUtil.getName(chainID);
            String communityTitle = getString(R.string.community_title, communityName, total);
            binding.toolbarInclude.tvTitle.setText(communityTitle);
        }
    }

    private void showCommunitySubtitle() {
        StringBuilder subtitle = new StringBuilder();
        if (memberStatistics != null) {
            long members = memberStatistics.getOnChain();
            if (members > 0) {
                subtitle.append(getString(R.string.community_users_stats_m, members));
            }
        }
        if (onlinePeers > 0) {
            subtitle.append(getString(R.string.community_users_stats_c, onlinePeers));
        }
        if (isJoined) {
            // 已加入社区
            if (nodes > 0) {
                if (miningTime >= 0) {
                    long minutes = miningTime / 60;
                    long seconds = miningTime % 60;
                    subtitle.append(getString(R.string.community_users_mining));
                    if (minutes > 0) {
                        subtitle.append(getString(R.string.chain_mining_time_min_seconds,
                                minutes, seconds));
                    } else {
                        subtitle.append(getString(R.string.chain_mining_time_seconds, seconds));
                    }
                } else if (miningTime == -100) {
                    subtitle.append(getString(R.string.community_users_doze));
                } else {
                    // 链业务已停止
                    int length = subtitle.length();
                    if (length > 0) {
                        subtitle.delete(length - 2, length - 1);
                    }
                }
            } else {
                subtitle.append(getString(R.string.community_users_discovering));
            }
        } else {
            // 未加入社区
            int length = subtitle.length();
            if (length > 0) {
                subtitle.delete(length - 2, length - 1);
            }
        }
        binding.toolbarInclude.tvSubtitle.setText(subtitle);
    }

    @Override
    public void onStart() {
        super.onStart();
        subscribeCommunityViewModel();
    }

    @Override
    public void onStop() {
        super.onStop();
        disposables.clear();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        binding.tabLayout.removeOnTabSelectedListener(onTabSelectedListener);
        if (chainStoppedDialog != null && chainStoppedDialog.isShowing()) {
            chainStoppedDialog.closeDialog();
        }
    }

    /**
     * 订阅社区相关的被观察者
     */
    private void subscribeCommunityViewModel() {
        communityViewModel.getSetBlacklistState().observe(this, state -> {
            if(state){
                activity.goBack();
            }
        });

        disposables.add(communityViewModel.observerCommunityMiningTime(chainID)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(time -> {
                    this.miningTime = time;
                    showCommunitySubtitle();
                }, it->{}));

//        // 60s更新检查一次
//        disposables.add(ObservableUtil.intervalSeconds(60)
//                .subscribeOn(Schedulers.io())
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe(l -> showCommunitySubtitle()));

        nodes = settingsRepo.getLongValue(getString(R.string.pref_key_dht_nodes), 0);
        disposables.add(settingsRepo.observeSettingsChanged()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::handleSettingsChanged));

        disposables.add(communityViewModel.getMembersStatistics(chainID)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(statistics -> {
                    this.memberStatistics = statistics;
                    showCommunityTitle();
//                    showCommunitySubtitle();
                }));

        disposables.add(communityViewModel.observerCurrentMember(chainID)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(member -> {
                    if (isJoined != member.isJoined()) {
                        isJoined = member.isJoined();
//                        showCommunitySubtitle();
                    }
                    if (nearExpired != member.nearExpired()) {
                        nearExpired = member.nearExpired();
                        showWarningView();
                    }
                    isNoBalance = member.noBalance();
                    for (CommunityTabFragment fragment: fragments) {
                        if (fragment != null) {
                            fragment.handleMember(member);
                        }
                    }
                    binding.flJoin.setVisibility(member.isJoined() ? View.GONE : View.VISIBLE);
                }, it -> {}));
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.tv_join:
                communityViewModel.joinCommunity(chainID);
                break;
            case R.id.ll_warning:
                showWarningDialog();
                break;
        }
    }

    /**
     * 显示警告的对话框
     */
    private void showWarningDialog() {
        if (chainStoppedDialog != null && chainStoppedDialog.isShowing()) {
            return;
        }
        Context context = activity.getApplicationContext();
        ExternalAirdropLinkDialogBinding dialogBinding = DataBindingUtil.inflate(LayoutInflater.from(context),
                R.layout.external_airdrop_link_dialog, null, false);
        if (chainStopped) {
            dialogBinding.tvPeer.setText(R.string.community_stopped_running);
            dialogBinding.tvJoin.setText(R.string.common_retry);
        } else {
            dialogBinding.tvPeer.setText(R.string.community_near_expiry);
            dialogBinding.tvJoin.setVisibility(View.GONE);
        }

        dialogBinding.tvPeer.setTextColor(context.getResources().getColor(R.color.color_black));
        dialogBinding.tvSkip.setOnClickListener(view -> {
            if (chainStoppedDialog != null) {
                chainStoppedDialog.closeDialog();
            }
        });
        dialogBinding.tvJoin.setOnClickListener(view -> {
            if (chainStoppedDialog != null) {
                chainStoppedDialog.closeDialog();
            }
            TauDaemon.getInstance(activity.getApplicationContext()).restartFailedChain(chainID);
        });
        chainStoppedDialog = new CommonDialog.Builder(activity)
                .setContentView(dialogBinding.getRoot())
                .setCanceledOnTouchOutside(false)
                .create();
        chainStoppedDialog.show();
    }

    @Override
    public void onFragmentResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onFragmentResult(requestCode, resultCode, data);
        for (CommunityTabFragment fragment : fragments) {
            if (fragment != null) {
                fragment.onFragmentResult(requestCode, resultCode, data);
            }
        }
    }

    private CommunityTabFragment createFragmentView(int position) {
        int pos = position < binding.tabLayout.getTabCount() ? position : 0;
        CommunityTabFragment tab;
        if (pos == 0) {
            tab = new NotesTabFragment();
        } else if (pos == 1) {
            tab = new MarketTabFragment();
        } else {
            tab = new TransactionsTabFragment();
        }
        fragments[pos] = tab;

        Bundle bundle = new Bundle();
        bundle.putString(IntentExtra.CHAIN_ID, chainID);
        bundle.putBoolean(IntentExtra.IS_JOINED, isJoined);
        tab.setArguments(bundle);
        return tab;
    }

    public class StateAdapter extends FragmentStatePagerAdapter {

        StateAdapter(@NonNull FragmentManager fm, int count) {
            super(fm, count);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            return createFragmentView(position);
        }

        @Nullable
        @Override
        public CharSequence getPageTitle(int position) {
            if (position == 1) {
                return getString(R.string.community_chain_market);
            } else if (position == 2) {
                return getString(R.string.community_on_chain);
            } else {
                return getString(R.string.community_chain_note);
            }
        }
    }
}