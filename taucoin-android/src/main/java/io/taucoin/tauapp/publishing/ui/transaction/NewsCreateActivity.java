package io.taucoin.tauapp.publishing.ui.transaction;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import io.taucoin.tauapp.publishing.MainApplication;
import io.taucoin.tauapp.publishing.R;
import io.taucoin.tauapp.publishing.core.Constants;
import io.taucoin.tauapp.publishing.core.model.data.message.NewsContent;
import io.taucoin.tauapp.publishing.core.model.data.message.TxType;
import io.taucoin.tauapp.publishing.core.storage.RepositoryHelper;
import io.taucoin.tauapp.publishing.core.storage.sp.SettingsRepository;
import io.taucoin.tauapp.publishing.core.storage.sqlite.entity.Member;
import io.taucoin.tauapp.publishing.core.storage.sqlite.entity.TxQueue;
import io.taucoin.tauapp.publishing.core.utils.ChainIDUtil;
import io.taucoin.tauapp.publishing.core.utils.FmtMicrometer;
import io.taucoin.tauapp.publishing.core.utils.StringUtil;
import io.taucoin.tauapp.publishing.core.utils.ToastUtils;
import io.taucoin.tauapp.publishing.core.utils.Utils;
import io.taucoin.tauapp.publishing.core.utils.ViewUtils;
import io.taucoin.tauapp.publishing.databinding.ActivityNewsBinding;
import io.taucoin.tauapp.publishing.ui.BaseActivity;
import io.taucoin.tauapp.publishing.ui.community.CommunityListAdapter;
import io.taucoin.tauapp.publishing.ui.community.CommunityViewModel;
import io.taucoin.tauapp.publishing.ui.constant.IntentExtra;
import io.taucoin.tauapp.publishing.ui.customviews.CommunitiesPopUpDialog;

/**
 * 发布News页面
 */
public class NewsCreateActivity extends BaseActivity implements View.OnClickListener, View.OnFocusChangeListener {

    private ActivityNewsBinding binding;
    private TxViewModel txViewModel;
    private CommunityListAdapter adapter;
    private CommunityViewModel communityViewModel;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private SettingsRepository settingsRepo;
    private String chainID;
    private String repliedHash;
    private String repliedKey;
    private TxQueue txQueue;
    private CharSequence msg;
    private String link;
    private final List<Member> communityList = new ArrayList<>();
    private CommunitiesPopUpDialog popUpDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ViewModelProvider provider = new ViewModelProvider(this);
        txViewModel = provider.get(TxViewModel.class);
        communityViewModel = provider.get(CommunityViewModel.class);
        settingsRepo = RepositoryHelper.getSettingsRepository(getApplicationContext());
        binding = DataBindingUtil.setContentView(this, R.layout.activity_news);
        binding.setListener(this);
        initParameter();
        initLayout();
    }

    /**
     * 初始化参数
     */
    private void initParameter() {
        if (getIntent() != null) {
            // 回复news
            chainID = getIntent().getStringExtra(IntentExtra.CHAIN_ID);
            repliedHash = getIntent().getStringExtra(IntentExtra.HASH);
            repliedKey = getIntent().getStringExtra(IntentExtra.PUBLIC_KEY);
            txQueue = getIntent().getParcelableExtra(IntentExtra.BEAN);
            msg = getIntent().getCharSequenceExtra(IntentExtra.DATA);
            link = getIntent().getStringExtra(IntentExtra.LINK);
        }
    }

    /**
     * 初始化布局
     */
    @SuppressLint("ClickableViewAccessibility")
    private void initLayout() {
        binding.tvBytesCount.setText("0/" + Constants.NEWS_TX_MAX_BYTE_SIZE);
        binding.tvBytesCount.setTextColor(getResources().getColor(R.color.color_gray_dark));
        binding.etNews.addTextChangedListener(newsWatcher);
        binding.etLink.addTextChangedListener(linkWatcher);
        binding.etNews.setMaxBytesLimit(Constants.NEWS_TX_MAX_BYTE_SIZE);

        binding.ivCancel.setOnClickListener(v -> onBackPressed());

        if (txQueue != null) {
            this.chainID = txQueue.chainID;
            NewsContent txContent = new NewsContent(txQueue.content);
            binding.etNews.setText(txContent.getMemo());
            binding.etNews.setSelection(binding.etNews.getText().length());
            binding.etNews.setEnabled(false);

            binding.etLink.setText(txContent.getLinkStr());
            binding.etLink.setSelection(binding.etLink.getText().length());
            binding.etLink.setEnabled(false);
        } else if (StringUtil.isNotEmpty(msg)) {
            binding.etLink.setText(link);
            binding.etNews.setText(msg.toString());
            binding.etNews.setSelection(binding.etNews.getText().length());
            binding.tvPost.setText(R.string.common_retweet);
        }
        binding.etNews.setOnFocusChangeListener(this);
        binding.etLink.setOnFocusChangeListener(this);

        adapter = new CommunityListAdapter();
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        binding.joinedList.setLayoutManager(layoutManager);
        binding.joinedList.setAdapter(adapter);

        binding.llCommunities.setVisibility(StringUtil.isEmpty(repliedHash) ? View.VISIBLE : View.GONE);
        binding.tvInterimBalance.setVisibility(StringUtil.isNotEmpty(repliedHash) ? View.VISIBLE : View.GONE);
        if (StringUtil.isNotEmpty(repliedHash)) {
            binding.etNews.setHint(R.string.tx_your_reply);
        }
    }

    private final TextWatcher newsWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            boolean isEmpty = StringUtil.isEmpty(s);
            binding.tvPost.setEnabled(!isEmpty);
            loadBytesCount();
        }

        @Override
        public void afterTextChanged(Editable s) {

        }
    };

    private final TextWatcher linkWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            loadBytesCount();
        }

        @Override
        public void afterTextChanged(Editable s) {

        }
    };

    private void loadBytesCount() {
        String news = ViewUtils.getText(binding.etNews);
        String link = ViewUtils.getText(binding.etLink);
        byte[] newsBytes = Utils.textStringToBytes(news);
        byte[] linkBytes = Utils.textStringToBytes(link);
        int bytesCount = newsBytes != null ? newsBytes.length : 0;
        bytesCount += linkBytes != null ? linkBytes.length : 0;
        binding.tvBytesCount.setText(bytesCount + "/" + Constants.NEWS_TX_MAX_BYTE_SIZE);
        binding.tvBytesCount.setTextColor(getResources().getColor(bytesCount > 0 ? R.color.color_black :
                R.color.color_gray_dark));
    }

    private void loadFeeView(long averageTxFee) {
        long txFee = 0L;
        if (txQueue != null) {
            txFee = txQueue.fee;
        }
        String txFeeStr = FmtMicrometer.fmtFeeValue(txFee > 0 ? txFee : averageTxFee);
        binding.tvFee.setTag(R.id.median_fee, averageTxFee);

        String txFreeHtml = getString(R.string.tx_median_fee, txFeeStr,
                ChainIDUtil.getCoinName(chainID));
        binding.tvFee.setText(Html.fromHtml(txFreeHtml));
        binding.tvFee.setTag(txFeeStr);
    }

    @Override
    public void onStart() {
        super.onStart();
        txViewModel.getAddState().observe(this, result -> {
            if (StringUtil.isNotEmpty(result)) {
                ToastUtils.showShortToast(result);
            } else {
                setResult(RESULT_OK);
                onBackPressed();
            }
        });

        disposables.add(txViewModel.observeAverageTxFee(chainID, TxType.NEWS_TX)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::loadFeeView));

        if (StringUtil.isEmpty(repliedHash)) {
            disposables.add(communityViewModel.observerJoinedCommunityList()
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(members -> {
                        if (communityList.size() > 0) {
                            return;
                        }
                        if (members != null) {
                            communityList.addAll(members);
                            List<Member> list = new ArrayList<>();
                            for (Member member : members) {
                                if (list.size() < 2) {
                                    list.add(member);
                                }
                            }
                            if (adapter != null) {
                                adapter.submitList(list);
                            }
                            binding.tvMore.setVisibility(communityList.size() > 2 ? View.VISIBLE : View.GONE);
                            binding.joinedList.setVisibility(communityList.size() > 0 ? View.VISIBLE : View.GONE);
                        }
                    }));
        } else {
            disposables.add(communityViewModel.observerCurrentMember(chainID)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(member -> {
                        long balance = ViewUtils.getLongTag(binding.tvInterimBalance);
                        if (member != null && member.getDisplayBalance() != balance) {
                            loadInterimBalanceView(member.getDisplayBalance());
                        }
                    }, it -> {
                        loadInterimBalanceView(0);
                    }));
        }

        handleSettingsChanged(getString(R.string.pref_key_dht_nodes));
        disposables.add(settingsRepo.observeSettingsChanged()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::handleSettingsChanged));
    }

    private void handleSettingsChanged(String key) {
        if (StringUtil.isEquals(key, getString(R.string.pref_key_dht_nodes))) {
            long nodes = settingsRepo.getLongValue(key, 0);
            binding.llLowLinked.setVisibility(nodes < 3 ? View.VISIBLE : View.GONE);
        }
    }

    private void loadInterimBalanceView(long showBalance) {
        binding.tvInterimBalance.setText(getString(R.string.tx_interim_balance,
                FmtMicrometer.fmtFeeValue(showBalance),
                ChainIDUtil.getCoinName(chainID)));
        binding.tvInterimBalance.setTag(showBalance);
    }

    @Override
    protected void onStop() {
        super.onStop();
        disposables.clear();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding.etNews.removeTextChangedListener(newsWatcher);
        binding.etLink.removeTextChangedListener(linkWatcher);
        if (popUpDialog != null && popUpDialog.isShowing()) {
            popUpDialog.closeDialog();
        }
    }

    /**
     * 构建交易数据
     * @return Tx
     */
    private TxQueue buildTx() {
        String senderPk = MainApplication.getInstance().getPublicKey();
        String fee = ViewUtils.getStringTag(binding.tvFee);
        String news = ViewUtils.getText(binding.etNews);
        String link = ViewUtils.getText(binding.etLink);
        NewsContent content = new NewsContent(news, link, repliedHash, repliedKey);
        TxQueue tx = new TxQueue(chainID, senderPk, senderPk, 0L,
                FmtMicrometer.fmtTxLongValue(fee), TxType.NEWS_TX, content.getEncoded());
        if (txQueue != null) {
            tx.queueID = txQueue.queueID;
            tx.queueTime = txQueue.queueTime;
        }
        return tx;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.tv_fee:
                txViewModel.showEditFeeDialog(this, binding.tvFee, chainID);
                break;
            case R.id.tv_post:
                TxQueue tx = buildTx();
                if (txViewModel.validateTx(tx)) {
                    txViewModel.addTransaction(tx, txQueue);
                }
                break;
            case R.id.tv_more:
                showCommunityPopWindow();
                break;
        }
    }

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        switch (v.getId()){
            case R.id.et_news:
                if (hasFocus) {
                    String link = ViewUtils.getText(binding.etLink);
                    byte[] linkBytes = Utils.textStringToBytes(link);
                    int bytesCount = linkBytes != null ? linkBytes.length : 0;
                    int maxBytesLimit = Constants.NEWS_TX_MAX_BYTE_SIZE - bytesCount;
                    binding.etNews.setMaxBytesLimit(maxBytesLimit);
                }
                break;
            case R.id.et_link:
                if (hasFocus) {
                    String news = ViewUtils.getText(binding.etNews);
                    byte[] newsBytes = Utils.textStringToBytes(news);
                    int bytesCount = newsBytes != null ? newsBytes.length : 0;
                    int maxBytesLimit = Constants.NEWS_TX_MAX_BYTE_SIZE - bytesCount;
                    binding.etLink.setMaxBytesLimit(maxBytesLimit);
                }
                break;
        }
    }

    private void showCommunityPopWindow() {
        if (popUpDialog != null && popUpDialog.isShowing()) {
            return;
        }
        popUpDialog = new CommunitiesPopUpDialog.Builder(this)
                .addItems(communityList)
                .setOnItemClickListener((dialog, member) -> {
                    dialog.dismiss();
                    List<Member> currentList = adapter.getCurrentList();
                    if (currentList.size() > 0) {
                        Member firstMember = currentList.get(0);
                        if (StringUtil.isNotEquals(member.chainID, firstMember.chainID)) {
                            List<Member> list = new ArrayList<>();
                            list.add(member);
                            list.add(firstMember);
                            adapter.submitList(list);
                        }
                    }
                    this.chainID = member.chainID;
                    adapter.setChainID(member.chainID);
                    adapter.notifyDataSetChanged();
                }).create();
        popUpDialog.show();
    }
}
