package com.example.lalem

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.bmob.v3.BmobUser
import cn.bmob.v3.exception.BmobException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.LocalDate

internal class LalemViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LalemApplication
    private val repository = LalemRepository()
    private val preferences = application.getSharedPreferences("lalem_preferences", 0)
    private var recordObserver: Job? = null
    val configured = BuildConfig.BMOB_APPLICATION_ID.isNotBlank()

    var user by mutableStateOf<LalemUser?>(null)
        private set
    var recordOwner by mutableStateOf<String?>(null)
        private set
    var pendingCount by mutableIntStateOf(0)
        private set
    var recordBusy by mutableStateOf(false)
        private set
    var nickname by mutableStateOf("")
        private set
    var phone by mutableStateOf("")
        private set
    var friendCode by mutableStateOf("")
        private set
    var records by mutableStateOf<List<Entry>>(emptyList())
        private set
    var links by mutableStateOf<List<FriendLink>>(emptyList())
        private set
    var pinned by mutableStateOf<Set<String>>(emptySet())
        private set
    var rankingEnabled by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var award by mutableStateOf(false)
        private set

    init {
        val cached = if (configured) repository.currentUser() else null
        if (cached != null) {
            applyProfile(cached)
            showRecords(cached.objectId)
            RecordSyncWorker.enqueue(app, cached.objectId)
        } else if (preferences.getBoolean("guest_open", false)) {
            showRecords(RecordStore.GUEST)
        }
    }

    fun authenticate(phone: String, password: String, registrationName: String?) = runAction {
        check(configured) { "请先在项目 local.properties 配置 Bmob Application ID" }
        val account = app.sessionLock.withLock {
            repository.authenticate(phone.trim(), password, registrationName?.trim())
        }
        app.recordStore.claimGuestRecords(account.objectId)
        preferences.edit().remove("guest_open").apply()
        applyProfile(account)
        showRecords(account.objectId)
        RecordSyncWorker.enqueue(app, account.objectId, afterPending = true)
        load(account, phone.trim())
    }

    fun startGuest() {
        preferences.edit().putBoolean("guest_open", true).apply()
        showRecords(RecordStore.GUEST)
    }

    fun leaveGuest() {
        preferences.edit().remove("guest_open").apply()
        showRecords(null)
    }

    private fun showRecords(owner: String?) {
        recordObserver?.cancel()
        recordOwner = owner
        records = emptyList()
        pendingCount = 0
        if (owner != null) {
            recordObserver = viewModelScope.launch {
                app.recordStore.observe(owner).collect { local ->
                    records = local.entries
                    pendingCount = local.pendingCount
                    checkAward()
                }
            }
        }
    }

    private fun applyProfile(account: LalemUser) {
        user = account
        nickname = account.nickname.orEmpty()
        phone = account.loginPhone.orEmpty()
        friendCode = account.friendCode.orEmpty()
        pinned = preferences.getStringSet("pinned_${account.objectId}", emptySet()).orEmpty().toSet()
        rankingEnabled = preferences.getBoolean("ranking_${account.objectId}", false)
    }

    private suspend fun load(previous: LalemUser, loginPhone: String? = null) {
        val account = repository.fetchProfile()
        check(account.objectId == previous.objectId) { "登录账号已变化，请重新登录" }
        applyProfile(account)
        links = repository.links(account)
        if (loginPhone != null) {
            repository.rememberLoginPhone(account, loginPhone)
            phone = account.loginPhone.orEmpty()
        }
        repository.ensureDirectory(account)
        friendCode = account.friendCode.orEmpty()
        checkAward()
    }

    fun refresh() = forUser {
        RecordSyncWorker.enqueue(app, it.objectId)
        load(it)
    }

    fun save(entry: Entry, onSaved: () -> Unit) = localChange(onSaved) { owner ->
        app.recordStore.save(owner, entry)
    }

    fun delete(entry: Entry, onDeleted: () -> Unit) = localChange(onDeleted) { owner ->
        app.recordStore.delete(owner, entry.id)
    }

    private fun localChange(done: () -> Unit, action: suspend (String) -> Unit) {
        val owner = recordOwner ?: return
        if (recordBusy) return
        recordBusy = true
        viewModelScope.launch {
            try {
                action(owner)
                done()
                if (owner != RecordStore.GUEST) RecordSyncWorker.enqueue(app, owner, afterPending = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = "操作未完成，请检查手机存储空间后重试"
            } finally {
                recordBusy = false
            }
        }
    }

    fun request(phone: String, code: String, onSent: () -> Unit) = forUser { account ->
        repository.requestFriend(account, phone.trim(), code.trim(), repository.links(account))
        onSent()
        load(account)
        message = "好友申请已发送"
    }

    fun accept(link: FriendLink) = forUser { account ->
        repository.acceptFriend(account, link)
        RecordSyncWorker.enqueue(app, account.objectId, afterPending = true)
        load(account)
    }

    fun remove(link: FriendLink, onRemoved: () -> Unit = {}) = forUser { account ->
        repository.removeLink(account, link)
        onRemoved()
        load(account)
    }

    fun editProfile(name: String, newPhone: String, onSaved: () -> Unit) = forUser { account ->
        repository.updateProfile(account, name.trim(), newPhone.trim())
        applyProfile(account)
        onSaved()
        try {
            repository.ensureDirectory(account)
            friendCode = account.friendCode.orEmpty()
            RecordSyncWorker.enqueue(app, account.objectId, afterPending = true)
            message = "个人资料已保存"
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            message = "资料已保存，好友资料同步失败，请在朋友页刷新"
        }
    }

    fun refreshCode() = forUser { account ->
        repository.refreshFriendCode(account)
        friendCode = account.friendCode.orEmpty()
    }

    fun togglePin(id: String) {
        val account = user ?: return
        pinned = if (id in pinned) pinned - id else pinned + id
        preferences.edit().putStringSet("pinned_${account.objectId}", pinned).apply()
    }

    fun setRanking(enabled: Boolean) {
        val account = user ?: return
        rankingEnabled = enabled
        preferences.edit().putBoolean("ranking_${account.objectId}", enabled).apply()
        checkAward()
    }

    data class Rank(
        val id: String,
        val name: String,
        val count: Int,
        val self: Boolean,
        val place: Int = 0,
        val tied: Boolean = false
    )

    fun rankings(weekly: Boolean): List<Rank> {
        val account = user ?: return emptyList()
        val today = LocalDate.now()
        val start = if (weekly) LalemRepository.weekStart(today) else today.withDayOfMonth(1)
        fun count(entries: List<Entry>) = entries.count { it.date in start..today }
        val friends = links.filter { it.status == LalemRepository.ACCEPTED }
            .distinctBy { it.otherId(account.objectId) }
            .map { Rank(it.otherId(account.objectId), it.otherName(account.objectId), count(it.otherRecords(account.objectId)), false) }
        val sorted = (friends + Rank(account.objectId, nickname, count(records), true))
            .sortedWith(compareByDescending<Rank> { it.count }.thenBy { it.id })
        val counts = sorted.groupingBy { it.count }.eachCount()
        var place = 0
        return sorted.mapIndexed { index, rank ->
            if (index == 0 || rank.count != sorted[index - 1].count) place = index + 1
            rank.copy(place = if (rank.count > 0) place else 0, tied = rank.count > 0 && counts.getValue(rank.count) > 1)
        }
    }

    private fun checkAward() {
        val account = user ?: return
        val today = LocalDate.now()
        if (!rankingEnabled || today.dayOfWeek != DayOfWeek.SUNDAY) return
        val ranks = rankings(weekly = true)
        val leader = ranks.firstOrNull() ?: return
        if (ranks.size < 3 || !leader.self || leader.place != 1 || leader.tied) return
        val week = LalemRepository.weekStart(today).toString()
        val key = "weekly_king_${account.objectId}"
        if (preferences.getString(key, "") == week) return
        preferences.edit().putString(key, week).apply()
        award = true
    }

    fun dismissAward() { award = false }
    fun dismissMessage() { message = null }

    fun logout() = forUser { account ->
        if (recordBusy) return@forUser
        RecordSyncWorker.cancel(app, account.objectId)
        app.sessionLock.withLock { BmobUser.logOut() }
        showRecords(null)
        links = emptyList()
        user = null
        phone = ""
        message = null
        award = false
    }

    private fun forUser(action: suspend (LalemUser) -> Unit) {
        val account = user ?: return
        runAction { action(account) }
    }

    private fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: BmobException) {
                message = when (error.errorCode) {
                    101 -> "账号、密码或记录有误，请检查后重试"
                    202 -> "该手机号已注册，请直接登录"
                    9010, 9016 -> "无法连接 Bmob，请检查网络后重试"
                    else -> "Bmob 请求失败（错误码 ${error.errorCode}），请重试"
                }
            } catch (error: IllegalArgumentException) {
                message = error.message ?: "输入无效"
            } catch (error: IllegalStateException) {
                message = error.message ?: "请求未完成"
            } catch (_: Exception) {
                message = "请求未完成，请检查网络后重试"
            } finally {
                busy = false
            }
        }
    }
}
