package com.example.lalem

import cn.bmob.v3.BmobACL
import cn.bmob.v3.BmobObject
import cn.bmob.v3.BmobQuery
import cn.bmob.v3.BmobUser
import cn.bmob.v3.exception.BmobException
import cn.bmob.v3.listener.FetchUserInfoListener
import cn.bmob.v3.listener.SaveListener
import cn.bmob.v3.listener.UpdateListener
import org.json.JSONArray
import io.reactivex.rxjava3.core.Observable
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class LalemUser : BmobUser() {
    var nickname: String? = null
    var loginPhone: String? = null
    var friendCode: String? = null
    var directoryId: String? = null
    var sharedLinkIds: List<String>? = null
}

class PoopRecord : BmobObject() {
    var userId: String? = null
    var clientId: String? = null
    var poopTime: Long? = null
    var poopType: String? = null
    var weight: Double? = null
}

class FriendDirectory : BmobObject() {
    var userId: String? = null
    var lookup: String? = null
    var nickname: String? = null
}

class FriendLink : BmobObject() {
    var senderId: String? = null
    var recipientId: String? = null
    var senderName: String? = null
    var recipientName: String? = null
    var status: String? = null
    var senderRecords: String? = null
    var recipientRecords: String? = null

    fun otherId(myId: String) = if (senderId == myId) recipientId.orEmpty() else senderId.orEmpty()
    fun otherName(myId: String) = if (senderId == myId) recipientName.orEmpty() else senderName.orEmpty()
    fun otherRecords(myId: String) = decodeRecords(if (senderId == myId) recipientRecords else senderRecords)
}

enum class RecordType(val label: String) {
    NORMAL("正常"), DRY("干燥"), DIARRHEA("拉稀");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: NORMAL
    }
}

data class Entry(val id: String, val time: Long, val type: RecordType, val weight: Double?) {
    val date: LocalDate get() = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
}

internal data class CloudRecord(val entry: Entry, val clientId: String?)

private fun decodeRecords(raw: String?): List<Entry> = runCatching {
    val array = JSONArray(raw ?: "[]")
    List(array.length()) { index ->
        val record = array.getJSONArray(index)
        Entry("", record.getLong(0), RecordType.from(record.getString(1)),
            if (record.isNull(2)) null else record.getDouble(2))
    }
}.getOrDefault(emptyList())

internal class LalemRepository {
    fun currentUser(): LalemUser? = BmobUser.getCurrentUser(LalemUser::class.java)

    suspend fun authenticate(phone: String, password: String, nickname: String?): LalemUser {
        require(password.length in 6..64) { "密码需为 6 至 64 个字符" }
        val account = LalemUser().apply {
            username = loginName(phone)
            setPassword(password)
            if (nickname != null) {
                require(nickname.length in 1..20) { "昵称需为 1 至 20 个字符" }
                this.nickname = nickname
                friendCode = newCode()
            }
        }
        if (nickname != null) {
            account.signUpObservable(LalemUser::class.java).awaitOne()
            if (currentUser() == null) account.loginObservable(LalemUser::class.java).awaitOne()
        } else {
            account.loginObservable(LalemUser::class.java).awaitOne()
        }
        return currentUser() ?: error("登录状态未保存，请重试")
    }

    suspend fun rememberLoginPhone(user: LalemUser, phone: String) {
        if (user.loginPhone.isNullOrBlank() && user.username == loginName(phone)) {
            updateUser(user) {
                loginPhone = phone
                setACL(acl(user.objectId))
            }
            user.loginPhone = phone
        }
    }

    suspend fun fetchProfile(): LalemUser = suspendCancellableCoroutine { continuation ->
        BmobUser.fetchUserInfo(object : FetchUserInfoListener<LalemUser>() {
            override fun done(user: LalemUser?, error: BmobException?) {
                if (!continuation.isActive) return
                if (error != null) continuation.resumeWithException(error)
                else if (user != null) continuation.resume(user)
                else continuation.resumeWithException(IllegalStateException("无法读取账号资料"))
            }
        })
    }

    suspend fun records(user: LalemUser): List<CloudRecord> {
        val all = mutableListOf<CloudRecord>()
        var skip = 0
        while (true) {
            val page = awaitList(BmobQuery<PoopRecord>().apply {
                addWhereEqualTo("userId", user.objectId)
                order("-poopTime,objectId")
                setLimit(100)
                setSkip(skip)
            })
            all += page.mapNotNull { row ->
                row.poopTime?.let {
                    CloudRecord(Entry(row.objectId, it, RecordType.from(row.poopType), row.weight?.takeIf { w -> w > 0 }), row.clientId)
                }
            }
            if (page.size < 100) return all
            skip += page.size
        }
    }

    suspend fun uploadRecord(user: LalemUser, change: PendingRecord): String? {
        val entry = change.entry
        val remoteId = change.remoteId
        if (change.deleted) {
            if (remoteId != null) {
                val row = PoopRecord().apply { objectId = remoteId }
                try {
                    awaitUpdate { row.delete(it) }
                } catch (error: BmobException) {
                    if (error.errorCode != 101) throw error
                }
            }
            return null
        }
        val row = PoopRecord().apply {
            poopTime = entry.time
            poopType = entry.type.name
            weight = entry.weight ?: 0.0
        }
        if (remoteId != null) {
            try {
                awaitUpdate { row.update(remoteId, it) }
                return remoteId
            } catch (error: BmobException) {
                if (error.errorCode != 101) throw error
            }
        }
        val newRow = PoopRecord().apply {
            userId = user.objectId
            clientId = entry.id
            poopTime = row.poopTime
            poopType = row.poopType
            weight = row.weight
            setACL(acl(user.objectId))
        }
        return awaitSave<String> { newRow.save(it) }
    }

    suspend fun links(user: LalemUser): List<FriendLink> {
        val asSender = BmobQuery<FriendLink>().addWhereEqualTo("senderId", user.objectId)
        val asRecipient = BmobQuery<FriendLink>().addWhereEqualTo("recipientId", user.objectId)
        val all = mutableListOf<FriendLink>()
        var skip = 0
        while (true) {
            val page = awaitList(BmobQuery<FriendLink>().apply {
                or(listOf(asSender, asRecipient))
                order("-createdAt,objectId")
                setLimit(100)
                setSkip(skip)
            })
            all += page
            if (page.size < 100) return all
            skip += page.size
        }
    }

    suspend fun requestFriend(user: LalemUser, phone: String, code: String, links: List<FriendLink>) {
        require(code.matches(Regex("[0-9a-fA-F]{8}"))) { "请输入 8 位好友码" }
        val friend = awaitList(BmobQuery<FriendDirectory>().apply {
            addWhereEqualTo("lookup", lookup(loginName(phone), code.uppercase()))
            setLimit(1)
        }).firstOrNull() ?: error("找不到对应的好友，请检查手机号和好友码")
        val friendId = friend.userId ?: error("好友资料不完整")
        require(friendId != user.objectId) { "不能添加自己" }
        require(links.none { it.otherId(user.objectId) == friendId }) { "已经有好友关系或待处理的申请" }
        val link = FriendLink().apply {
            senderId = user.objectId
            senderName = user.nickname
            recipientId = friendId
            recipientName = friend.nickname
            status = PENDING
            setACL(acl(user.objectId, friendId))
        }
        val id = awaitSave<String> { link.save(it) }
        allowSharing(user, id)
    }

    suspend fun acceptFriend(user: LalemUser, link: FriendLink) {
        require(link.recipientId == user.objectId && link.status == PENDING)
        allowSharing(user, link.objectId)
        val update = FriendLink().apply { status = ACCEPTED }
        awaitUpdate { update.update(link.objectId, it) }
    }

    suspend fun removeLink(user: LalemUser, link: FriendLink) {
        val row = FriendLink().apply { objectId = link.objectId }
        awaitUpdate { row.delete(it) }
        if (link.objectId in user.sharedLinkIds.orEmpty()) {
            val remaining = user.sharedLinkIds.orEmpty() - link.objectId
            updateUser(user) { sharedLinkIds = remaining }
            user.sharedLinkIds = remaining
        }
    }

    suspend fun syncSharedRecords(user: LalemUser, records: List<Entry>, links: List<FriendLink>) {
        val today = LocalDate.now()
        val cutoff = today.minusDays(35)
        val shared = records.filter { it.date >= cutoff }.toMutableList()
        if (shared.isEmpty()) records.firstOrNull()?.let(shared::add)
        val array = JSONArray()
        shared.forEach { record ->
            array.put(JSONArray().put(record.time).put(record.type.name).put(record.weight ?: JSONObjectNull))
        }
        val payload = array.toString()
        for (link in links) {
            if (link.status != ACCEPTED || link.objectId !in user.sharedLinkIds.orEmpty()) continue
            val isSender = link.senderId == user.objectId
            val prior = if (isSender) link.senderRecords else link.recipientRecords
            val priorName = if (isSender) link.senderName else link.recipientName
            if (prior == payload && priorName == user.nickname) continue
            val update = FriendLink().apply {
                if (isSender) {
                    senderRecords = payload
                    senderName = user.nickname
                } else {
                    recipientRecords = payload
                    recipientName = user.nickname
                }
            }
            awaitUpdate { update.update(link.objectId, it) }
        }
    }

    suspend fun ensureDirectory(user: LalemUser) {
        val code = user.friendCode ?: newCode()
        val expectedLookup = lookup(user.username, code)
        val found = awaitList(BmobQuery<FriendDirectory>().apply {
            addWhereEqualTo("userId", user.objectId)
            val savedId = user.directoryId
            if (savedId.isNullOrBlank()) addWhereEqualTo("lookup", expectedLookup)
            else addWhereEqualTo("objectId", savedId)
            setLimit(1)
        }).firstOrNull()
        if (found != null && (found.lookup != expectedLookup || found.nickname != user.nickname)) {
            val update = FriendDirectory().apply {
                lookup = expectedLookup
                nickname = user.nickname
            }
            awaitUpdate { update.update(found.objectId, it) }
        }
        val id = found?.objectId ?: awaitSave<String> { listener ->
            FriendDirectory().apply {
                userId = user.objectId
                lookup = expectedLookup
                nickname = user.nickname
                setACL(acl(user.objectId).apply { setPublicReadAccess(true) })
            }.save(listener)
        }
        if (id == user.directoryId && code == user.friendCode) return
        updateUser(user) {
            setACL(acl(user.objectId))
            directoryId = id
            friendCode = code
        }
        user.directoryId = id
        user.friendCode = code
    }

    suspend fun updateProfile(user: LalemUser, name: String, phone: String) {
        require(name.length in 1..20) { "昵称需为 1 至 20 个字符" }
        val newUsername = phone.takeIf { it.isNotBlank() }?.let(::loginName)
        updateUser(user) {
            nickname = name
            if (newUsername != null && newUsername != user.username) username = newUsername
            if (phone.isNotBlank() && phone != user.loginPhone) loginPhone = phone
            setACL(acl(user.objectId))
        }
        user.nickname = name
        if (newUsername != null) {
            user.username = newUsername
            user.loginPhone = phone
        }
    }

    suspend fun refreshFriendCode(user: LalemUser) {
        ensureDirectory(user)
        val code = newCode()
        val directory = FriendDirectory().apply { lookup = lookup(user.username, code) }
        awaitUpdate { directory.update(user.directoryId, it) }
        try {
            updateUser(user) { friendCode = code }
        } catch (error: Exception) {
            val previous = FriendDirectory().apply { lookup = lookup(user.username, user.friendCode.orEmpty()) }
            runCatching { awaitUpdate { previous.update(user.directoryId, it) } }
            throw error
        }
        user.friendCode = code
    }

    private suspend fun allowSharing(user: LalemUser, id: String) {
        val ids = (user.sharedLinkIds.orEmpty() + id).distinct()
        updateUser(user) { sharedLinkIds = ids }
        user.sharedLinkIds = ids
    }

    private suspend fun updateUser(user: LalemUser, changes: LalemUser.() -> Unit) {
        val update = LalemUser().apply(changes)
        awaitUpdate { update.update(user.objectId, it) }
    }

    private fun acl(vararg userIds: String) = BmobACL().apply {
        setPublicReadAccess(false)
        setPublicWriteAccess(false)
        userIds.forEach {
            setReadAccess(it, true)
            setWriteAccess(it, true)
        }
    }

    private fun newCode(): String = ByteArray(4).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02X".format(it.toInt() and 255) }

    private fun lookup(username: String, code: String) = hash("$username:$code")

    private fun loginName(phone: String): String {
        require(phone.matches(Regex("1[0-9]{10}"))) { "请输入 11 位手机号" }
        return "p_" + hash(phone)
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        const val PENDING = "pending"
        const val ACCEPTED = "accepted"
        private val JSONObjectNull = org.json.JSONObject.NULL
        fun weekStart(today: LocalDate): LocalDate =
            today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}

private suspend fun <T> awaitSave(start: (SaveListener<T>) -> Unit): T =
    suspendCancellableCoroutine { continuation ->
        start(object : SaveListener<T>() {
            override fun done(value: T?, error: BmobException?) {
                if (!continuation.isActive) return
                if (error != null) continuation.resumeWithException(error)
                else if (value != null) continuation.resume(value)
                else continuation.resumeWithException(IllegalStateException("服务端未返回结果"))
            }
        })
    }

private suspend fun awaitUpdate(start: (UpdateListener) -> Unit): Unit =
    suspendCancellableCoroutine { continuation ->
        start(object : UpdateListener() {
            override fun done(error: BmobException?) {
                if (!continuation.isActive) return
                if (error != null) continuation.resumeWithException(error)
                else continuation.resume(Unit)
            }
        })
    }

private suspend inline fun <reified T : BmobObject> awaitList(query: BmobQuery<T>): List<T> {
    return try {
        query.findObjectsObservable(T::class.java).awaitOne()
    } catch (error: BmobException) {
        if (error.errorCode == 101) emptyList() else throw error
    }
}

private suspend fun <T : Any> Observable<T>.awaitOne(): T = suspendCancellableCoroutine { continuation ->
    val subscription = firstOrError().subscribe(
        { if (continuation.isActive) continuation.resume(it) },
        { if (continuation.isActive) continuation.resumeWithException(it) }
    )
    continuation.invokeOnCancellation { subscription.dispose() }
}
