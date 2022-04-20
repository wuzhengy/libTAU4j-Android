package io.taucoin.torrent.publishing.ui.transaction;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import com.noober.menu.FloatMenu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.taucoin.torrent.publishing.MainApplication;
import io.taucoin.torrent.publishing.R;
import io.taucoin.torrent.publishing.core.model.data.CommunityAndMember;
import io.taucoin.torrent.publishing.core.model.data.OperationMenuItem;
import io.taucoin.torrent.publishing.core.model.data.TxQueueAndStatus;
import io.taucoin.torrent.publishing.core.model.data.UserAndTx;
import io.taucoin.torrent.publishing.core.model.data.message.TrustContent;
import io.taucoin.torrent.publishing.core.model.data.message.TxType;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.TxConfirm;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.TxQueue;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.User;
import io.taucoin.torrent.publishing.core.utils.ActivityUtil;
import io.taucoin.torrent.publishing.core.utils.ChainIDUtil;
import io.taucoin.torrent.publishing.core.utils.CopyManager;
import io.taucoin.torrent.publishing.core.utils.FmtMicrometer;
import io.taucoin.torrent.publishing.core.utils.KeyboardUtils;
import io.taucoin.torrent.publishing.core.utils.StringUtil;
import io.taucoin.torrent.publishing.core.utils.ToastUtils;
import io.taucoin.torrent.publishing.core.utils.UsersUtil;
import io.taucoin.torrent.publishing.core.utils.ViewUtils;
import io.taucoin.torrent.publishing.databinding.DialogTrustBinding;
import io.taucoin.torrent.publishing.databinding.FragmentTxsTabBinding;
import io.taucoin.torrent.publishing.databinding.TxConfirmDialogBinding;
import io.taucoin.torrent.publishing.ui.BaseActivity;
import io.taucoin.torrent.publishing.ui.BaseFragment;
import io.taucoin.torrent.publishing.ui.community.CommunityViewModel;
import io.taucoin.torrent.publishing.ui.constant.IntentExtra;
import io.taucoin.torrent.publishing.ui.customviews.CommonDialog;
import io.taucoin.torrent.publishing.ui.user.UserDetailActivity;
import io.taucoin.torrent.publishing.ui.user.UserViewModel;

public abstract class CommunityTabFragment extends BaseFragment implements View.OnClickListener,
        CommunityListener {

    protected static final Logger logger = LoggerFactory.getLogger("CommunityTabFragment");
    public static final int TX_REQUEST_CODE = 0x1002;
    public static final int TAB_NOTES = 0;
    public static final int TAB_MARKET = 1;
    public static final int TAB_CHAIN = 2;
    protected BaseActivity activity;
    protected FragmentTxsTabBinding binding;
    protected TxViewModel txViewModel;
    protected UserViewModel userViewModel;
    protected CommunityViewModel communityViewModel;
    protected CompositeDisposable disposables = new CompositeDisposable();
    private FloatMenu operationsMenu;
    private CommonDialog trustDialog;
    private CommonDialog confirmsDialog;
    private Disposable confirmDisposable;

    boolean noBalance = true;
    boolean isJoined = false;
    boolean onChain = false;
    protected String chainID;
    int currentTab;
    int currentPos = 0;
    boolean isScrollToBottom = true;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof BaseActivity)
            activity = (BaseActivity)context;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_txs_tab, container, false);
        binding.setListener(this);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (BaseActivity) getActivity();
        assert activity != null;
        ViewModelProvider provider = new ViewModelProvider(this);
        txViewModel = provider.get(TxViewModel.class);
        userViewModel = provider.get(UserViewModel.class);
        communityViewModel = provider.get(CommunityViewModel.class);
        initParameter();
        initView();
    }

    /**
     * 初始化参数
     */
    private void initParameter() {
        if(getArguments() != null){
            chainID = getArguments().getString(IntentExtra.CHAIN_ID);
            noBalance = getArguments().getBoolean(IntentExtra.NO_BALANCE, true);
            isJoined = getArguments().getBoolean(IntentExtra.IS_JOINED, false);
            onChain = getArguments().getBoolean(IntentExtra.ON_CHAIN, false);
        }
    }

    /**
     * 初始化视图
     */
    public void initView() {
        binding.refreshLayout.setOnRefreshListener(this);

        LinearLayoutManager layoutManager = new LinearLayoutManager(activity);
//        layoutManager.setStackFromEnd(true);
        binding.txList.setLayoutManager(layoutManager);
        binding.txList.setItemAnimator(null);
    }

    final Runnable handleUpdateAdapter = () -> {
        LinearLayoutManager layoutManager = (LinearLayoutManager) binding.txList.getLayoutManager();
        if (layoutManager != null) {
            logger.debug("handleUpdateAdapter isScrollToBottom::{}", isScrollToBottom);
            if (isScrollToBottom) {
                isScrollToBottom = false;
                // 滚动到底部
                int bottomPosition = getItemCount() - 1;
                logger.debug("handleUpdateAdapter scrollToPosition::{}", bottomPosition);
                layoutManager.scrollToPositionWithOffset(bottomPosition, Integer.MIN_VALUE);
            }
        }
    };

    final Runnable handlePullAdapter = () -> {
        LinearLayoutManager layoutManager = (LinearLayoutManager) binding.txList.getLayoutManager();
        if (layoutManager != null) {
            int bottomPosition = getItemCount() - 1;
            int position = bottomPosition - currentPos;
            layoutManager.scrollToPositionWithOffset(position, 0);
        }
    };



    /**
     * 显示每个item长按操作选项对话框
     */
    @Override
    public void onItemLongClicked(TextView view, UserAndTx tx) {
        KeyboardUtils.hideSoftInput(activity);
        List<OperationMenuItem> menuList = new ArrayList<>();
        menuList.add(new OperationMenuItem(R.string.tx_operation_copy));
        final URLSpan[] urls = view.getUrls();
        if (urls != null && urls.length > 0) {
            menuList.add(new OperationMenuItem(R.string.tx_operation_copy_link));
        }
        // 用户不能拉黑自己
        if(StringUtil.isNotEquals(tx.senderPk,
                MainApplication.getInstance().getPublicKey())){
            menuList.add(new OperationMenuItem(R.string.tx_operation_blacklist));
        }
        menuList.add(new OperationMenuItem(tx.pinnedTime <= 0 ? R.string.tx_operation_pin : R.string.tx_operation_unpin));
        if (tx.favoriteTime <= 0) {
            menuList.add(new OperationMenuItem(R.string.tx_operation_favorite));
        }
        menuList.add(new OperationMenuItem(R.string.tx_operation_msg_hash));

        operationsMenu = new FloatMenu(activity);
        operationsMenu.items(menuList);
        operationsMenu.setOnItemClickListener((v, position) -> {
            OperationMenuItem item = menuList.get(position);
            int resId = item.getResId();
            switch (resId) {
                case R.string.tx_operation_copy:
                    CopyManager.copyText(view.getText());
                    ToastUtils.showShortToast(R.string.copy_successfully);
                    break;
                case R.string.tx_operation_copy_link:
                    if (urls != null && urls.length > 0) {
                        String link = urls[0].getURL();
                        CopyManager.copyText(link);
                        ToastUtils.showShortToast(R.string.copy_link_successfully);
                    }
                    break;
                case R.string.tx_operation_blacklist:
                    String publicKey = tx.senderPk;
                    userViewModel.setUserBlacklist(publicKey, true);
                    ToastUtils.showShortToast(R.string.blacklist_successfully);
                    break;
                case R.string.tx_operation_pin:
                case R.string.tx_operation_unpin:
                    txViewModel.setMessagePinned(tx, false);
                    break;
                case R.string.tx_operation_favorite:
                    txViewModel.setMessageFavorite(tx, false);
                    break;
                case R.string.tx_operation_msg_hash:
                    String msgHash = tx.txID;
                    CopyManager.copyText(msgHash);
                    ToastUtils.showShortToast(R.string.copy_message_hash);
                    break;

            }
        });
        operationsMenu.show(activity.getPoint());
    }

    @Override
    public void onItemClicked(TextView view, UserAndTx tx) {
        KeyboardUtils.hideSoftInput(activity);
    }

    @Override
    public void onUserClicked(String senderPk) {
        KeyboardUtils.hideSoftInput(activity);
        Intent intent = new Intent();
        intent.putExtra(IntentExtra.PUBLIC_KEY, senderPk);
        ActivityUtil.startActivity(intent, this, UserDetailActivity.class);
    }

    @Override
    public void onEditNameClicked(String senderPk){
        KeyboardUtils.hideSoftInput(activity);
        String userPk = MainApplication.getInstance().getPublicKey();
        if (StringUtil.isEquals(userPk, senderPk)) {
            userViewModel.showEditNameDialog(activity, senderPk);
        } else {
            userViewModel.showRemarkDialog(activity, senderPk);
        }
    }

    @Override
    public void onBanClicked(UserAndTx tx){
        KeyboardUtils.hideSoftInput(activity);
        String showName = UsersUtil.getShowName(tx.sender, tx.senderPk);
        userViewModel.showBanDialog(activity, tx.senderPk, showName);
    }

    @Override
    public void onTrustClicked(User user) {
        KeyboardUtils.hideSoftInput(activity);
        showTrustDialog(user, null);
    }

    @Override
    public void onItemClicked(UserAndTx tx) {
        KeyboardUtils.hideSoftInput(activity);
        Intent intent = new Intent();
        intent.putExtra(IntentExtra.ID, tx.txID);
        intent.putExtra(IntentExtra.CHAIN_ID, tx.chainID);
        intent.putExtra(IntentExtra.PUBLIC_KEY, tx.senderPk);
        ActivityUtil.startActivity(intent, activity, SellDetailActivity.class);
    }

    @Override
    public void onLinkClick(String link) {
        KeyboardUtils.hideSoftInput(activity);
        ActivityUtil.openUri(activity, link);
    }

    @Override
    public void onResendClick(String txID) {
        KeyboardUtils.hideSoftInput(activity);
        if (confirmDisposable != null) {
            disposables.remove(confirmDisposable);
        }
        confirmDisposable = communityViewModel.observerTxConfirms(txID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(confirms -> {
                    showTxConfirmsDialog(txID, confirms);
                });
        disposables.add(confirmDisposable);
    }

    /**
     * 显示交易确认的对话框
     */
    private void showTxConfirmsDialog(String txID, List<TxConfirm> confirms) {
        int count = null == confirms ? 0 : confirms.size();
        View confirmRoot = null;
        if (null == confirmsDialog || !confirmsDialog.isShowing()) {
            TxConfirmDialogBinding txConfirmBinding = DataBindingUtil.inflate(LayoutInflater.from(activity),
                    R.layout.tx_confirm_dialog, null, false);
            txConfirmBinding.ivClose.setOnClickListener(v -> {
                confirmsDialog.closeDialog();
                if (confirmDisposable != null) {
                    disposables.remove(confirmDisposable);
                }
            });
            confirmRoot = txConfirmBinding.getRoot();
            confirmsDialog = new CommonDialog.Builder(activity)
                    .setContentView(confirmRoot)
                    .setPositiveButton(R.string.common_resend, (dialog, which) -> {
                        dialog.cancel();
                        txViewModel.resendTransaction(txID);
                        if (confirmDisposable != null) {
                            disposables.remove(confirmDisposable);
                        }
                    }).create();
            confirmsDialog.show();
        } else {
            Window window = confirmsDialog.getWindow();
            if (window != null) {
                confirmRoot = window.getDecorView();
            }
        }
        if (confirmRoot != null) {
            TextView tvStatus = confirmRoot.findViewById(R.id.tv_status);
            String status;
            if (count >= 5) {
                status = getString(R.string.tx_confirmed_high);
            } else if (count >= 3) {
                status = getString(R.string.tx_confirmed_middle);
            } else {
                status = getString(R.string.tx_confirmed_low);
            }
            status = getString(R.string.tx_confirmed, status, count);
            tvStatus.setText(Html.fromHtml(status));
        }
    }

    /**
     * 显示信任的对话框
     */
    void showTrustDialog(User user, TxQueueAndStatus txQueue) {
        DialogTrustBinding binding = DataBindingUtil.inflate(LayoutInflater.from(activity),
                R.layout.dialog_trust, null, false);

        String showName = "";
        String trustedPk = "";
        TrustContent content;
        if (user != null) {
            showName = UsersUtil.getShowName(user);
            content = new TrustContent(null, user.publicKey);
        } else {
            showName = UsersUtil.getShowName(null, txQueue.receiverPk);
            content = new TrustContent(txQueue.content);
        }
        Spanned trustTip = Html.fromHtml(getString(R.string.tx_give_trust_tip, showName));
        binding.tvTrustTip.setText(trustTip);
        long txFee = 0;
        if (txQueue != null) {
            txFee = txQueue.fee;
        }
        long medianFee = txViewModel.getTxFee(chainID, TxType.TRUST_TX);
        String txFeeStr = FmtMicrometer.fmtFeeValue(txFee > 0 ? txFee : medianFee);
        binding.tvTrustFee.setTag(R.id.median_fee, medianFee);

        String txFreeHtml = getString(R.string.tx_median_fee, txFeeStr,
                ChainIDUtil.getCoinName(chainID));
        binding.tvTrustFee.setText(Html.fromHtml(txFreeHtml));
        binding.tvTrustFee.setTag(txFeeStr);
        binding.tvTrustFee.setOnClickListener(v -> {
            txViewModel.showEditFeeDialog(activity, binding.tvTrustFee, chainID);
        });

        binding.ivClose.setOnClickListener(v -> trustDialog.closeDialog());
        binding.tvSubmit.setOnClickListener(v -> {
            String fee = ViewUtils.getStringTag(binding.tvTrustFee);
            String senderPk = MainApplication.getInstance().getPublicKey();
            TxQueue tx = new TxQueue(chainID, senderPk, senderPk, 0L,
                    FmtMicrometer.fmtTxLongValue(fee), TxType.TRUST_TX, content.getEncoded());
            if (txQueue != null) {
                tx.queueID = txQueue.queueID;
            }
            if (txViewModel.validateTx(tx)) {
                txViewModel.addTransaction(tx, null == txQueue);
                trustDialog.closeDialog();
            }
        });
        trustDialog = new CommonDialog.Builder(activity)
                .setContentView(binding.getRoot())
                .setCanceledOnTouchOutside(false)
                .enableWarpWidth(true)
                .create();
        trustDialog.show();
    }

    @Override
    public void onClick(View v) {
        closeAllDialog();
        switch (v.getId()) {
            case R.id.ll_pinned_message:
                Intent intent = new Intent();
                intent.putExtra(IntentExtra.CHAIN_ID, chainID);
                intent.putExtra(IntentExtra.TYPE, currentTab);
                ActivityUtil.startActivityForResult(intent, activity, PinnedActivity.class,
                        NotesTabFragment.TX_REQUEST_CODE);
                break;
        }
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
        disposables.clear();
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        closeAllDialog();
    }

    private void closeAllDialog() {
        if (operationsMenu != null) {
            operationsMenu.setOnItemClickListener(null);
            operationsMenu.dismiss();
        }
        if (trustDialog != null) {
            trustDialog.closeDialog();
        }
    }

    public void handleMember(CommunityAndMember member) {
        if (null == binding) {
            return;
        }
        noBalance = !member.onChain() || member.noBalance();
        onChain = member.onChain();
        isJoined = member.isJoined();
    }

    @Override
    public void onRefresh() {
        loadData(getItemCount());
    }

    int getItemCount() {
        return 0;
    }

    protected void loadData(int pos) {
        currentPos = pos;
    }

    void initScrollToBottom() {
        if (!isScrollToBottom) {
            LinearLayoutManager layoutManager = (LinearLayoutManager) binding.txList.getLayoutManager();
            if (layoutManager != null) {
                int lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition();
                int bottomPosition = getItemCount() - 1;
                this.isScrollToBottom = lastVisibleItemPosition <= bottomPosition &&
                        lastVisibleItemPosition >= bottomPosition - 2;
                logger.debug("handleUpdateAdapter lastVisibleItemPosition::{}, bottomPosition::{}",
                        lastVisibleItemPosition, bottomPosition);
            }
        }
    }

    @Override
    public void onFragmentResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onFragmentResult(requestCode, resultCode, data);
        if (requestCode == TX_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            loadData(0);
        }
    }

    public void switchView(int spinnerItem) {
        this.isScrollToBottom = true;
    }

    /**
     * 消除子fragment和父fragment渲染速度不一致，造成的视觉效果
     */
    public void hideView() { }
}
