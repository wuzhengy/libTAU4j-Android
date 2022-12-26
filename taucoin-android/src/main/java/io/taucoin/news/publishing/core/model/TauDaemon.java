package io.taucoin.news.publishing.core.model;

import android.content.Context;
import android.content.Intent;

import org.libTAU4j.Account;
import org.libTAU4j.Block;
import org.libTAU4j.Message;
import org.libTAU4j.SessionManager;
import org.libTAU4j.SessionParams;
import org.libTAU4j.Transaction;
import org.libTAU4j.alerts.Alert;
import org.libTAU4j.alerts.AlertType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.NonNull;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.taucoin.news.publishing.BuildConfig;
import io.taucoin.news.publishing.R;
import io.taucoin.news.publishing.core.Constants;
import io.taucoin.news.publishing.core.log.LogUtil;
import io.taucoin.news.publishing.core.model.data.AlertAndUser;
import io.taucoin.news.publishing.core.model.data.message.DataKey;
import io.taucoin.news.publishing.core.storage.RepositoryHelper;
import io.taucoin.news.publishing.core.storage.sp.SettingsRepository;
import io.taucoin.news.publishing.core.storage.sqlite.entity.Community;
import io.taucoin.news.publishing.core.storage.sqlite.entity.TxQueue;
import io.taucoin.news.publishing.core.storage.sqlite.entity.User;
import io.taucoin.news.publishing.core.storage.sqlite.repo.CommunityRepository;
import io.taucoin.news.publishing.core.storage.sqlite.repo.MemberRepository;
import io.taucoin.news.publishing.core.storage.sqlite.repo.UserRepository;
import io.taucoin.news.publishing.core.utils.ChainIDUtil;
import io.taucoin.news.publishing.core.utils.DateUtil;
import io.taucoin.news.publishing.core.utils.DeviceUtils;
import io.taucoin.news.publishing.core.utils.FileUtil;
import io.taucoin.news.publishing.core.utils.LinkUtil;
import io.taucoin.news.publishing.core.utils.LocationManagerUtil;
import io.taucoin.news.publishing.core.utils.NetworkSetting;
import io.taucoin.news.publishing.core.utils.ObservableUtil;
import io.taucoin.news.publishing.core.utils.SessionStatistics;
import io.taucoin.news.publishing.core.utils.StringUtil;
import io.taucoin.news.publishing.core.utils.TrafficUtil;
import io.taucoin.news.publishing.core.utils.Utils;
import io.taucoin.news.publishing.core.utils.rlp.ByteUtil;
import io.taucoin.news.publishing.receiver.ConnectionReceiver;
import io.taucoin.news.publishing.receiver.PowerReceiver;
import io.taucoin.news.publishing.service.SystemServiceManager;
import io.taucoin.news.publishing.service.TauService;
import io.taucoin.news.publishing.ui.TauNotifier;
import io.taucoin.news.publishing.ui.chat.ChatViewModel;
import io.taucoin.news.publishing.ui.transaction.TxViewModel;

/**
 * 区块链业务Daemon
 */
public abstract class TauDaemon {
    private static final String TAG = TauDaemon.class.getSimpleName();
    static final Logger logger = LoggerFactory.getLogger(TAG);
    static final int ALERT_QUEUE_CAPACITY = 10000;              // Alert缓存队列
    private static volatile TauDaemon instance;

    Context appContext;
    private final SettingsRepository settingsRepo;
    CompositeDisposable disposables = new CompositeDisposable();
    private final PowerReceiver powerReceiver = new PowerReceiver();
    private final ConnectionReceiver connectionReceiver = new ConnectionReceiver();
    SessionManager sessionManager;
    private final SystemServiceManager systemServiceManager;
    private final TauInfoProvider tauInfoProvider;
    private final LocationManagerUtil locationManager;
    private Disposable updateBootstrapIntervalTimer; // 更新BootstrapInterval定时任务
    private Disposable updateLocationTimer;          // 更新位置信息定时任务
    private Disposable onlineTimer;                  // 触发在线信号定时任务
    private Disposable chargingTimer;                // 触发充电5分钟计时任务
    TauDaemonAlertHandler tauDaemonAlertHandler;     // libTAU上报的Alert处理程序
    private final TxQueueManager txQueueManager;     // 交易队列管理
    private final MyAccountManager myAccountManager; // 社区我的账户管理
    volatile boolean isRunning = false;
    volatile String seed;
    String deviceID;

    private boolean chargingTimingCompleted = false;  // 充电5分钟计时完成

    // libTAU上报的Alert缓存队列
    LinkedBlockingQueue<AlertAndUser> alertQueue = new LinkedBlockingQueue<>();
    AtomicBoolean isSessionStopped = new AtomicBoolean(false);

    public static TauDaemon getInstance(@NonNull Context appContext) {
        if (instance == null) {
            synchronized (TauDaemon.class) {
                if (instance == null)
                    instance = new TauDaemonImpl(appContext);
            }
        }
        return instance;
    }

    /**
     * TauDaemon构造函数
     */
    TauDaemon(@NonNull Context appContext) {
        this.appContext = appContext;
        settingsRepo = RepositoryHelper.getSettingsRepository(appContext);
        systemServiceManager = SystemServiceManager.getInstance();
        tauInfoProvider = TauInfoProvider.getInstance(this);
        locationManager = new LocationManagerUtil(appContext);
        deviceID = DeviceUtils.getCustomDeviceID(appContext);
        sessionManager = new SessionManager(true);
        txQueueManager = new TxQueueManager(this);
        myAccountManager = new MyAccountManager();

        observeTauDaemon();
        initLocalParam();
    }

    private void observeTauDaemon() {
        // 监听libTAU启动成功
        sessionManager.addListener(new TauDaemonAlertListener() {

            @Override
            public int[] types() {
                return new int[]{AlertType.SES_START_OVER.swig()};
            }

            @Override
            public void alert(Alert<?> alert) {
                if (alert != null && alert.type() == AlertType.SES_START_OVER) {
                    logger.info("Tau start successfully");
                    isRunning = true;
                    // libTAU删除此限制
//                    Constants.MAX_ACCOUNT_SIZE = sessionManager.getMaxAccountSize();
                    Constants.CHAIN_EPOCH_BLOCK_SIZE = sessionManager.getChainEpochBlockSize();
                    Constants.TX_MAX_BYTE_SIZE = sessionManager.getMaxTxEncodedSize();
                    Constants.TX_MAX_OVERDRAFT = sessionManager.getMaxOverdraft();
                    handleSettingsChanged(appContext.getString(R.string.pref_key_foreground_running));
                    // 更新当前用户自己的信息
                    updateCurrentUserInfo(true);
                    // 启动在线信号定时任务
                    startOnlineTimer();
                    // 更新用户跟随的社区和其账户状态
                    updateChainsAndAccountInfo();
                    // 防止Crash后重启 初始化来不及设置新的日志等级
                    setLogLevel(LogUtil.getTauLogLevel());
                    // 重发点对点消息
                    startDataResend();
                }
            }
        });
        observeTauDaemonAlertListener();
        disposables.add(settingsRepo.observeSettingsChanged()
                .subscribe(this::handleSettingsChanged));
        disposables.add(tauInfoProvider.observeAppStatistics()
                .subscribeOn(Schedulers.io())
                .subscribe());
        disposables.add(tauInfoProvider.observeCPUStatistics()
                .subscribeOn(Schedulers.io())
                .subscribe());
    }

    /**
     * 初始化本地参数
     */
    private void initLocalParam() {
        switchPowerReceiver();
        switchConnectionReceiver();
        TrafficUtil.resetTrafficTotalOld();
        settingsRepo.initData();
    }

    /**
     * Daemon启动
     */
    public void start() {
        if (isRunning){
            return;
        }
        Intent intent = new Intent(appContext, TauService.class);
        Utils.startServiceBackground(appContext, intent);
    }

    /**
     * 观察是否需要启动Daemon
     * @return Flowable
     */
    public Flowable<Boolean> observeNeedStartDaemon() {
        return Flowable.create((emitter) -> {
            if (!emitter.isCancelled()) {
                emitter.onNext(!isRunning);
                if (!isRunning) {
                    while (!Thread.interrupted() && !emitter.isCancelled()) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            break;
                        }
                        if (emitter.isCancelled() || isRunning) {
                            break;
                        }
                        emitter.onNext(true);
                    }
                }
            }
            emitter.onComplete();
        }, BackpressureStrategy.LATEST);
    }

    /**
     * Only calls from TauService
     */
    public void doStart(String seed) {
        logger.info("doStart");
        if (isRunning)
            return;

        // 设置SessionManager启动参数
        SessionParams sessionParams = SessionSettings.getSessionParamsBuilder()
                .setAccountSeed(seed)
                .setDeviceID(deviceID)
                .setDatabaseDir(appContext.getApplicationInfo().dataDir) // 数据库目录
                .setDumpfileDir(FileUtil.getDumpfileDir())  // Dump File目录
                .setDhtNonReferable(true)
                .setDhtAutoRelay(false)
                .setDhtPingInterval(3600)
                .setDhtBootstrapInterval(10)
                .setLogLevel(LogUtil.getTauLogLevel())
                .build();
        sessionManager.start(sessionParams);
    }

    /**
     * Session通知结束
     */
    abstract void sessionStopOver();

    /**
     * Only calls from TauService
     * Session停止结束方案：直接是stop()方法执行结束，就认为Session内存已释放结束；
     *
     * TODO:: 这种方案是否可行，有待商榷
     */
    public void doStop() {
        if (!isRunning)
            return;
        isRunning = false;
        disposables.clear();
        if (updateLocationTimer != null && !updateLocationTimer.isDisposed()) {
            updateLocationTimer.dispose();
        }
        if (onlineTimer != null && !onlineTimer.isDisposed()) {
            onlineTimer.dispose();
        }
        if (updateBootstrapIntervalTimer != null && !updateBootstrapIntervalTimer.isDisposed()) {
            updateBootstrapIntervalTimer.dispose();
        }
        if (chargingTimer != null && !chargingTimer.isDisposed()) {
            chargingTimer.dispose();
        }
        if (msgResendDisposable != null && !msgResendDisposable.isDisposed()) {
            msgResendDisposable.dispose();
        }
        TauNotifier.getInstance().cancelAllNotify();
        locationManager.stopLocation();
        appContext.unregisterReceiver(powerReceiver);
        appContext.unregisterReceiver(connectionReceiver);
        sessionManager.stop();
        tauDaemonAlertHandler.onCleared();
        txQueueManager.onCleared();
        myAccountManager.onCleared();
        sessionStopOver();
    }

    /**
     * 强制停止
     */
    public void forceStop() {
        Intent i = new Intent(appContext, TauService.class);
        i.setAction(TauService.ACTION_SHUTDOWN);
        Utils.startServiceBackground(appContext, i);
    }

    /**
     * 注册Alert监听事件
     * @param listener TauDaemonAlertListener
     */
    void registerAlertListener(TauDaemonAlertListener listener) {
        sessionManager.addListener(listener);
    }

    /**
     * 反注册Alert监听事件
     * @param listener TauDaemonAlertListener
     */
    void unregisterAlertListener(TauDaemonAlertListener listener) {
        sessionManager.removeListener(listener);
    }

    /**
     * 电源充电状态切换广播接受器
     */
    private void switchPowerReceiver() {
        boolean chargingState = systemServiceManager.isPlugged();
        settingsRepo.chargingState(chargingState);
        if (chargingState) {
            startChargingTiming();
        }
        try {
            appContext.unregisterReceiver(powerReceiver);
        } catch (IllegalArgumentException ignore) {
            /* Ignore non-registered receiver */
        }
        appContext.registerReceiver(powerReceiver, PowerReceiver.getCustomFilter());
    }

    /**
     * 网络连接切换广播接受器
     */
    private void switchConnectionReceiver() {
        settingsRepo.internetState(systemServiceManager.isHaveNetwork());
        settingsRepo.setInternetType(systemServiceManager.getInternetType());
        NetworkSetting.setMeteredNetwork(systemServiceManager.isNetworkMetered());
        NetworkSetting.setWiFiNetwork(systemServiceManager.isWiFi());
        try {
            appContext.unregisterReceiver(connectionReceiver);
        } catch (IllegalArgumentException ignore) {
            /* Ignore non-registered receiver */
        }
        appContext.registerReceiver(connectionReceiver, ConnectionReceiver.getFilter());
    }

    /**
     * 处理设置的改变
     * @param key 存储key
     */
    private void handleSettingsChanged(String key) {
        if (key.equals(appContext.getString(R.string.pref_key_internet_state))) {
            logger.info("SettingsChanged, internet state::{}", settingsRepo.internetState());
        } else if (key.equals(appContext.getString(R.string.pref_key_internet_type))) {
            logger.info("SettingsChanged, internet type::{}", settingsRepo.getInternetType());
            reopenNetworkSockets();
        } else if (key.equals(appContext.getString(R.string.pref_key_charging_state))) {
            logger.info("SettingsChanged, charging state::{}", settingsRepo.chargingState());
            startChargingTiming();
            resetFrequencyMode();
        } else if (key.equals(appContext.getString(R.string.pref_key_is_metered_network))) {
            logger.info("isMeteredNetwork::{}", NetworkSetting.isMeteredNetwork());
        } else if (key.equals(appContext.getString(R.string.pref_key_is_wifi_network))) {
            logger.info("isWiFiNetwork::{}", NetworkSetting.isWiFiNetwork());
        } else if (key.equals(appContext.getString(R.string.pref_key_foreground_running))) {
            boolean isForeground = settingsRepo.getBooleanValue(key);
            logger.info("foreground running::{}", isForeground);
            resetFrequencyMode();
        } else if (key.equals(appContext.getString(R.string.pref_key_nat_pmp_mapped))) {
            logger.info("SettingsChanged, Nat-PMP mapped::{}", settingsRepo.isNATPMPMapped());
        } else if (key.equals(appContext.getString(R.string.pref_key_upnp_mapped))) {
            logger.info("SettingsChanged, UPnP mapped::{}", settingsRepo.isUPnpMapped());
        } else if (key.equals(appContext.getString(R.string.pref_key_dht_nodes))) {
            long nodes = settingsRepo.getLongValue(key, 0);
            logger.info("SettingsChanged, nodes::{}", nodes);
        }
    }

    /**
     * 更新libTAU监听接口
     * 必须有网络才会更新
     */
    private void reopenNetworkSockets() {
        if (!settingsRepo.internetState()) {
            return;
        }
        if (!isRunning) {
            // 必须有用户seed才能启动
            if (StringUtil.isNotEmpty(seed)) {
                doStart(seed);
            }
        } else {
            SystemServiceManager.getInstance().getNetworkAddress();
            sessionManager.reopenNetworkSockets();

            resetAutoRelayDelay();
            logger.info("Network change reopen network sockets...");
        }
    }

    /**
     * 统计Sessions的nodes数
     */
    long getSessionNodes() {
        if (!isRunning) {
            return 0;
        }
        return sessionManager.dhtNodes();
    }

    /**
     * 统计Sessions的nodes数
     */
    long getInvokedRequests() {
        if (!isRunning) {
            return 0;
        }
        return sessionManager.invokedRequests();
    }

    /**
     * 获取Sessions的流量统计
     */
    void getSessionStatistics(@NonNull SessionStatistics statistics) {
        if (isRunning) {
            statistics.setTotalUpload(sessionManager.totalUpload());
            statistics.setTotalDownload(sessionManager.totalDownload());
            statistics.setUploadRate(sessionManager.downloadRate());
            statistics.setDownloadRate(sessionManager.uploadRate());
        }
    }

    /**
     * 启动在线信号定时任务
     */
    public void startOnlineTimer() {
        if (null == onlineTimer || onlineTimer.isDisposed()) {
            onlineTimer = ObservableUtil.intervalSeconds(12 * 60 * 60)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe( l -> updateCurrentUserInfo(false));
        }
    }

    /**
     * 更新当前用户信息
     * @param isUpdateLocation 是否只更新位置信息
     */
    public void updateCurrentUserInfo(boolean isUpdateLocation) {
        Disposable disposable = Observable.create((ObservableOnSubscribe<Void>) emitter -> {
            UserRepository userRepo = RepositoryHelper.getUserRepository(appContext);
            User user = userRepo.getCurrentUser();
            if (user != null) {
                if (isUpdateLocation) {
                    double longitude = locationManager.getLongitude();
                    double latitude = locationManager.getLatitude();
                    if ((longitude > 0 && user.longitude != longitude) ||
                            (latitude > 0 && user.latitude != latitude)) {
                        user.longitude = longitude;
                        user.latitude = latitude;
                        user.updateLocationTime = getSessionTime() / 1000;
                        userRepo.updateUser(user);
                    }
                } else {
                    updateUserInfo(user);
                }
            }
            emitter.onComplete();
        }).subscribeOn(Schedulers.io())
                .subscribe();
        disposables.add(disposable);
    }

    /**
     * 更新用户跟随的社区和其账户状态
     */
    private void updateChainsAndAccountInfo() {
        Disposable disposable = Observable.create((ObservableOnSubscribe<Void>) emitter -> {
            UserRepository userRepo = RepositoryHelper.getUserRepository(appContext);
            MemberRepository memberRepo = RepositoryHelper.getMemberRepository(appContext);
            CommunityRepository communityRepo = RepositoryHelper.getCommunityRepository(appContext);
            User user = userRepo.getCurrentUser();
            if (user != null) {
                String userPk = user.publicKey;
                // 1、检查本地followed的社区和libTAU中followed的社区数据是否一致
                List<String> tauChains = getTauAllChains();
                List<String> localChains = memberRepo.queryFollowedCommunities(userPk);
                logger.info("checkAllChains localChain::{}, tauChains::{}",
                        localChains.size(), tauChains.size());

                // 0、添加默认San Francisco
                String testChainID2 = BuildConfig.TEST_CHAIN_ID;
                Community community2 = communityRepo.getCommunityByChainID(testChainID2);
                if (null == community2) {
                    String peer = BuildConfig.TEST_CHAIN_PEER;
                    String tauTesting = LinkUtil.encodeChain(peer, testChainID2, peer);
                    tauDaemonAlertHandler.addCommunity(tauTesting);
                }
                // 1、处理本地跟随的chains, libTAU未跟随的情况
                for (String chainID : localChains) {
                    if (!tauChains.contains(chainID)) {
                        // libTAU followChain
                        List<String> list = memberRepo.queryCommunityMembersLimit(chainID, Constants.CHAIN_LINK_BS_LIMIT);
                        Set<String> peers = new HashSet<>(list);
                        boolean success = followChain(chainID, peers);
                        logger.info("checkAllChains followChain chainID::{}, success::{}", chainID, success);
                    } else {
                        // 从列表中移除，为1.2准备数据
                        tauChains.remove(chainID);
                    }
                }
                // 2、处理本地未跟随的chains, libTAU跟随的情况
                for (String chainID : tauChains) {
                    // libTAU unfollowChain
                    boolean success = unfollowChain(chainID);
                    logger.debug("checkAllChains unfollowChain chainID::{}, success::{}", chainID, success);
                }
            }
            emitter.onComplete();
        }).subscribeOn(Schedulers.io())
                .subscribe();
        disposables.add(disposable);
    }

    /**
     * 请求用户社区账户状态
     */
    private List<String> getTauAllChains() {
        List<String> list = null;
        if (isRunning) {
            list = sessionManager.getAllChains();
        }
        if (null == list) {
            list = new ArrayList<>();
        }
        return list;
    }

    /**
     * 请求更新朋友信息
     */
    public void requestFriendInfo(String friendPk) {
        subFriendInfo(friendPk);
        subFriendHeadPic(friendPk);
    }

    public void pubUserInfo(String publicKey, byte[] value) {
        if (isRunning) {
            byte[] keySrc = ByteUtil.toByte(publicKey);
            byte[] key = DataKey.getKey(keySrc, DataKey.Suffix.INFO);
            boolean success = sessionManager.publishData(key, value);
            logger.info("pubUserInfo userPk::{}, key.length::{}, success::{}", publicKey, key.length, success);
        }
    }

    public void subFriendInfo(String publicKey) {
        if (isRunning) {
            byte[] keySrc = ByteUtil.toByte(publicKey);
            byte[] key = DataKey.getKey(keySrc, DataKey.Suffix.INFO);
            sessionManager.subscribeFromPeer(keySrc, key);
            logger.info("subFriendInfo peer::{}, key.length::{}", publicKey, key.length);
        }
    }

    public void pubUserHeadPic(String publicKey, byte[] value) {
        if (isRunning) {
            byte[] keySrc = ByteUtil.toByte(publicKey);
            byte[] key = DataKey.getKey(keySrc, DataKey.Suffix.PIC);
            sessionManager.publishData(key, value);
            logger.info("pubUserHeadPic userPk::{}, key.length::{}", publicKey, key.length);
        }
    }

    public void subFriendHeadPic(String publicKey) {
        if (isRunning) {
            byte[] keySrc = ByteUtil.toByte(publicKey);
            byte[] key = DataKey.getKey(keySrc, DataKey.Suffix.PIC);
            sessionManager.subscribeFromPeer(keySrc, key);
            logger.info("subFriendHeadPic peer::{}, key.length::{}", publicKey, key.length);
        }
    }

    /**
     * 关注节点
     * @param publicKey 公钥
     */
    public void focusFriend(String publicKey) {
        if (isRunning) {
            byte[] peer = ByteUtil.toByte(publicKey);
            sessionManager.payAttenToPeer(peer);
            logger.info("focusFriend peer::{}", publicKey);
        }
    }

    /**
     * 重启失败停止的链
     * @param chainID 链ID
     */
    public void restartFailedChain(String chainID) {
        if (isRunning) {
            boolean isSuccess = sessionManager.startChain(ChainIDUtil.encode(chainID));
            if (isSuccess) {
                tauDaemonAlertHandler.restartFailedChain(chainID);
            }
            logger.info("restartFailedChain chainID::{}, isSuccess::{}", chainID, isSuccess);
        }
    }

    public TauDaemonAlertHandler getTauDaemonHandler() {
        return tauDaemonAlertHandler;
    }

    public MyAccountManager getMyAccountManager() {
        return myAccountManager;
    }

    /**
     * 请求peer发布区块数据
     * @param publicKey 公钥
     * @param chainID 链ID
     */
    public void requestChainData(String publicKey, String chainID) {
        if (isRunning) {
            sessionManager.requestChainData(ChainIDUtil.encode(chainID), publicKey);
            logger.info("requestChainData peer::{}, chainID::{}", publicKey, chainID);
        }
    }

    /**
     * publish链的数据
     * @param chainID 链ID
     */
    public void pubChainData(String chainID) {
        if (isRunning) {
            sessionManager.putAllChainData(ChainIDUtil.encode(chainID));
            logger.info("pubChainData chainID::{}", chainID);
        }
    }

    /**
     *  设置libTAU Non Referable
     */
    public void setNonReferable(boolean nonReferable) {
        if (isRunning) {
            if (this.sessionManager != null) {
                sessionManager.setNonReferrable(nonReferable);
                logger.info("setNonReferable::{}", nonReferable);
            }
        }
    }

    /**
     * 启动充电5分钟计时
     */
    private void startChargingTiming() {
        if (chargingTimer != null && !chargingTimer.isDisposed()) {
            chargingTimer.dispose();
        }
        boolean chargingState = settingsRepo.chargingState();
        if (chargingState) {
            int timeMin = 5;
            logger.info("startChargingTiming::{}min...", timeMin);
            chargingTimer = ObservableUtil.intervalSeconds(timeMin * 60)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(l -> {
                    chargingTimingCompleted = true;
                    resetAutoRelayDelay();
                    if (chargingTimer != null && !chargingTimer.isDisposed()) {
                        chargingTimer.dispose();
                    }
                });
        } else {
            chargingTimingCompleted = false;
            resetAutoRelayDelay();
        }
    }

    /**
     * 启动点对点消息重发
     */
    private long resendTime = 0;
    private Disposable msgResendDisposable;
    public void checkDataResend() {
        int intervalSeconds = 30 * 60;
        long currentTime = DateUtil.getMillisTime();
        if (null == msgResendDisposable || resendTime == 0 || (currentTime - resendTime) / 1000 <= intervalSeconds) {
            return;
        }
        startDataResend();
    }
    public void startDataResend() {
        int intervalSeconds = 30 * 60;
        if (msgResendDisposable != null && !msgResendDisposable.isDisposed()) {
            msgResendDisposable.dispose();
        }
        msgResendDisposable = ObservableUtil.intervalSeconds(intervalSeconds, true)
                .subscribeOn(Schedulers.io())
                .subscribe(l -> {
                    resendTime = DateUtil.getMillisTime();
                    logger.debug("resendRegularMessages...");
                    ChatViewModel.resendRegularMessages(appContext);
                    TxViewModel.resendRegularTxs(appContext);
                });
    }

    /**
     * 延迟重置libTAU是否为自动中继
     */
    public void resetAutoRelayDelay() {
        boolean autoRelay = NetworkSetting.isUnlimitedNetwork() && settingsRepo.chargingState()
                && chargingTimingCompleted;
        if (autoRelay) {
            setAutoRelay(true);
        } else {
            setAutoRelay(false);
            setNonReferable(true);
        }
    }

    /**
     *  设置libTAU auto relay
     */
    public void setAutoRelay(boolean autoRelay) {
        if (isRunning) {
            if (this.sessionManager != null) {
                sessionManager.setAutoRelay(autoRelay);
                logger.info("setAutoRelay::{}", autoRelay);
                // 设置为true, 底层会智能判断，修改此值
                setNonReferable(true);
                setNonReferable(true);
            }
        }
    }

    /**
     * 关注链时，尝试连接链
     * @param chainID 链ID
     */
    public void connectChain(String chainID) {
        if (isRunning) {
            sessionManager.connectChain(ChainIDUtil.encode(chainID));
            logger.info("connectChain chainID::{}", chainID);
        }
    }

    /**
     * 每次更新网络接口监听成功，定时一分钟 更新libTAU Bootstrap Interval为600s
     */
    void updateBootstrapInterval() {
        if (updateBootstrapIntervalTimer != null && !updateBootstrapIntervalTimer.isDisposed()) {
            updateBootstrapIntervalTimer.dispose();
        }
        updateBootstrapIntervalTimer = ObservableUtil.intervalSeconds(120)
                .subscribeOn(Schedulers.io())
                .subscribe(l -> updateBootstrapInterval(600));
    }

    /**
     *  更新libTAU Bootstrap Interval
     * @param interval 时间间隔
     */
    public boolean updateBootstrapInterval(int interval) {
        if (isRunning) {
            sessionManager.updateBootstrapIntervel(interval);
            logger.info("updateBootstrapInterval::{}s", interval);
            return true;
        }
        return false;
    }

    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 获取挖矿时间
     * 返回-1：代表出不了块
     */
    public long getMiningTime(byte[] chainID) {
        logger.info("getMiningTime isRunning::{}", isRunning);
        if (isRunning) {
            return sessionManager.getMiningTime(chainID);
        }
        return -1;
    }

    /**
     * 添加新Bootstrap节点
     */
    public boolean addNewBootstrapPeers(String chainID, Set<String> peers) {
        if (isRunning) {
            boolean isSuccess = sessionManager.addNewBootstrapPeers(ChainIDUtil.encode(chainID), peers);
            logger.info("addNewBootstrapPeers chainID::{}, peers size::{}", chainID, peers.size());
            return isSuccess;
        }
        return false;
    }

    /**
     * 主动断网
     */
    public void disconnectNetwork() {
        if (isRunning) {
            sessionManager.disconnectNetwork();
        }
        logger.info("disconnectNetwork isRunning::{}", isRunning);
    }

    /**
     * 重连网络
     */
    public void reconnectNetwork() {
        if (isRunning) {
            sessionManager.reconnectNetwork();
        }
        logger.info("reconnectNetwork isRunning::{}", isRunning);
    }

    /**
     * 设置日志等级
     */
    public void setLogLevel(int level) {
        if (isRunning) {
            sessionManager.setLogLevel(level);
        }
        logger.info("setLogLevel level::{}, isRunning::{}", level, isRunning);
    }

    /**
     * 重置频率模式
     */
    private void resetFrequencyMode() {
        String foregroundRunningKey = appContext.getString(R.string.pref_key_foreground_running);
        boolean isForeground = settingsRepo.getBooleanValue(foregroundRunningKey, true);
        if (isForeground || settingsRepo.chargingState()) {
            setHighFrequencyMode();
        } else {
            setLowFrequencyMode();
        }
    }

    /**
     * 设置高频模式
     */
    public void setHighFrequencyMode() {
        if (isRunning) {
            sessionManager.setHighFrequencyMode();
        }
        logger.warn("setHighFrequencyMode isRunning::{}", isRunning);
    }

    /**
     * 设置低频模式
     */
    public void setLowFrequencyMode() {
        if (isRunning) {
            sessionManager.setLowFrequencyMode();
        }
        logger.warn("setLowFrequencyMode isRunning::{}", isRunning);
    }

    /**
     * touch Chain
     */
    public void touchChain(String chainID) {
        if (!isRunning) {
            return;
        }
        sessionManager.touchChain(ChainIDUtil.encode(chainID));
        logger.info("touchChain::{} isRunning::{}", chainID, isRunning);
    }

    /**
     * Active list
     */
    public List<String> getActiveList(String chainID) {
        List<String> list = null;
        if (isRunning) {
            list = sessionManager.getActiveList(ChainIDUtil.encode(chainID));
        }
        if (null == list) {
            list = new ArrayList<>();
        }
        logger.info("chainID::{} activeList::{} isRunning::{}", chainID, list.size(), isRunning);
        return list;
    }

    /**
     * crash测试
     */
    public void crashTest() {
        if (isRunning) {
            sessionManager.crashTest();
        }
        logger.warn("crashTest isRunning::{}", isRunning);
    }

    /**
     * sql测试
     */
    public void sqlTest() {
        if (isRunning) {
            sessionManager.sqlTest();
        }
        logger.warn("sqlTest isRunning::{}", isRunning);
    }

    /**
     * reload chain
     * 处理区块数据
     */
    public void handleBlockData(Block block, TauListenHandler.BlockStatus status) {
        tauDaemonAlertHandler.handleBlockData(block, status);
    }

    /**
     * 开始定位
     */
    public void startLocation() {
        if (locationManager.isNeedPermission()) {
            logger.debug("No location permission");
            return;
        }
        if (null == updateLocationTimer || updateLocationTimer.isDisposed()) {
            locationManager.startLocation();
            updateLocationTimer = ObservableUtil.intervalSeconds(3600)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe( l -> locationManager.startLocation());
        }
    }

    /**
     * 发送更新转账交易队列
     */
    public void updateTxQueue(String chainID) {
        txQueueManager.updateTxQueue(chainID);
    }

    public void updateTxQueue(String chainID, boolean isResendTx) {
        txQueueManager.updateTxQueue(chainID, isResendTx);
    }

    public void updateChainTxStoppedTime(String chainID) {
        txQueueManager.updateChainTxStoppedTime(chainID);
    }

    public void sendTxQueue(TxQueue txQueue, long pinnedTime, int mode) {
        txQueueManager.sendTxQueue(txQueue, pinnedTime, mode);
    }

    /**
     * 获取Alert消费者的发射器
     */
    public abstract ObservableEmitter getAlertConsumerEmitter();

    /**
     * 更新用户Seed
     * @param seed Seed
     */
    public abstract void updateSeed(String seed);

    /**
     * 观察TauDaemonAlert变化
     * 直接进队列，然后单独线程处理
     */
    abstract void observeTauDaemonAlertListener();

    /**
     *  更新libTAU朋友信息
     * @param user 朋友对象
     */
    public abstract boolean updateUserInfo(User user);

    public abstract boolean addNewFriend(String friendPk);

    /**
     * 删除朋友
     * @param friendPk 朋友公钥
     */
    public abstract boolean deleteFriend(String friendPk);

    /**
     * 添加新消息
     */
    public abstract boolean addNewMessage(Message msg);

    /**
     * 创建新的社区
     */
    public abstract boolean createNewCommunity(byte[] chainID, Set<Account> accounts);

    /**
     * 创建新的社区链ID
     * @param communityName 社区名称
     * @return chainID
     */
    public abstract String createNewChainID(String type, String communityName);

    /**
     * 获取账户信息
     * @param chainID 社区ID
     * @param publicKey 用户公钥
     * @return Account 账户信息
     */
    public abstract Account getAccountInfo(byte[] chainID, String publicKey);

    /**
     * 提交交易到交易池
     * @param tx 交易对象
     */
    public abstract boolean submitTransaction(Transaction tx);

    /**
     * 跟随链
     * @param chainID 链ID
     * @param peers 链上peers
     */
    public abstract boolean followChain(String chainID, Set<String> peers);

    /**
     * 取消跟随链
     * @param chainID 链ID
     */
    public abstract boolean unfollowChain(String chainID);

    /**
     * 获取Session Time
     */
    public abstract long getSessionTime();

    /**
     * 通过区块号查询区块
     * @param chainID 链ID
     * @param blockNumber 区块号
     */
    public abstract Block getBlockByNumber(String chainID, long blockNumber);

    /**
     * 通过区块Hash查询区块
     * @param chainID 链ID
     * @param blockHash 区块Hash
     */
    public abstract Block getBlockByHash(String chainID, String blockHash);
}