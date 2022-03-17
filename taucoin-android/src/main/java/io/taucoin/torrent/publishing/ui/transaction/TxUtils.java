package io.taucoin.torrent.publishing.ui.transaction;

import android.content.Context;
import android.text.SpannableStringBuilder;

import org.libTAU4j.Block;
import org.libTAU4j.Transaction;

import io.taucoin.torrent.publishing.MainApplication;
import io.taucoin.torrent.publishing.R;
import io.taucoin.torrent.publishing.core.model.data.BlockAndTx;
import io.taucoin.torrent.publishing.core.model.data.TxQueueAndStatus;
import io.taucoin.torrent.publishing.core.model.data.UserAndTx;
import io.taucoin.torrent.publishing.core.model.data.message.TxType;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.BlockInfo;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.Tx;
import io.taucoin.torrent.publishing.core.utils.ChainIDUtil;
import io.taucoin.torrent.publishing.core.utils.DateUtil;
import io.taucoin.torrent.publishing.core.utils.FmtMicrometer;
import io.taucoin.torrent.publishing.core.utils.Formatter;
import io.taucoin.torrent.publishing.core.utils.SpanUtils;
import io.taucoin.torrent.publishing.core.utils.StringUtil;
import io.taucoin.torrent.publishing.core.utils.UsersUtil;
import io.taucoin.torrent.publishing.core.utils.rlp.ByteUtil;

public class TxUtils {

    public static SpannableStringBuilder createTxSpan(UserAndTx tx) {
        return createTxSpan(tx, CommunityTabFragment.TAB_NOTES);
    }

    public static SpannableStringBuilder createTxSpan(UserAndTx tx, int tab) {
        switch (TxType.valueOf(tx.txType)) {
            case NOTE_TX:
                return createSpanNoteTx(tx, tab);
            case WIRING_TX:
                return createSpanWiringTx(tx);
            case SELL_TX:
                return createSpanSellTx(tx, tab);
            case AIRDROP_TX:
                return createSpanAirdropTx(tx, tab);
            case TRUST_TX:
                return createSpanTrustTx(tx, tab);
            case LEADER_INVITATION:
                return createSpanLeaderTx(tx, tab);
        }
        return new SpanUtils().create();
    }

    private static SpannableStringBuilder createSpanNoteTx(UserAndTx tx, int tab) {
        SpanUtils msg = new SpanUtils();
        if (tab == CommunityTabFragment.TAB_CHAIN) {
            Context context = MainApplication.getInstance();
            int titleColor = context.getResources().getColor(R.color.gray_dark);
            String coinName = ChainIDUtil.getCoinName(tx.chainID);
            msg.append("Message: ").setForegroundColor(titleColor)
                    .append(tx.memo)
                    .append("\n").append("Fee: ").setForegroundColor(titleColor)
                    .append(FmtMicrometer.fmtFeeValue(tx.fee))
                    .append(" ").append(coinName)
                    .append("\n").append("From: ").setForegroundColor(titleColor)
                    .append(tx.senderPk);
            if (tx.txStatus == 1) {
                msg.append("\n").append("Blocknumber: ").setForegroundColor(titleColor)
                        .append(FmtMicrometer.fmtLong(tx.blockNumber));
            }
        } else {
            msg.append(tx.memo);
        }
        return msg.create();
    }

    private static SpannableStringBuilder createSpanWiringTx(Tx tx) {
        Context context = MainApplication.getInstance();
        int titleColor = context.getResources().getColor(R.color.gray_dark);
        String coinName = ChainIDUtil.getCoinName(tx.chainID);
        SpanUtils msg = new SpanUtils()
                .append("Amount: ").setForegroundColor(titleColor)
                .append(FmtMicrometer.fmtBalance(tx.amount))
                .append(" ").append(coinName)
                .append("\n").append("Fee: ").setForegroundColor(titleColor)
                .append(FmtMicrometer.fmtFeeValue(tx.fee))
                .append(" ").append(coinName)
                .append("\n").append("From: ").setForegroundColor(titleColor)
                .append(tx.senderPk)
                .append("\n").append("To: ").setForegroundColor(titleColor)
                .append(tx.receiverPk)
                .append("\n").append("Hash: ").setForegroundColor(titleColor)
                .append(tx.txID);
        if (tx.txStatus == 1) {
            msg.append("\n").append("Blocknumber: ").setForegroundColor(titleColor)
                    .append(FmtMicrometer.fmtLong(tx.blockNumber));
        }
        msg.append("\n").append("Nonce: ").setForegroundColor(titleColor)
                .append(FmtMicrometer.fmtLong(tx.nonce))
                .append("\n").append("Memo: ").setForegroundColor(titleColor)
                .append(tx.memo);
        return msg.create();
    }

    private static SpannableStringBuilder createSpanSellTx(Tx tx, int tab) {
        Context context = MainApplication.getInstance();
        int titleColor = context.getResources().getColor(R.color.gray_dark);
        SpanUtils msg = new SpanUtils()
                .append("Sell: ").setForegroundColor(titleColor)
                .append("\n").append(tx.coinName).setFontSize(16, true)
                .append("\n").append("Quantity: ").setForegroundColor(titleColor)
                .append(FmtMicrometer.fmtBalance(tx.quantity));
        if (StringUtil.isNotEmpty(tx.link) && tab != CommunityTabFragment.TAB_MARKET) {
            msg.append("\n").append("Link: ").setForegroundColor(titleColor)
                    .append(tx.link);
        }
        if (StringUtil.isNotEmpty(tx.location)) {
            msg.append("\n").append("Location: ").setForegroundColor(titleColor)
                    .append(tx.location);
        }
        if (StringUtil.isNotEmpty(tx.memo) && tab != CommunityTabFragment.TAB_MARKET) {
            msg.append("\n").append("Description: ").setForegroundColor(titleColor)
                    .append(tx.memo);
        }

        if (tab == CommunityTabFragment.TAB_CHAIN) {
            String coinName = ChainIDUtil.getCoinName(tx.chainID);
            msg.append("\n").append("Fee: ").setForegroundColor(titleColor)
                    .append(FmtMicrometer.fmtFeeValue(tx.fee))
                    .append(" ").append(coinName)
                    .append("\n").append("From: ").setForegroundColor(titleColor)
                    .append(tx.senderPk);
            if (tx.txStatus == 1) {
                msg.append("\n").append("Blocknumber: ").setForegroundColor(titleColor)
                        .append(FmtMicrometer.fmtLong(tx.blockNumber));
            }
        }
        return msg.create();
    }

    private static SpannableStringBuilder createSpanAirdropTx(Tx tx, int tab) {
        Context context = MainApplication.getInstance();
        int titleColor = context.getResources().getColor(R.color.gray_dark);
        SpanUtils msg = new SpanUtils()
                .append("Airdrop: ").setForegroundColor(titleColor)
                .append(tx.coinName)
                .append("\n").append("Link: ").setForegroundColor(titleColor)
                    .append(tx.link);
        if (StringUtil.isNotEmpty(tx.memo)) {
            msg.append("\n").append("Description: ").setForegroundColor(titleColor)
                    .append(tx.memo);
        }

        if (tab == CommunityTabFragment.TAB_CHAIN) {
            String coinName = ChainIDUtil.getCoinName(tx.chainID);
            msg.append("\n").append("Fee: ").setForegroundColor(titleColor)
                    .append(FmtMicrometer.fmtFeeValue(tx.fee))
                    .append(" ").append(coinName)
                    .append("\n").append("From: ").setForegroundColor(titleColor)
                    .append(tx.senderPk);
            if (tx.txStatus == 1) {
                msg.append("\n").append("Blocknumber: ").setForegroundColor(titleColor)
                        .append(FmtMicrometer.fmtLong(tx.blockNumber));
            }
        }
        return msg.create();
    }

    private static SpannableStringBuilder createSpanLeaderTx(Tx tx, int tab) {
        Context context = MainApplication.getInstance();
        int titleColor = context.getResources().getColor(R.color.gray_dark);
        SpanUtils msg = new SpanUtils()
                .append(tx.coinName).setForegroundColor(titleColor);
        if (StringUtil.isNotEmpty(tx.memo)) {
            msg.append("\n").append("Description: ").setForegroundColor(titleColor)
                    .append(tx.memo);
        }

        if (tab == CommunityTabFragment.TAB_CHAIN) {
            String coinName = ChainIDUtil.getCoinName(tx.chainID);
            msg.append("\n").append("Fee: ").setForegroundColor(titleColor)
                    .append(FmtMicrometer.fmtFeeValue(tx.fee))
                    .append(" ").append(coinName)
                    .append("\n").append("From: ").setForegroundColor(titleColor)
                    .append(tx.senderPk);
            if (tx.txStatus == 1) {
                msg.append("\n").append("Blocknumber: ").setForegroundColor(titleColor)
                        .append(FmtMicrometer.fmtLong(tx.blockNumber));
            }
        }
        return msg.create();
    }

    public static SpannableStringBuilder createSpanTxQueue(TxQueueAndStatus tx) {
        String coinName = ChainIDUtil.getCoinName(tx.chainID);
        SpanUtils msg = new SpanUtils()
                .append("Amount: ")
                .append(FmtMicrometer.fmtBalance(tx.amount))
                .append("\n").append("Fee: ")
                .append(FmtMicrometer.fmtFeeValue(tx.fee))
                .append(" ")
                .append(coinName)
                .append("\n").append("To: ")
                .append(tx.receiverPk);
        if (tx.nonce > 0) {
            msg.append("\n").append("Nonce: ").append(FmtMicrometer.fmtLong(tx.nonce));
        }
        msg.append("\n").append("Memo: ").append(tx.memo);
        return msg.create();
    }

    private static SpannableStringBuilder createSpanTrustTx(UserAndTx tx, int tab) {
        Context context = MainApplication.getInstance();
        int titleColor = context.getResources().getColor(R.color.gray_dark);
        String receiverName = UsersUtil.getShowName(tx.receiver);
        SpanUtils msg = new SpanUtils()
                .append("Trust: ")
                .setForegroundColor(titleColor)
//                .append(receiverName)
                .append(tx.receiverPk);
//                .append(")");
        if (tab == CommunityTabFragment.TAB_CHAIN) {
            String coinName = ChainIDUtil.getCoinName(tx.chainID);
            msg.append("\n").append("Fee: ").setForegroundColor(titleColor)
                    .append(FmtMicrometer.fmtFeeValue(tx.fee))
                    .append(" ").append(coinName)
                    .append("\n").append("From: ").setForegroundColor(titleColor)
                    .append(tx.senderPk);
            if (tx.txStatus == 1) {
                msg.append("\n").append("Blocknumber: ").setForegroundColor(titleColor)
                        .append(FmtMicrometer.fmtLong(tx.blockNumber));
            }
        }
        return msg.create();
    }

    public static SpannableStringBuilder createBlockSpan(BlockAndTx block) {
        Context context = MainApplication.getInstance();
        int titleColor = context.getResources().getColor(R.color.gray_dark);
        SpanUtils msg = new SpanUtils()
                .append(context.getString(R.string.community_tip_block_txs))
                .setForegroundColor(titleColor)
                .append(String.valueOf(null == block.txs ? 0 : block.txs.size()))
                .append("\n").append(context.getString(R.string.community_tip_block_timestamp))
                .setForegroundColor(titleColor)
                .append(DateUtil.formatTime(block.timestamp, DateUtil.pattern6))
                .append("\n").append(context.getString(R.string.community_tip_block_miner))
                .setForegroundColor(titleColor)
                .append("\n").append(block.miner)
                .append("\n").append(context.getString(R.string.community_tip_block_reward))
                .setForegroundColor(titleColor)
                .append(FmtMicrometer.fmtBalance(block.rewards))
                .append(" ")
                .append(ChainIDUtil.getCoinName(block.chainID));
         if (StringUtil.isNotEmpty(block.previousBlockHash)) {
             msg.append("\n").append(context.getString(R.string.community_tip_previous_hash))
                     .setForegroundColor(titleColor)
                     .append("\n").append(block.previousBlockHash);
         }
        msg.append("\n").append(context.getString(R.string.community_tip_block_hash))
                .setForegroundColor(titleColor)
                .append("\n").append(block.blockHash)
                .append("\n").append(context.getString(R.string.community_tip_block_difficulty))
                .setForegroundColor(titleColor)
                .append(FmtMicrometer.fmtDecimal(block.difficulty));
        return msg.create();
    }

    public static SpannableStringBuilder createBlockSpan(Block block) {
        Transaction tx = block.getTx();
        byte[] payload = tx.getPayload();

        boolean isHaveTx = payload != null && payload.length > 0;
        int txsSize = isHaveTx ? 1 : 0;
        String blockReward = FmtMicrometer.fmtBalance(tx.getFee());
        if (block.getBlockNumber() <= 0) {
            blockReward = FmtMicrometer.fmtBalance(block.getMinerBalance());
        }
        String chainID = ChainIDUtil.decode(block.getChainID());
        blockReward += " " + ChainIDUtil.getCoinName(chainID);

        Context context = MainApplication.getInstance();
        int titleColor = context.getResources().getColor(R.color.gray_dark);
        SpanUtils msg = new SpanUtils()
                .append(context.getString(R.string.community_tip_block_txs))
                .setForegroundColor(titleColor)
                .append(String.valueOf(txsSize))
                .append("\n").append(context.getString(R.string.community_tip_block_timestamp))
                .setForegroundColor(titleColor)
                .append(DateUtil.formatTime(block.getTimestamp(), DateUtil.pattern6))
                .append("\n").append(context.getString(R.string.community_tip_block_miner))
                .setForegroundColor(titleColor)
                .append("\n").append(ByteUtil.toHexString(block.getMiner()))
                .append("\n").append(context.getString(R.string.community_tip_block_reward))
                .setForegroundColor(titleColor)
                .append(blockReward);
        if (block.getPreviousBlockHash() != null) {
            String previousHash = ByteUtil.toHexString(block.getPreviousBlockHash());
            msg.append("\n").append(context.getString(R.string.community_tip_previous_hash))
                    .setForegroundColor(titleColor)
                    .append("\n").append(previousHash);
        }
        msg.append("\n").append(context.getString(R.string.community_tip_block_hash))
                .setForegroundColor(titleColor)
                .append("\n").append(block.Hash())
                .append("\n").append(context.getString(R.string.community_tip_block_difficulty))
                .setForegroundColor(titleColor)
                .append(FmtMicrometer.fmtDecimal(block.getCumulativeDifficulty().longValue()));
        return msg.create();
    }
}
