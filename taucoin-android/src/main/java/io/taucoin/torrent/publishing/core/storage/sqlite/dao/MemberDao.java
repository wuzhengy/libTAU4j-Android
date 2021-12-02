package io.taucoin.torrent.publishing.core.storage.sqlite.dao;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.paging.DataSource;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.taucoin.torrent.publishing.core.Constants;
import io.taucoin.torrent.publishing.core.model.data.MemberAndFriend;
import io.taucoin.torrent.publishing.core.model.data.MemberAndUser;
import io.taucoin.torrent.publishing.core.model.data.Statistics;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.Member;

/**
 * Room:Member操作接口
 */
@Dao
public interface MemberDao {
    String WHERE_NOT_PERISHABLE = " (headBlock - blockNumber < "+ Constants.BLOCKS_NOT_PERISHABLE +")";
    String WHERE_ON_CHAIN = " ((balance > 0 OR power > 0) AND" + WHERE_NOT_PERISHABLE + ")";

    String QUERY_GET_MEMBER_BY_CHAIN_ID_PK = "SELECT * FROM Members WHERE chainID = :chainID AND publicKey = :publicKey";
    String QUERY_GET_MEMBERS_BY_CHAIN_ID = "SELECT * FROM Members WHERE chainID = :chainID";

    String QUERY_GET_MEMBERS_ON_CHAIN = "SELECT m.*, f.lastSeenTime, c.headBlock" +
            " FROM Members m LEFT JOIN Friends f" +
            " ON m.publicKey = f.friendPK AND f.userPK = (" + UserDao.QUERY_GET_CURRENT_USER_PK + ")" +
            " LEFT JOIN Communities c ON m.chainID = c.chainID" +
            " WHERE m.chainID = :chainID AND " + WHERE_ON_CHAIN +
            " ORDER BY f.lastSeenTime DESC";

    String QUERY_COMMUNITY_NUM_IN_COMMON = "SELECT chainID FROM " +
            " (Select count(*) AS num, m.chainID FROM Members m" +
            " LEFT JOIN Communities c ON m.chainID = c.chainID" +
            " where (m.publicKey =:currentUserPk OR m.publicKey =:memberPk) AND " + WHERE_ON_CHAIN +
            " GROUP BY m.chainID)" +
            " WHERE num >= 2";

    String QUERY_COMMUNITY_MEMBERS_LIMIT = "SELECT m.publicKey FROM Members m" +
            " LEFT JOIN Communities c ON m.chainID = c.chainID" +
            " WHERE m.chainID = :chainID AND " + WHERE_ON_CHAIN +
            " ORDER BY m.power limit :limit";

    String QUERY_MEMBERS_STATISTICS = "SELECT COUNT(*) AS members FROM Members m" +
            " LEFT JOIN Communities c ON m.chainID = c.chainID" +
            " WHERE m.chainID =:chainID and " + WHERE_ON_CHAIN;

    String QUERY_DELETE_COMMUNITY_MEMBERS = "DELETE FROM Members where chainID =:chainID";
    /**
     * 添加新社区成员
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long addMember(Member member);

    /**
     * 更新社区成员
     */
    @Update
    int updateMember(Member member);

    /**
     * 获取Member根据公钥和链ID
     * @param chainID 社区链ID
     * @param publicKey 公钥
     * @return Member
     */
    @Query(QUERY_GET_MEMBER_BY_CHAIN_ID_PK)
    Member getMemberByChainIDAndPk(@NonNull String chainID, @NonNull String publicKey);

    @Query(QUERY_GET_MEMBERS_BY_CHAIN_ID)
    @Transaction
    Flowable<List<MemberAndUser>> observeCommunityMembers(String chainID);

    /**
     * 查询社区上链的成员
     * @param chainID 社区链ID
     * @return DataSource.Factory
     */
    @Query(QUERY_GET_MEMBERS_ON_CHAIN)
    @Transaction
    DataSource.Factory<Integer, MemberAndFriend> queryCommunityMembersOnChain(String chainID);

    /**
     * 获取和社区成员共在的社区数
     * @param currentUserPk
     * @param memberPk
     */
    @Query(QUERY_COMMUNITY_NUM_IN_COMMON)
    Single<List<String>> getCommunityNumInCommon(String currentUserPk, String memberPk);

    /**
     * 获取社区limit个成员
     * @param chainID
     * @param limit
     */
    @Query(QUERY_COMMUNITY_MEMBERS_LIMIT)
    Single<List<String>> getCommunityMembersLimit(String chainID, int limit);

    @Query(QUERY_COMMUNITY_MEMBERS_LIMIT)
    List<String> queryCommunityMembersLimit(String chainID, int limit);

    @Query(QUERY_MEMBERS_STATISTICS)
    Flowable<Statistics> getMembersStatistics(String chainID);

    @Query(QUERY_DELETE_COMMUNITY_MEMBERS)
    void deleteCommunityMembers(String chainID);
}
